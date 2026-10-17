"""卡密出库与鉴权回归测试：python3 -m pytest tests -q"""
import multiprocessing as mp
import os
import sqlite3
import threading

import pytest
from fastapi.testclient import TestClient

from app.db import database
from app.db.cards import claim_card


@pytest.fixture()
def db(tmp_path, monkeypatch):
    path = str(tmp_path / "x.db")
    monkeypatch.setattr(database, "DB_PATH", path)
    database.init_db()
    c = sqlite3.connect(path)
    c.execute("PRAGMA journal_mode=WAL;")
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_card_order ON card_keys(order_id) WHERE order_id IS NOT NULL;")
    c.executemany("INSERT INTO card_keys(keyword, card_content) VALUES('vip', ?)", [(f"K{i}",) for i in range(50)])
    c.commit(); c.close()
    return path


def test_threads_no_oversell(db):
    out, lock = [], threading.Lock()
    def w(i):
        r = claim_card(db, "vip", f"o{i}")
        with lock: out.append(r["card_content"] if r else None)
    ts = [threading.Thread(target=w, args=(i,)) for i in range(80)]
    [t.start() for t in ts]; [t.join() for t in ts]
    got = [x for x in out if x]
    assert len(got) == 50 and len(set(got)) == 50 and out.count(None) == 30


def _proc(db, start, q):
    q.put([(r or {}).get("card_content") for r in (claim_card(db, "vip", f"p{start+i}") for i in range(15))])


def test_processes_no_oversell(db):
    q = mp.get_context("spawn").Queue()
    ps = [mp.get_context("spawn").Process(target=_proc, args=(db, k * 100, q)) for k in range(4)]
    [p.start() for p in ps]
    res = sum((q.get(timeout=60) for _ in ps), []); [p.join() for p in ps]
    got = [x for x in res if x]
    assert len(got) == 50 and len(set(got)) == 50


def test_idempotent_and_empty_order(db):
    a = claim_card(db, "vip", "o1"); b = claim_card(db, "vip", "o1")
    assert a["card_content"] == b["card_content"]
    with pytest.raises(ValueError):
        claim_card(db, "vip", "")


@pytest.fixture()
def client(db, monkeypatch):
    monkeypatch.setenv("ADMIN_TOKEN", "s3cret-token")
    import app.api.routes as R
    with TestClient(R.app) as c:
        yield c


def test_auth_and_endpoints(client):
    assert client.get("/api/cards/list").status_code == 401
    assert client.get("/api/stats", headers={"Authorization": "Bearer nope"}).status_code == 401
    h = {"Authorization": "Bearer s3cret-token"}
    assert client.get("/api/stats", headers=h).status_code == 200
    assert client.post("/api/cards/claim", json={"keyword": "vip", "order_id": " "}, headers=h).status_code == 400
    assert client.post("/api/prompts", json={"../../x": "a"}, headers=h).status_code == 400
    r = client.post("/api/cards/import", json={"keyword": "dup", "raw_keys": "A\nA\n\nB"}, headers=h)
    assert r.json()["count"] == 2


def test_no_token_rejects_proxied(db, monkeypatch):
    monkeypatch.delenv("ADMIN_TOKEN", raising=False)
    import app.api.routes as R
    with TestClient(R.app, client=("127.0.0.1", 5000)) as c:
        assert c.get("/api/stats").status_code == 200
        assert c.get("/api/stats", headers={"X-Forwarded-For": "1.2.3.4"}).status_code == 403
