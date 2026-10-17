"""并发防超卖与状态机测试：python -m pytest tests/test_fulfillment.py -q"""
import multiprocessing as mp
import os
import sqlite3
import tempfile
import threading

from app.services import fulfillment as F

LEGACY_CARD_TABLE = """
CREATE TABLE card_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT, keyword TEXT, card_content TEXT,
    is_used INTEGER DEFAULT 0, order_id TEXT, used_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP);
"""


def make_db(n_cards=10, legacy_used=0):
    path = os.path.join(tempfile.mkdtemp(), "t.db")
    c = sqlite3.connect(path)
    c.executescript(LEGACY_CARD_TABLE)
    for i in range(legacy_used):
        c.execute("INSERT INTO card_keys(keyword, card_content, is_used, order_id) VALUES ('vip', ?, 1, ?)",
                  (f"OLD-{i}", f"old-order-{i}"))
    for i in range(n_cards):
        c.execute("INSERT INTO card_keys(keyword, card_content) VALUES ('vip', ?)", (f"KEY-{i:04d}",))
    c.commit(); c.close()
    conn = F.connect(path); F.migrate(conn); conn.close()
    return path


def _worker(path, ids, out):
    conn = F.connect(path)
    for oid in ids:
        F.on_paid(conn, oid, "vip", chat_id="c" + oid)
        F.on_paid(conn, oid, "vip", chat_id="c" + oid)  # 重复付款事件
        text = F.reserve(conn, oid, use_card_pool=True, fixed_text="网盘：https://example.invalid/x")
        out.append((oid, text))
    conn.close()


def test_migration_keeps_legacy_state():
    path = make_db(n_cards=3, legacy_used=2)
    conn = F.connect(path)
    rows = dict(conn.execute("SELECT status, count(*) FROM card_keys GROUP BY status").fetchall())
    assert rows == {"sent": 2, "available": 3}


def test_threads_no_oversell():
    path = make_db(n_cards=50)
    out, threads = [], []
    for t in range(8):
        ids = [f"T{t}-{i}" for i in range(10)]  # 80 单抢 50 张卡
        threads.append(threading.Thread(target=_worker, args=(path, ids, out)))
    [t.start() for t in threads]; [t.join() for t in threads]
    conn = F.connect(path)
    got = [t for _, t in out if t]
    assert len(got) == 50
    assert len(set(got)) == 50  # 没有两单拿到同一张卡
    assert conn.execute("SELECT count(*) FROM orders WHERE status='OUT_OF_STOCK'").fetchone()[0] == 30
    assert conn.execute("SELECT count(*) FROM card_keys WHERE status='available'").fetchone()[0] == 0
    assert conn.execute("SELECT count(DISTINCT order_id) FROM card_keys WHERE status='reserved'").fetchone()[0] == 50


def _proc(path, t):
    out = []
    _worker(path, [f"P{t}-{i}" for i in range(15)], out)
    return [x for _, x in out if x]


def test_processes_no_oversell():
    path = make_db(n_cards=40)
    with mp.get_context("spawn").Pool(4) as pool:  # 4 个进程 60 单抢 40 张
        res = pool.starmap(_proc, [(path, t) for t in range(4)])
    flat = [x for r in res for x in r]
    assert len(flat) == 40 and len(set(flat)) == 40


def test_retry_resends_same_card_and_never_returns_after_sent():
    path = make_db(n_cards=5)
    conn = F.connect(path)
    rule = F.Rule("vip", True, "", True)
    F.on_paid(conn, "o1", "vip", chat_id="c1")
    sent = []
    def flaky_send(chat, text):
        sent.append(text)
        return len(sent) >= 3  # 前两次失败
    assert F.process(conn, "o1", rule, flaky_send, lambda o: True) == F.RESERVED
    assert F.process(conn, "o1", rule, flaky_send, lambda o: True) == F.RESERVED
    assert F.process(conn, "o1", rule, flaky_send, lambda o: True) == F.DONE
    assert len(set(sent)) == 1  # 三次发的是同一张卡
    assert F.cancel(conn, "o1") is False  # 已交付不可取消
    assert conn.execute("SELECT count(*) FROM card_keys WHERE status='available'").fetchone()[0] == 4


def test_consign_failure_does_not_resend_message():
    path = make_db(n_cards=2)
    conn = F.connect(path)
    rule = F.Rule("vip", True, "", True)
    F.on_paid(conn, "o2", "vip", chat_id="c2")
    sends = []
    assert F.process(conn, "o2", rule, lambda c, t: sends.append(t) or True, lambda o: False) == F.SENT
    assert F.process(conn, "o2", rule, lambda c, t: sends.append(t) or True, lambda o: True) == F.DONE
    assert len(sends) == 1


def test_cancel_before_send_returns_card():
    path = make_db(n_cards=1)
    conn = F.connect(path)
    F.on_paid(conn, "o3", "vip")
    assert F.reserve(conn, "o3", True)
    assert F.cancel(conn, "o3")
    assert conn.execute("SELECT status, order_id FROM card_keys").fetchone()[:] == ("available", None)


def test_out_of_stock_then_restock():
    path = make_db(n_cards=0)
    conn = F.connect(path)
    rule = F.Rule("vip", True, "", False)
    F.on_paid(conn, "o4", "vip", chat_id="c4")
    assert F.process(conn, "o4", rule, lambda c, t: True, lambda o: True) == F.OUT_OF_STOCK
    conn.execute("INSERT INTO card_keys(keyword, card_content, status) VALUES ('vip', 'NEW-1', 'available')")
    assert F.requeue_out_of_stock(conn, "vip") == 1
    assert F.process(conn, "o4", rule, lambda c, t: True, lambda o: True) == F.SENT  # auto_confirm=False 停在 SENT


def test_illegal_transition_rejected():
    path = make_db(n_cards=1)
    conn = F.connect(path)
    F.on_paid(conn, "o5", "vip")
    try:
        F._transition(conn, "o5", F.PAID, F.DONE)
        assert False
    except F.IllegalTransition:
        pass
