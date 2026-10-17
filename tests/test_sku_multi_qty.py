"""多规格 (SKU) + 一单多件 回归测试：python3 -m pytest tests -q"""
import asyncio
import multiprocessing as mp
import sqlite3
import threading

import pytest
from fastapi.testclient import TestClient

from app.db import database
from app.db.cards import claim_cards, format_cards_message, stock_by_sku
from app.services.delivery_manager import AutoDeliveryManager, parse_order_meta


@pytest.fixture()
def db(tmp_path, monkeypatch):
    path = str(tmp_path / "x.db")
    monkeypatch.setattr(database, "DB_PATH", path)
    database.init_db()
    c = sqlite3.connect(path)
    c.execute("PRAGMA journal_mode=WAL;")
    rows = [("网易云音乐", "月卡", f"M{i}") for i in range(30)]
    rows += [("网易云音乐", "年卡", f"Y{i}") for i in range(5)]
    rows += [("网易云音乐", "", f"G{i}") for i in range(3)]
    c.executemany("INSERT INTO card_keys(keyword, sku, card_content) VALUES (?,?,?)", rows)
    c.commit(); c.close()
    return path


# ---------- 迁移 ----------
def test_migration_from_legacy_schema(tmp_path, monkeypatch):
    path = str(tmp_path / "old.db")
    c = sqlite3.connect(path)
    c.execute("CREATE TABLE card_keys (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT, card_content TEXT,"
              " is_used INTEGER DEFAULT 0, order_id TEXT, used_at DATETIME, created_at DATETIME)")
    c.execute("CREATE TABLE auto_delivery_rules (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT UNIQUE,"
              " delivery_content TEXT, use_card_pool INTEGER DEFAULT 0, auto_confirm INTEGER DEFAULT 1,"
              " enabled INTEGER DEFAULT 1, created_at DATETIME)")
    c.execute("CREATE UNIQUE INDEX idx_card_order ON card_keys(order_id) WHERE order_id IS NOT NULL")
    c.execute("INSERT INTO card_keys(keyword, card_content, is_used, order_id) VALUES ('vip','OLD',1,'o-old')")
    c.execute("INSERT INTO card_keys(keyword, card_content) VALUES ('vip','A'),('vip','B')")
    c.commit(); c.close()
    monkeypatch.setattr(database, "DB_PATH", path)
    database.init_db(); database.init_db()  # 可重复执行
    r = claim_cards(path, "vip", "o-new", quantity=2)
    assert [x["card_content"] for x in r.cards] == ["A", "B"]
    again = claim_cards(path, "vip", "o-old")
    assert again.cards[0]["card_content"] == "OLD" and again.cards[0]["order_seq"] == 1


# ---------- claim_cards ----------
def test_multi_qty_atomic_and_idempotent(db):
    r = claim_cards(db, "网易云音乐", "o1", quantity=3, sku="月卡")
    assert r.complete and [c["order_seq"] for c in r.cards] == [1, 2, 3]
    assert all(c["card_content"].startswith("M") for c in r.cards)
    r2 = claim_cards(db, "网易云音乐", "o1", quantity=3, sku="月卡")
    assert [c["card_content"] for c in r2.cards] == [c["card_content"] for c in r.cards]
    assert r2.newly_claimed == []


def test_sku_isolation(db):
    r = claim_cards(db, "网易云音乐", "o-y", quantity=5, sku="年卡")
    assert r.complete and all(c["card_content"].startswith("Y") for c in r.cards)
    # 年卡空了也绝不挪用月卡/通用池
    r2 = claim_cards(db, "网易云音乐", "o-y2", quantity=1, sku="年卡")
    assert r2.cards == [] and r2.shortage == 1


def test_partial_then_backfill(db):
    r = claim_cards(db, "网易云音乐", "o-p", quantity=5, sku="", allow_partial=True)
    assert r.delivered == 3 and r.shortage == 2
    c = sqlite3.connect(db)
    c.executemany("INSERT INTO card_keys(keyword, sku, card_content) VALUES ('网易云音乐','',?)", [("G9",), ("G10",)])
    c.commit(); c.close()
    r2 = claim_cards(db, "网易云音乐", "o-p", quantity=5, sku="")
    assert r2.complete and [c["order_seq"] for c in r2.newly_claimed] == [4, 5]
    assert len({c["card_content"] for c in r2.cards}) == 5


def test_hold_policy_takes_nothing(db):
    r = claim_cards(db, "网易云音乐", "o-h", quantity=6, sku="年卡", allow_partial=False)
    assert r.cards == [] and r.shortage == 6
    assert [s for s in stock_by_sku(db) if s["sku"] == "年卡"][0]["unused"] == 5


def test_quantity_bounds(db):
    for q in (0, -1, 51):
        with pytest.raises(ValueError):
            claim_cards(db, "网易云音乐", "o-b", quantity=q, sku="月卡")


def test_threads_multi_qty_no_oversell(db):
    """40 个线程、每单 2~3 件抢 30 张月卡：不超卖、不重复、每单要么部分要么齐、序号连续"""
    out, lock = {}, threading.Lock()
    def w(i):
        r = claim_cards(db, "网易云音乐", f"t{i}", quantity=2 + i % 2, sku="月卡")
        with lock:
            out[f"t{i}"] = r
    ts = [threading.Thread(target=w, args=(i,)) for i in range(40)]
    [t.start() for t in ts]; [t.join() for t in ts]
    cards = [c["card_content"] for r in out.values() for c in r.cards]
    assert len(cards) == 30 and len(set(cards)) == 30
    for r in out.values():
        assert [c["order_seq"] for c in r.cards] == list(range(1, len(r.cards) + 1))
    assert sum(1 for s in stock_by_sku(db) if s["sku"] == "月卡" and s["unused"] == 0) == 1


def _proc(db, base, q):
    res = []
    for i in range(6):
        r = claim_cards(db, "网易云音乐", f"p{base + i}", quantity=2, sku="月卡")
        res += [c["card_content"] for c in r.cards]
    q.put(res)


def test_processes_multi_qty_no_oversell(db):
    ctx = mp.get_context("spawn")
    q = ctx.Queue()
    ps = [ctx.Process(target=_proc, args=(db, k * 100, q)) for k in range(4)]
    [p.start() for p in ps]
    got = sum((q.get(timeout=60) for _ in ps), []); [p.join() for p in ps]
    assert len(got) == 30 and len(set(got)) == 30


def test_format_message():
    msg = format_cards_message([{"order_seq": 2, "card_content": "B"}, {"order_seq": 1, "card_content": "A"}], 2, "月卡")
    lines = msg.splitlines()
    assert lines[0] == "【您的专属卡密 · 月卡 × 2】" and lines[1] == "① A" and lines[2] == "② B"
    assert "温馨提示" in lines[-1]


# ---------- 订单解析与发货管理器 ----------
def test_parse_order_meta():
    payload = {"data": {"order": {"skuDesc": "套餐：年卡 ", "buyAmount": "3"}}}
    assert parse_order_meta(payload) == ("套餐：年卡", 3)
    assert parse_order_meta({"x": 1}) == ("", 1)


class _FakeDelivery:
    def __init__(self): self.calls = []
    def confirm_dummy_shipping(self, order_id, trade_text=""):
        self.calls.append(order_id); return True, "SUCCESS"


def _manager(db):
    m = AutoDeliveryManager.__new__(AutoDeliveryManager)
    m.db_path = db
    m.delivery_service = _FakeDelivery()
    return m


def _rule(db, policy="partial"):
    c = sqlite3.connect(db)
    c.execute("INSERT INTO auto_delivery_rules(keyword, delivery_content, use_card_pool, shortage_policy)"
              " VALUES ('网易云音乐', '使用说明：打开App兑换', 1, ?)", (policy,))
    rid = c.execute("SELECT id FROM auto_delivery_rules WHERE keyword='网易云音乐'").fetchone()[0]
    c.executemany("INSERT INTO delivery_rule_skus(rule_id, sku_name, aliases, delivery_content, use_card_pool)"
                  " VALUES (?,?,?,?,1)", [(rid, "月卡", "1个月,30天", ""), (rid, "年卡", "12个月", "年卡专属说明")])
    c.commit(); c.close()


def _run(m, sent, **kw):
    async def send(ws, chat_id, user_id, msg): sent.append(msg)
    return asyncio.run(m.handle_paid_order(None, send, "u1", "c1", item_title="网易云音乐 黑胶VIP", **kw))


def test_manager_sku_and_qty(db):
    _rule(db); m = _manager(db); sent = []
    assert _run(m, sent, order_id="A1", order_payload={"skuDesc": "规格:30天", "buyAmount": 3}) is True
    assert "【您的专属卡密 · 月卡 × 3】" in sent[0] and "③ M2" in sent[0]
    assert m.delivery_service.calls == ["A1"]
    # 重推事件：不再发消息、不再确认
    assert _run(m, sent, order_id="A1", order_payload={"skuDesc": "规格:30天", "buyAmount": 3}) is True
    assert len(sent) == 1 and m.delivery_service.calls == ["A1"]


def test_manager_unmatched_sku_goes_manual(db):
    _rule(db); m = _manager(db); sent = []
    assert _run(m, sent, order_id="A2", sku_desc="季卡", quantity=1) is False
    assert sent == [] and m.get_order("A2")["status"] == "manual"


def test_manager_partial_then_backfill(db):
    _rule(db); m = _manager(db); sent = []
    assert _run(m, sent, order_id="A3", sku_desc="年卡", quantity=7) is False
    assert "已发 5 件，剩余 2 件" in sent[0] and "年卡专属说明" in sent[0]
    assert m.delivery_service.calls == [] and m.get_order("A3")["status"] == "partial"
    # 仍缺货时重跑不打扰买家
    assert _run(m, sent, order_id="A3") is False and len(sent) == 1
    c = sqlite3.connect(db)
    c.executemany("INSERT INTO card_keys(keyword, sku, card_content) VALUES ('网易云音乐','年卡',?)", [("Y8",), ("Y9",)])
    c.commit(); c.close()
    assert _run(m, sent, order_id="A3") is True
    assert "【自动补发】" in sent[1] and "⑥ Y8" in sent[1] and "⑦ Y9" in sent[1] and "年卡专属说明" not in sent[1]
    assert m.delivery_service.calls == ["A3"] and m.get_order("A3")["status"] == "delivered"


def test_manager_hold_policy(db):
    _rule(db, policy="hold"); m = _manager(db); sent = []
    assert _run(m, sent, order_id="A4", sku_desc="年卡", quantity=6) is False
    assert "一次性自动发齐" in sent[0] and "Y0" not in sent[0]
    assert m.get_order("A4")["status"] == "pending_stock"


# ---------- API ----------
@pytest.fixture()
def client(db, monkeypatch):
    monkeypatch.setenv("ADMIN_TOKEN", "t0k")
    import app.api.routes as R
    with TestClient(R.app) as c:
        c.headers.update({"Authorization": "Bearer t0k"})
        yield c


def test_api_sku_flow(client):
    assert client.post("/api/cards/import", json={"keyword": "QQ音乐", "sku": "季卡", "raw_keys": "S1\nS2\nS2"}).json()["count"] == 2
    assert client.post("/api/cards/add", json={"keyword": "QQ音乐", "card_content": "G1"}).status_code == 200
    lst = client.get("/api/cards/list", params={"keyword": "QQ音乐", "sku": "季卡"}).json()["cards"]
    assert {c["card_content"] for c in lst} == {"S1", "S2"}
    r = client.post("/api/delivery/rules", json={"keyword": "QQ音乐", "use_card_pool": True, "shortage_policy": "hold",
                                                 "skus": [{"sku_name": "季卡", "aliases": "3个月"}]})
    assert r.status_code == 200
    rule = [x for x in client.get("/api/delivery/rules").json()["rules"] if x["keyword"] == "QQ音乐"][0]
    assert rule["skus"][0]["card_stock"] == 2 and rule["generic_stock"] == 1 and rule["shortage_policy"] == "hold"
    # UPSERT 保留 id
    client.post("/api/delivery/rules", json={"keyword": "QQ音乐", "use_card_pool": True})
    rule2 = [x for x in client.get("/api/delivery/rules").json()["rules"] if x["keyword"] == "QQ音乐"][0]
    assert rule2["id"] == rule["id"] and len(rule2["skus"]) == 1
    # 手动出库默认凑齐才出
    assert client.post("/api/cards/claim", json={"keyword": "QQ音乐", "sku": "季卡", "order_id": "x", "quantity": 3}).status_code == 409
    ok = client.post("/api/cards/claim", json={"keyword": "QQ音乐", "sku": "季卡", "order_id": "x", "quantity": 2}).json()
    assert ok["complete"] and [c["seq"] for c in ok["cards"]] == [1, 2]
    used = [c for c in client.get("/api/cards/list", params={"status": "used"}).json()["cards"]][0]
    assert client.delete(f"/api/cards/{used['id']}").status_code == 409
    stock = client.get("/api/cards/stock", params={"keyword": "QQ音乐"}).json()["stock"]
    assert {(s["sku"], s["unused"], s["used"]) for s in stock} == {("", 1, 0), ("季卡", 0, 2)}
    assert client.post("/api/cards/claim", json={"keyword": "QQ音乐", "order_id": "y", "quantity": 51}).status_code == 422
