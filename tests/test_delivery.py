"""卡密池与发货状态机测试：python -m pytest tests -q"""
import asyncio
import multiprocessing as mp
import os
import tempfile

from app.db.database import get_db, init_db
from app.services.card_pool import CardPool
from app.services.delivery_engine import DeliveryEngine, PaidOrder, CONFIRMED, OUT_OF_STOCK, MANUAL


def _setup(n_cards=5, use_pool=1, auto_confirm=1):
    path = os.path.join(tempfile.mkdtemp(), "t.db")
    init_db(path)
    c = get_db(path)
    c.execute("INSERT INTO auto_delivery_rules(keyword,delivery_content,use_card_pool,auto_confirm) "
              "VALUES('vip','网盘: https://example.invalid/x',?,?)", (use_pool, auto_confirm))
    for i in range(n_cards):
        c.execute("INSERT INTO card_keys(keyword,card_content) VALUES('vip',?)", (f"CARD-{i}",))
    return path, c


def _worker(path, start, q):
    pool = CardPool(get_db(path))
    got = []
    for i in range(start, start + 20):
        r = pool.reserve("vip", f"order-{i}")
        if r:
            got.append(r["id"])
    q.put(got)


def test_no_oversell_across_processes():
    path, _ = _setup(n_cards=30)
    q = mp.Queue()
    ps = [mp.Process(target=_worker, args=(path, k * 20, q)) for k in range(4)]  # 80 单抢 30 张
    [p.start() for p in ps]
    ids = sum((q.get() for _ in ps), [])
    [p.join() for p in ps]
    assert len(ids) == 30 and len(set(ids)) == 30


def test_reserve_idempotent():
    _, c = _setup()
    p = CardPool(c)
    assert p.reserve("vip", "o1")["id"] == p.reserve("vip", "o1")["id"]
    assert p.stock("vip")["reserved"] == 1


def _engine(c, send_fail=0, confirm_ok=True):
    sent, state = [], {"fail": send_fail}

    async def send(chat, text):
        if state["fail"] > 0:
            state["fail"] -= 1
            raise ConnectionError()
        sent.append(text)

    async def confirm(oid):
        return confirm_ok, "SUCCESS" if confirm_ok else "FAIL"
    return DeliveryEngine(c, send, confirm), sent


def _state(c, oid):
    return c.execute("SELECT state FROM delivery_orders WHERE order_id=?", (oid,)).fetchone()["state"]


def test_happy_path_and_duplicate_push():
    _, c = _setup()
    eng, sent = _engine(c)
    o = PaidOrder("o1", "b", "chat", "item", "vip")

    async def run():
        await asyncio.gather(eng.on_paid(o), eng.on_paid(o), eng.on_paid(o))
    asyncio.run(run())
    assert _state(c, "o1") == CONFIRMED
    assert len(sent) == 1 and "CARD-0" in sent[0] and "网盘" in sent[0]
    assert CardPool(c).stock("vip")["delivered"] == 1


def test_send_retry_uses_same_card():
    _, c = _setup()
    eng, sent = _engine(c, send_fail=1)
    asyncio.run(eng.on_paid(PaidOrder("o1", "b", "chat", "item", "vip")))
    c.execute("UPDATE delivery_orders SET updated_at=datetime('now','-1 minute')")
    asyncio.run(eng.retry_pending())
    assert _state(c, "o1") == CONFIRMED and "CARD-0" in sent[0]
    assert CardPool(c).stock("vip") == {"available": 4, "reserved": 0, "delivered": 1, "total": 5}


def test_out_of_stock_then_restock():
    _, c = _setup(n_cards=0)
    eng, sent = _engine(c)
    asyncio.run(eng.on_paid(PaidOrder("o1", "b", "chat", "item", "vip")))
    assert _state(c, "o1") == OUT_OF_STOCK and not sent
    c.execute("INSERT INTO card_keys(keyword,card_content) VALUES('vip','NEW')")
    c.execute("UPDATE delivery_orders SET updated_at=datetime('now','-1 minute')")
    asyncio.run(eng.retry_pending())
    assert _state(c, "o1") == CONFIRMED and "NEW" in sent[0]


def test_send_keeps_failing_goes_manual_card_kept():
    _, c = _setup()
    eng, _ = _engine(c, send_fail=99)
    asyncio.run(eng.on_paid(PaidOrder("o1", "b", "chat", "item", "vip")))
    for _ in range(5):
        c.execute("UPDATE delivery_orders SET updated_at=datetime('now','-1 minute')")
        asyncio.run(eng.retry_pending())
    assert _state(c, "o1") == MANUAL
    assert CardPool(c).stock("vip")["reserved"] == 1   # 卡没有被别人拿走，也没回池


def test_migration_from_v1_keeps_data():
    path = os.path.join(tempfile.mkdtemp(), "old.db")
    import sqlite3
    o = sqlite3.connect(path)
    o.execute("CREATE TABLE card_keys (id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT, card_content TEXT,"
              " is_used INTEGER DEFAULT 0, order_id TEXT, used_at DATETIME, created_at DATETIME)")
    o.execute("INSERT INTO card_keys(keyword,card_content,is_used) VALUES('vip','A',1),('vip','B',0)")
    o.commit(); o.close()
    init_db(path); init_db(path)   # 可重复执行
    s = CardPool(get_db(path)).stock("vip")
    assert s == {"available": 1, "reserved": 0, "delivered": 1, "total": 2}
