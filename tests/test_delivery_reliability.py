"""发货可靠性回归：发送失败不丢卡、重复事件不重发、确认失败可重试、跨批次去重。"""
import asyncio
import sqlite3
import threading

from tests.test_sku_multi_qty import _FakeDelivery, _manager, _rule, db, client  # noqa: F401  复用夹具

TITLE = "网易云音乐 黑胶VIP"


def _go(m, send, **kw):
    return asyncio.run(m.handle_paid_order(None, send, "u1", "c1", item_title=TITLE, **kw))


def test_send_failure_then_retry_delivers_same_cards(db):
    """绑卡后发消息失败：重跑必须把同一批卡发出去，且发齐前不确认发货（旧实现会发空消息并确认）。"""
    _rule(db); m = _manager(db); sent = []
    async def boom(*a): raise ConnectionError("ws closed")
    assert _go(m, boom, order_id="R1", sku_desc="30天", quantity=3) is False
    o = m.get_order("R1")
    assert o["status"] == "processing" and o["sent_count"] == 0 and m.delivery_service.calls == []
    async def ok(ws, chat_id, user_id, msg): sent.append(msg)
    assert _go(m, ok, order_id="R1") is True
    assert len(sent) == 1 and "① M0" in sent[0] and "③ M2" in sent[0] and "打开App兑换" in sent[0]
    assert m.delivery_service.calls == ["R1"]
    c = sqlite3.connect(db)
    assert c.execute("SELECT COUNT(*) FROM card_keys WHERE order_id='R1'").fetchone()[0] == 3


def test_concurrent_duplicate_events_send_once(db):
    """WSS 重推：两个协程同时处理同一订单，只能发一条消息、只确认一次。"""
    _rule(db); m = _manager(db); sent = []
    async def slow(ws, chat_id, user_id, msg):
        await asyncio.sleep(0.05); sent.append(msg)
    async def both():
        return await asyncio.gather(
            m.handle_paid_order(None, slow, "u1", "c1", "D1", TITLE, "30天", 2),
            m.handle_paid_order(None, slow, "u1", "c1", "D1", TITLE, "30天", 2))
    res = asyncio.run(both())
    assert sorted(res) == [False, True]
    assert len(sent) == 1 and m.delivery_service.calls == ["D1"]


def test_threads_same_order_single_delivery(db):
    """多线程（多 worker）同时处理同一订单。"""
    _rule(db); m = _manager(db); sent = []; lock = threading.Lock()
    async def send(ws, chat_id, user_id, msg):
        await asyncio.sleep(0.02)
        with lock: sent.append(msg)
    ts = [threading.Thread(target=_go, args=(m, send), kwargs=dict(order_id="T1", sku_desc="12个月", quantity=2))
          for _ in range(12)]
    [t.start() for t in ts]; [t.join() for t in ts]
    assert len(sent) == 1 and m.delivery_service.calls == ["T1"]
    assert m.get_order("T1")["status"] == "delivered"


def test_confirm_failure_is_retried_without_resending(db):
    _rule(db); m = _manager(db); sent = []
    class Flaky(_FakeDelivery):
        def __init__(self): super().__init__(); self.fail = True
        def confirm_dummy_shipping(self, order_id, trade_text=""):
            self.calls.append(order_id)
            return (False, "TIMEOUT") if self.fail else (True, "SUCCESS")
    m.delivery_service = Flaky()
    async def send(ws, chat_id, user_id, msg): sent.append(msg)
    assert _go(m, send, order_id="C1", sku_desc="30天", quantity=1) is False
    assert m.get_order("C1")["status"] == "confirm_failed" and len(sent) == 1
    assert [o["order_id"] for o in m.pending_orders()] == ["C1"]
    m.delivery_service.fail = False
    assert _go(m, send, order_id="C1") is True
    assert len(sent) == 1 and m.delivery_service.calls == ["C1", "C1"]
    assert m.get_order("C1")["status"] == "delivered"


def test_partial_resend_after_send_failure_keeps_numbering(db):
    """部分发放 → 补货 → 补发时网络失败 → 再重跑：编号连续，不重复发已送达的件。"""
    _rule(db); m = _manager(db); sent = []
    async def ok(ws, chat_id, user_id, msg): sent.append(msg)
    assert _go(m, ok, order_id="P1", sku_desc="年卡", quantity=7) is False      # 5/7
    c = sqlite3.connect(db)
    c.executemany("INSERT INTO card_keys(keyword, sku, card_content) VALUES ('网易云音乐','年卡',?)", [("Y8",), ("Y9",)])
    c.commit(); c.close()
    async def boom(*a): raise TimeoutError()
    assert _go(m, boom, order_id="P1") is False
    assert m.get_order("P1")["sent_count"] == 5
    assert _go(m, ok, order_id="P1") is True
    assert "⑥ Y8" in sent[1] and "⑦ Y9" in sent[1] and "① Y0" not in sent[1] and "年卡专属说明" not in sent[1]


def test_import_dedup_across_batches_and_waiting(client):
    r1 = client.post("/api/cards/import", json={"keyword": "K", "sku": "月卡", "raw_keys": "A\nB"}).json()
    r2 = client.post("/api/cards/import", json={"keyword": "K", "sku": "年卡", "raw_keys": "B\nC"}).json()
    assert (r1["count"], r2["count"], r2["duplicates"]) == (2, 1, 1)
    assert client.post("/api/cards/add", json={"keyword": "K", "card_content": "A"}).status_code == 409
