"""T-015 多规格（SKU）+ 一次拍多件回归测试：python3 -m pytest tests -q"""
import asyncio
import multiprocessing as mp
import random
import sqlite3
import threading
from collections import Counter

import pytest
from fastapi.testclient import TestClient

from app.db import database
from app.db.cards import claim_cards, claim_card
from app.services.delivery_plan import (
    DeliveryPlan, match_variant, resolve_plan, format_delivery_messages,
)


def _seed(path, keyword, sku, n, prefix):
    c = sqlite3.connect(path)
    c.executemany("INSERT INTO card_keys(keyword, sku, card_content) VALUES(?,?,?)",
                  [(keyword, sku, f"{prefix}{i}") for i in range(n)])
    c.commit(); c.close()


@pytest.fixture()
def db(tmp_path, monkeypatch):
    path = str(tmp_path / "x.db")
    monkeypatch.setattr(database, "DB_PATH", path)
    database.init_db()
    c = sqlite3.connect(path); c.execute("PRAGMA journal_mode=WAL;"); c.close()
    _seed(path, "网易云", "月卡", 20, "M")
    _seed(path, "网易云", "年卡", 6, "Y")
    _seed(path, "网易云", "", 3, "D")
    return path


def _unused(path, sku):
    c = sqlite3.connect(path)
    try:
        return c.execute("SELECT COUNT(*) FROM card_keys WHERE keyword='网易云' AND sku=? AND is_used=0",
                         (sku,)).fetchone()[0]
    finally:
        c.close()


# ---------- 出库 ----------
def test_multi_qty_numbered_and_idempotent(db):
    r = claim_cards(db, "网易云", "o1", 3, "年卡")
    assert r.complete and [c["line"] for c in r.cards] == [1, 2, 3]
    assert all(c["card_content"].startswith("Y") for c in r.cards)
    again = claim_cards(db, "网易云", "o1", 3, "年卡")
    assert [c["id"] for c in again.cards] == [c["id"] for c in r.cards] and again.newly_claimed == 0
    assert _unused(db, "年卡") == 3


def test_all_or_nothing_then_restock(db):
    r = claim_cards(db, "网易云", "big", 5, "年卡")
    assert r.complete and _unused(db, "年卡") == 1
    short = claim_cards(db, "网易云", "o2", 3, "年卡")
    assert not short.complete and short.cards == [] and short.available == 1
    assert _unused(db, "年卡") == 1  # 一张都没占
    _seed(db, "网易云", "年卡", 2, "Y2-")
    ok = claim_cards(db, "网易云", "o2", 3, "年卡")
    assert ok.complete and len({c["id"] for c in ok.cards}) == 3


def test_partial_mode_tops_up(db):
    r = claim_cards(db, "网易云", "p", 5, "", allow_partial=True)
    assert len(r.cards) == 3 and r.shortage == 2
    _seed(db, "网易云", "", 4, "D2-")
    r2 = claim_cards(db, "网易云", "p", 5, "", allow_partial=True)
    assert r2.complete and [c["line"] for c in r2.cards] == [1, 2, 3, 4, 5]


def test_sku_isolation_and_normalization(db):
    r = claim_cards(db, "网易云", "o3", 2, " 年 卡 ")  # 空白会被归一化
    assert all(c["card_content"].startswith("Y") for c in r.cards)
    assert claim_card(db, "网易云", "legacy")["card_content"].startswith("D")  # 旧调用走默认套餐


@pytest.mark.parametrize("qty", [0, -1, 51, "3", True])
def test_invalid_quantity(db, qty):
    with pytest.raises(ValueError):
        claim_cards(db, "网易云", "bad", qty, "年卡")


def test_threads_same_order_no_double_claim(db):
    out = []
    def w():
        out.append(tuple(c["id"] for c in claim_cards(db, "网易云", "dup", 4, "月卡").cards))
    ts = [threading.Thread(target=w) for _ in range(12)]
    [t.start() for t in ts]; [t.join() for t in ts]
    assert len(set(out)) == 1 and len(out[0]) == 4 and _unused(db, "月卡") == 16


def _proc(path, seed, q):
    rnd = random.Random(seed)
    res = []
    for i in range(12):
        sku = rnd.choice(["月卡", "年卡"])
        qty = rnd.randint(1, 4)
        r = claim_cards(path, "网易云", f"s{seed}-{i}", qty, sku)
        res.append((sku, qty, [c["card_content"] for c in r.cards]))
    q.put(res)


def test_processes_mixed_sku_qty_no_oversell(db):
    ctx = mp.get_context("spawn")
    q = ctx.Queue()
    ps = [ctx.Process(target=_proc, args=(db, k, q)) for k in range(4)]
    [p.start() for p in ps]
    res = sum((q.get(timeout=90) for _ in ps), []); [p.join() for p in ps]
    got = [c for _, _, cards in res for c in cards]
    assert len(got) == len(set(got))                       # 没有一张卡发给两个订单
    for sku, qty, cards in res:
        assert len(cards) in (0, qty)                      # 全有或全无
        assert all(c.startswith("M" if sku == "月卡" else "Y") for c in cards)
    assert sum(1 for c in got if c.startswith("M")) <= 20 and sum(1 for c in got if c.startswith("Y")) <= 6


def test_migration_from_v1(tmp_path, monkeypatch):
    path = str(tmp_path / "old.db")
    c = sqlite3.connect(path)
    c.executescript("""
    CREATE TABLE card_keys (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT, card_content TEXT,
        is_used INTEGER DEFAULT 0, order_id TEXT, used_at DATETIME, created_at DATETIME DEFAULT CURRENT_TIMESTAMP);
    CREATE UNIQUE INDEX idx_card_order ON card_keys(order_id) WHERE order_id IS NOT NULL;
    CREATE TABLE delivery_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, order_id TEXT, user_id TEXT, chat_id TEXT,
        keyword TEXT, delivery_content TEXT, confirm_status TEXT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP);
    INSERT INTO card_keys(keyword, card_content, is_used, order_id) VALUES ('vip','OLD1',1,'old-order');
    INSERT INTO card_keys(keyword, card_content) VALUES ('vip','OLD2'),('vip','OLD3');
    INSERT INTO delivery_logs(order_id, keyword, confirm_status) VALUES ('old-order','vip','成功');
    """)
    c.commit(); c.close()
    monkeypatch.setattr(database, "DB_PATH", path)
    database.init_db(); database.init_db()  # 可重复执行
    c = sqlite3.connect(path)
    rows = c.execute("SELECT card_content, sku, order_line FROM card_keys ORDER BY id").fetchall()
    assert rows == [("OLD1", "", 1), ("OLD2", "", None), ("OLD3", "", None)]
    assert c.execute("SELECT quantity FROM delivery_logs").fetchone()[0] == 1
    c.close()
    assert claim_card(path, "vip", "old-order")["card_content"] == "OLD1"
    assert len(claim_cards(path, "vip", "new", 2).cards) == 2  # 旧唯一索引已换掉，一单可多卡


# ---------- 套餐匹配 / 排版 ----------
V = [{"id": 1, "sku_pattern": "年卡"}, {"id": 2, "sku_pattern": "半年卡"},
     {"id": 3, "sku_pattern": "月卡"}, {"id": 4, "sku_pattern": "专业版"}]


@pytest.mark.parametrize("desc,want", [
    ("套餐:年卡", 1), ("套餐：半年卡", 2), ("规格:月卡;版本:基础", 3),
    ("专业版 年卡", None), ("网易云半年卡畅听", 2), ("ＰＲＯ", None), ("季卡", None), ("", None),
])
def test_match_variant(desc, want):
    v = match_variant(V, desc)
    assert (v["id"] if v else None) == want


def test_wildcard_and_resolve():
    rule = {"keyword": "网易云", "delivery_content": "通用说明", "use_card_pool": 1, "auto_confirm": 1}
    assert resolve_plan(rule, [], "套餐:年卡").card_sku == ""            # 没配套餐 = 旧行为
    assert resolve_plan(rule, V, "季卡") is None                          # 对不上 → 转人工
    vs = V + [{"id": 9, "sku_pattern": "*"}, {"id": 5, "sku_pattern": "网盘版", "use_card_pool": 0,
                                              "delivery_content": "专属网盘"}]
    assert resolve_plan(rule, vs, "季卡").card_sku == ""
    p = resolve_plan(rule, vs, "版本:网盘版")
    assert p.fixed_content == "专属网盘" and not p.use_card_pool


def test_format_numbered_and_split():
    plan = DeliveryPlan("网易云", "年卡", "年卡", "说明", True, True)
    cards = [{"card_content": f"CARD-{i:03d}"} for i in range(1, 4)]
    [m] = format_delivery_messages(plan, cards, 3)
    assert "规格：年卡 × 3" in m and "共 3 张" in m and "① CARD-001" in m and "③ CARD-003" in m
    many = [{"card_content": "X" * 40 + str(i)} for i in range(1, 31)]
    msgs = format_delivery_messages(plan, many, 30, max_chars=300)
    assert len(msgs) > 2 and all(len(x) <= 300 for x in msgs)
    joined = "\n".join(msgs)
    assert all(joined.count("X" * 40 + str(i) + "\n") + joined.endswith("X" * 40 + str(i)) >= 1 for i in range(1, 31))
    assert "21. " in joined and "⑳" in joined


# ---------- 发货管理器端到端 ----------
class _Svc:
    calls = 0
    def confirm_dummy_shipping(self, order_id, trade_text=""):
        _Svc.calls += 1
        return True, "SUCCESS"


def _manager(db):
    from app.services.delivery_manager import AutoDeliveryManager
    m = AutoDeliveryManager(db, None)
    m.delivery_service = _Svc()
    c = sqlite3.connect(db)
    rid = c.execute("INSERT INTO auto_delivery_rules(keyword, delivery_content, use_card_pool) "
                    "VALUES('网易云','使用说明',1) RETURNING id").fetchone()[0]
    c.executemany("INSERT INTO delivery_rule_skus(rule_id, sku_pattern, card_sku) VALUES(?,?,?)",
                  [(rid, "月卡", "月卡"), (rid, "年卡", "年卡")])
    c.commit(); c.close()
    return m


def test_manager_end_to_end(db):
    m = _manager(db)
    sent = []
    async def send(ws, chat_id, user_id, text):
        sent.append(text)
    run = lambda *a, **k: asyncio.run(m.handle_paid_order(None, send, "u", "c", *a, **k))

    assert run("A1", "网易云音乐 会员", "套餐:年卡", 2)
    assert len(sent) == 1 and "① Y" in sent[0] and "② Y" in sent[0]
    assert run("A1", "网易云音乐 会员", "套餐:年卡", 2) and len(sent) == 1   # 重复推送不再发

    assert not run("A2", "网易云音乐 会员", "套餐:季卡", 1)                  # 套餐对不上
    assert len(sent) == 1

    assert not run("A3", "网易云音乐 会员", "套餐:年卡", 6)                  # 只剩 4 张年卡
    assert len(sent) == 2 and "补货" in sent[1]
    assert not run("A3", "网易云音乐 会员", "套餐:年卡", 6) and len(sent) == 2  # 缺货通知只发一次
    assert _unused(db, "年卡") == 4

    _seed(db, "网易云", "年卡", 2, "Y-R")
    done = asyncio.run(m.retry_pending(None, send))
    assert done == 1 and "共 6 张" in sent[-1] and _unused(db, "年卡") == 0


def test_manager_concurrent_duplicate_push(db):
    m = _manager(db)
    sent = []
    async def send(ws, chat_id, user_id, text):
        await asyncio.sleep(0.01); sent.append(text)
    async def main():
        await asyncio.gather(*[m.handle_paid_order(None, send, "u", "c", "B1", "网易云", "月卡", 3)
                               for _ in range(5)])
    asyncio.run(main())
    assert len(sent) == 1 and _unused(db, "月卡") == 17


# ---------- API ----------
@pytest.fixture()
def client(db, monkeypatch):
    monkeypatch.setenv("ADMIN_TOKEN", "t0k")
    import app.api.routes as R
    with TestClient(R.app) as c:
        c.headers["Authorization"] = "Bearer t0k"
        yield c


def test_api_sku_flow(client):
    assert client.post("/api/cards/import", json={"keyword": "网易云", "sku": "季卡", "raw_keys": "S1\nS2"}).json()["count"] == 2
    assert client.post("/api/cards/add", json={"keyword": "网易云", "sku": "季卡", "card_content": "S3"}).json()["success"]
    lst = client.get("/api/cards/list", params={"keyword": "网易云", "sku": "季卡"}).json()["cards"]
    assert {c["card_content"] for c in lst} == {"S1", "S2", "S3"}
    dist = {s["sku"]: s["unused"] for s in client.get("/api/cards/skus", params={"keyword": "网易云"}).json()["skus"]}
    assert dist == {"": 3, "季卡": 3, "年卡": 6, "月卡": 20}

    body = {"keyword": "网易云", "use_card_pool": True,
            "variants": [{"sku_pattern": "季卡"}, {"sku_pattern": "年卡", "delivery_content": "年卡专属"}]}
    rid = client.post("/api/delivery/rules", json=body).json()["id"]
    body["delivery_content"] = "改了说明"
    assert client.post("/api/delivery/rules", json=body).json()["id"] == rid      # UPSERT 不换 id
    rule = client.get("/api/delivery/rules").json()["rules"][0]
    assert [(v["sku_pattern"], v["card_stock"]) for v in rule["variants"]] == [("季卡", 3), ("年卡", 6)]
    assert client.post("/api/delivery/rules", json={"keyword": "x", "variants": [{"sku_pattern": "A"}, {"sku_pattern": " a "}]}).status_code == 400

    r = client.post("/api/cards/claim", json={"keyword": "网易云", "sku": "季卡", "order_id": "z", "quantity": 3}).json()
    assert r["count"] == 3
    assert client.post("/api/cards/claim", json={"keyword": "网易云", "sku": "季卡", "order_id": "z2", "quantity": 1}).status_code == 409
