import asyncio, os, tempfile, threading
import app.db.database as d


def setup_db(cards=1):
    d.DB_PATH = os.path.join(tempfile.mkdtemp(), "t.db"); d.init_db()
    c = d.get_db()
    c.execute("INSERT INTO auto_delivery_rules(keyword,delivery_content,use_card_pool) VALUES('会员','网盘:x',1)")
    for i in range(cards):
        c.execute("INSERT INTO card_keys(keyword,card_content) VALUES('会员',?)", (f"K{i}",))
    return c


def test_no_oversell_under_threads():
    from app.services import delivery_engine as e
    c = setup_db(cards=5)
    def w(i):
        try: e.build_delivery(f"o{i % 8}", "u", "c", "年度会员")
        except e.OutOfStock: pass
    ts = [threading.Thread(target=w, args=(i,)) for i in range(40)]
    [t.start() for t in ts]; [t.join() for t in ts]
    assert c.execute("select count(*) from card_keys where is_used=1").fetchone()[0] == 5
    assert c.execute("select count(distinct order_id) from card_keys where is_used=1").fetchone()[0] == 5


def test_same_order_sends_once_and_refill_retry():
    from app.services import delivery_engine as e
    c = setup_db(cards=1)
    sent = []
    async def send(ch, u, t): sent.append(t)
    async def confirm(o): return True, "SUCCESS"
    async def run():
        await asyncio.gather(*[e.deliver_order("o1", "u", "c", "年度会员", send, confirm) for _ in range(5)])
        await e.deliver_order("o2", "u", "c", "年度会员", send, confirm)       # 缺货
        assert e._status("o2") == "out_of_stock"
        c.execute("INSERT INTO card_keys(keyword,card_content) VALUES('会员','K9')")
        await e.deliver_order("o2", "u", "c", "年度会员", send, confirm)       # 补卡重试
    asyncio.run(run())
    assert len(sent) == 2
    assert e._status("o1") == e._status("o2") == "confirmed"
    assert e.mark_status("o1", "sent") is False                                 # 非法回退被拒
