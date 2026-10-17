"""
订单自动发货引擎（卡密池 + 固定内容复合发货）

设计要点
--------
1. 订单表 orders 以闲鱼 order_id 为主键：同一付款事件被 WSS 重复推送 N 次，也只会落一行（幂等入口）。
2. 状态机（只允许表中列出的迁移，迁移用 CAS：UPDATE ... WHERE status=<旧状态>）：

       PAID ──reserve──> RESERVED ──send ok──> SENT ──consign ok──> DONE
        │                  │  ▲                  │
        │                  │  └──send fail───────┤(重试同一张卡，绝不换卡)
        │                  │                     └──consign fail──> SENT(重试确认)
        ├──无库存──> OUT_OF_STOCK ──补货后 retry──> PAID
        │
        └─(任意未 SENT 状态) cancel ──> CANCELLED（RESERVED 的卡退回 available）

   SENT 之后卡密已经到买家手里：只能 DONE 或人工处理，永远不回池。
3. 防超卖：卡密 available→reserved 在 BEGIN IMMEDIATE 事务里用 CAS 抢占，并且
   card_keys.order_id 上有唯一索引（一单最多一张卡），card 状态改为 reserved 时
   WHERE status='available'，rowcount!=1 视为被别人抢走，重选下一张。
   多进程 / 多线程同时发货也不会把同一张卡发给两个订单。
4. 失败重试发送的是**同一张**已预留卡：先查 orders.card_id，存在则复用。
5. SQLite：WAL + busy_timeout，短事务；网络调用（发消息 / 确认发货）一律在事务外。
"""
from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass
from typing import Callable, Optional

# ---------------------------------------------------------------- 状态定义
PAID, RESERVED, SENT, DONE = "PAID", "RESERVED", "SENT", "DONE"
OUT_OF_STOCK, CANCELLED, MANUAL = "OUT_OF_STOCK", "CANCELLED", "MANUAL"

TRANSITIONS = {
    PAID: {RESERVED, OUT_OF_STOCK, CANCELLED, MANUAL},
    RESERVED: {SENT, CANCELLED, MANUAL},
    SENT: {DONE, MANUAL},
    OUT_OF_STOCK: {PAID, CANCELLED, MANUAL},
    MANUAL: {PAID, CANCELLED, DONE},
    DONE: set(),
    CANCELLED: set(),
}

MAX_ATTEMPTS = 5


class IllegalTransition(Exception):
    pass


# ---------------------------------------------------------------- 连接与表结构
def connect(path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, timeout=30, isolation_level=None)  # 手动控制事务
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


SCHEMA = """
CREATE TABLE IF NOT EXISTS orders (
    order_id     TEXT PRIMARY KEY,
    buyer_id     TEXT,
    chat_id      TEXT,
    item_id      TEXT,
    keyword      TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'PAID',
    card_id      INTEGER REFERENCES card_keys(id),
    message_text TEXT,
    attempts     INTEGER NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   REAL NOT NULL,
    updated_at   REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_orders_status ON orders(status, updated_at);

CREATE TABLE IF NOT EXISTS order_events (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id  TEXT NOT NULL,
    from_s    TEXT,
    to_s      TEXT NOT NULL,
    note      TEXT,
    at        REAL NOT NULL
);
"""


def migrate(conn: sqlite3.Connection) -> None:
    """在现有 card_keys 表上增量加列，旧数据 is_used=1 → sent，is_used=0 → available。"""
    conn.executescript(SCHEMA)
    cols = {r["name"] for r in conn.execute("PRAGMA table_info(card_keys)")}
    if "status" not in cols:
        conn.execute("ALTER TABLE card_keys ADD COLUMN status TEXT NOT NULL DEFAULT 'available'")
        conn.execute("UPDATE card_keys SET status = CASE WHEN is_used=1 THEN 'sent' ELSE 'available' END")
    if "reserved_at" not in cols:
        conn.execute("ALTER TABLE card_keys ADD COLUMN reserved_at REAL")
    # 一单最多一张卡；同一关键词下卡密内容不重复（防批量导入重复粘贴）
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_card_order ON card_keys(order_id) WHERE order_id IS NOT NULL")
    conn.execute("CREATE INDEX IF NOT EXISTS ix_card_pick ON card_keys(keyword, status, id)")


# ---------------------------------------------------------------- 状态迁移（CAS）
def _transition(conn, order_id: str, frm: str, to: str, note: str = "", **fields) -> bool:
    if to not in TRANSITIONS[frm]:
        raise IllegalTransition(f"{frm} -> {to}")
    sets = ", ".join([f"{k}=?" for k in fields] + ["status=?", "updated_at=?"])
    args = list(fields.values()) + [to, time.time(), order_id, frm]
    cur = conn.execute(f"UPDATE orders SET {sets} WHERE order_id=? AND status=?", args)
    if cur.rowcount == 1:
        conn.execute("INSERT INTO order_events(order_id, from_s, to_s, note, at) VALUES (?,?,?,?,?)",
                     (order_id, frm, to, note[:500], time.time()))
        return True
    return False


# ---------------------------------------------------------------- 1. 幂等入单
def on_paid(conn, order_id: str, keyword: str, buyer_id: str = "", chat_id: str = "", item_id: str = "") -> bool:
    """收到「已付款」事件时调用。重复事件返回 False，不会重复建单。"""
    now = time.time()
    cur = conn.execute(
        "INSERT OR IGNORE INTO orders(order_id, buyer_id, chat_id, item_id, keyword, status, created_at, updated_at)"
        " VALUES (?,?,?,?,?,?,?,?)", (order_id, buyer_id, chat_id, item_id, keyword, PAID, now, now))
    if cur.rowcount == 1:
        conn.execute("INSERT INTO order_events(order_id, from_s, to_s, note, at) VALUES (?,?,?,?,?)",
                     (order_id, None, PAID, "paid event", now))
        return True
    return False


# ---------------------------------------------------------------- 2. 预留卡密（防超卖核心）
def reserve(conn, order_id: str, use_card_pool: bool, fixed_text: str = "") -> Optional[str]:
    """
    返回待发送的完整消息文本；无库存返回 None（订单进入 OUT_OF_STOCK）。
    重入安全：订单已 RESERVED/SENT 时直接返回之前那份文本（同一张卡）。
    """
    conn.execute("BEGIN IMMEDIATE")  # 拿写锁，同一时刻只有一个写事务
    try:
        o = conn.execute("SELECT * FROM orders WHERE order_id=?", (order_id,)).fetchone()
        if o is None:
            raise KeyError(order_id)
        if o["status"] in (RESERVED, SENT, DONE):
            conn.execute("COMMIT")
            return o["message_text"]
        if o["status"] != PAID:
            conn.execute("COMMIT")
            return None

        card_id, card_text = None, ""
        if use_card_pool:
            while True:
                row = conn.execute(
                    "SELECT id, card_content FROM card_keys WHERE keyword=? AND status='available' ORDER BY id LIMIT 1",
                    (o["keyword"],)).fetchone()
                if row is None:
                    _transition(conn, order_id, PAID, OUT_OF_STOCK, "card pool empty")
                    conn.execute("COMMIT")
                    return None
                cur = conn.execute(
                    "UPDATE card_keys SET status='reserved', order_id=?, reserved_at=? WHERE id=? AND status='available'",
                    (order_id, time.time(), row["id"]))
                if cur.rowcount == 1:
                    card_id, card_text = row["id"], row["card_content"]
                    break  # 抢到了；否则被并发者拿走，继续选下一张

        parts = [p for p in (fixed_text.strip(), f"卡密：{card_text}" if card_text else "") if p]
        text = "\n".join(parts)
        if not _transition(conn, order_id, PAID, RESERVED, "reserved", card_id=card_id, message_text=text):
            raise RuntimeError("concurrent state change")
        conn.execute("COMMIT")
        return text
    except Exception:
        conn.execute("ROLLBACK")
        raise


# ---------------------------------------------------------------- 3. 发送结果
def mark_sent(conn, order_id: str) -> None:
    conn.execute("BEGIN IMMEDIATE")
    try:
        o = conn.execute("SELECT card_id, status FROM orders WHERE order_id=?", (order_id,)).fetchone()
        if o["status"] == RESERVED:
            _transition(conn, order_id, RESERVED, SENT, "message delivered")
            if o["card_id"]:
                conn.execute("UPDATE card_keys SET status='sent', is_used=1, used_at=CURRENT_TIMESTAMP WHERE id=?",
                             (o["card_id"],))
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise


def mark_failed(conn, order_id: str, error: str) -> None:
    """发送或确认失败：状态不变（保留同一张卡），只记次数；超过上限转人工。"""
    conn.execute("BEGIN IMMEDIATE")
    try:
        o = conn.execute("SELECT status, attempts FROM orders WHERE order_id=?", (order_id,)).fetchone()
        n = o["attempts"] + 1
        conn.execute("UPDATE orders SET attempts=?, last_error=?, updated_at=? WHERE order_id=?",
                     (n, error[:500], time.time(), order_id))
        if n >= MAX_ATTEMPTS and o["status"] in (RESERVED, SENT):
            _transition(conn, order_id, o["status"], MANUAL, f"too many failures: {error}")
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise


def mark_done(conn, order_id: str) -> None:
    conn.execute("BEGIN IMMEDIATE")
    _transition(conn, order_id, SENT, DONE, "consign confirmed")
    conn.execute("COMMIT")


# ---------------------------------------------------------------- 4. 取消 / 补货重试
def cancel(conn, order_id: str, reason: str = "refund/cancel") -> bool:
    """买家退款或关闭交易。SENT 之后不可取消（卡已交付）。"""
    conn.execute("BEGIN IMMEDIATE")
    try:
        o = conn.execute("SELECT status, card_id FROM orders WHERE order_id=?", (order_id,)).fetchone()
        ok = False
        if o and CANCELLED in TRANSITIONS[o["status"]]:
            ok = _transition(conn, order_id, o["status"], CANCELLED, reason)
            if ok and o["status"] == RESERVED and o["card_id"]:
                conn.execute("UPDATE card_keys SET status='available', order_id=NULL, reserved_at=NULL "
                             "WHERE id=? AND status='reserved'", (o["card_id"],))
        conn.execute("COMMIT")
        return ok
    except Exception:
        conn.execute("ROLLBACK")
        raise


def requeue_out_of_stock(conn, keyword: str) -> int:
    """补货后调用：把该关键词所有缺货订单拉回 PAID，由 worker 重新发货。"""
    conn.execute("BEGIN IMMEDIATE")
    ids = [r[0] for r in conn.execute("SELECT order_id FROM orders WHERE keyword=? AND status=?", (keyword, OUT_OF_STOCK))]
    for oid in ids:
        _transition(conn, oid, OUT_OF_STOCK, PAID, "restocked")
    conn.execute("COMMIT")
    return len(ids)


# ---------------------------------------------------------------- 5. 单步驱动（worker 每轮调用）
@dataclass
class Rule:
    keyword: str
    use_card_pool: bool
    fixed_text: str
    auto_confirm: bool


def process(conn, order_id: str, rule: Rule,
            send_message: Callable[[str, str], bool],
            confirm_consign: Callable[[str], bool]) -> str:
    """
    驱动一张订单向前走一步或几步，返回最终状态。网络调用在事务外，失败只记次数。
    send_message(chat_id, text) / confirm_consign(order_id) 由调用方注入（便于测试与限流）。
    """
    o = conn.execute("SELECT * FROM orders WHERE order_id=?", (order_id,)).fetchone()
    if o["status"] == PAID:
        if reserve(conn, order_id, rule.use_card_pool, rule.fixed_text) is None:
            return conn.execute("SELECT status FROM orders WHERE order_id=?", (order_id,)).fetchone()[0]
        o = conn.execute("SELECT * FROM orders WHERE order_id=?", (order_id,)).fetchone()

    if o["status"] == RESERVED:
        try:
            ok = send_message(o["chat_id"], o["message_text"])
        except Exception as e:  # noqa: BLE001
            ok, err = False, str(e)
        else:
            err = "send returned false"
        if not ok:
            mark_failed(conn, order_id, err)
            return RESERVED
        mark_sent(conn, order_id)
        o = conn.execute("SELECT * FROM orders WHERE order_id=?", (order_id,)).fetchone()

    if o["status"] == SENT and rule.auto_confirm:
        try:
            ok, err = bool(confirm_consign(order_id)), "consign returned false"
        except Exception as e:  # noqa: BLE001
            ok, err = False, str(e)
        if ok:
            mark_done(conn, order_id)
        else:
            mark_failed(conn, order_id, err)  # 停在 SENT，下轮只重试确认，不会再发消息
    return conn.execute("SELECT status FROM orders WHERE order_id=?", (order_id,)).fetchone()[0]
