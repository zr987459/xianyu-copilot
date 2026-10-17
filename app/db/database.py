"""SQLite 持久化层。

要点：
- WAL + busy_timeout：读写并发不再抛 "database is locked"。
- isolation_level=None：事务由调用方用 BEGIN IMMEDIATE 显式控制（卡密抢占必须如此）。
- 迁移只做 ADD COLUMN / CREATE IF NOT EXISTS，绝不删表删数据；旧库可直接升级。
"""
import os
import sqlite3
from contextlib import contextmanager

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "data", "xianyu.db")
SCHEMA_VERSION = 2


def get_db(path: str = None) -> sqlite3.Connection:
    path = path or DB_PATH
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path, timeout=10, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


@contextmanager
def write_tx(conn: sqlite3.Connection):
    """写事务：BEGIN IMMEDIATE 立即拿写锁，避免两个事务都先读到同一张可用卡。"""
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise


def _columns(conn, table):
    return {r["name"] for r in conn.execute(f"PRAGMA table_info({table})")}


def init_db(path: str = None):
    conn = get_db(path)
    with write_tx(conn):
        for sql in _V1_TABLES:
            conn.execute(sql)
        _migrate_v2(conn)
        conn.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
    conn.close()


_V1_TABLES = [
    """CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL, item_id TEXT NOT NULL, chat_id TEXT,
        role TEXT NOT NULL, content TEXT NOT NULL,
        timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)""",
    """CREATE TABLE IF NOT EXISTS chat_bargain_counts (
        chat_id TEXT PRIMARY KEY, count INTEGER DEFAULT 0,
        last_updated DATETIME DEFAULT CURRENT_TIMESTAMP)""",
    """CREATE TABLE IF NOT EXISTS auto_delivery_rules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        keyword TEXT UNIQUE, delivery_content TEXT,
        use_card_pool INTEGER DEFAULT 0, auto_confirm INTEGER DEFAULT 1,
        enabled INTEGER DEFAULT 1, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)""",
    """CREATE TABLE IF NOT EXISTS card_keys (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        keyword TEXT, card_content TEXT, is_used INTEGER DEFAULT 0,
        order_id TEXT, used_at DATETIME, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)""",
    """CREATE TABLE IF NOT EXISTS delivery_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        order_id TEXT, user_id TEXT, chat_id TEXT, keyword TEXT,
        delivery_content TEXT, confirm_status TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP)""",
    """CREATE TABLE IF NOT EXISTS published_items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        item_id TEXT, title TEXT, price REAL, url TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP)""",
    "CREATE INDEX IF NOT EXISTS idx_messages_user_item ON messages(user_id, item_id, id)",
]


def _migrate_v2(conn):
    cols = _columns(conn, "card_keys")
    # status: available -> reserved -> delivered | void ；is_used 保留并同步，兼容旧 UI/统计
    if "status" not in cols:
        conn.execute("ALTER TABLE card_keys ADD COLUMN status TEXT NOT NULL DEFAULT 'available'")
        conn.execute("UPDATE card_keys SET status='delivered' WHERE is_used=1")
    if "reserved_order" not in cols:
        conn.execute("ALTER TABLE card_keys ADD COLUMN reserved_order TEXT")
    if "reserved_at" not in cols:
        conn.execute("ALTER TABLE card_keys ADD COLUMN reserved_at DATETIME")
    # 一单只能占一张卡（数量>1 的订单见 docs：reserved_order 改为 order_id#n）
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_card_reserved_order "
                 "ON card_keys(reserved_order) WHERE reserved_order IS NOT NULL")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_card_pick ON card_keys(keyword, status, id)")

    # 发货订单状态机：order_id 主键 = 天然幂等，闲鱼重复推送同一订单只会落一行
    conn.execute("""CREATE TABLE IF NOT EXISTS delivery_orders (
        order_id TEXT PRIMARY KEY,
        buyer_id TEXT, chat_id TEXT, item_id TEXT, keyword TEXT,
        state TEXT NOT NULL,
        card_id INTEGER REFERENCES card_keys(id),
        content TEXT,
        attempts INTEGER NOT NULL DEFAULT 0,
        last_error TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)""")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_delivery_state ON delivery_orders(state, updated_at)")
