"""同一订单不跨池补发 + 内网判定回归测试"""
import asyncio
import sqlite3

import pytest

from app.core.security import is_local_or_private
from app.db import database
from app.db.cards import claim_cards


@pytest.fixture()
def db(tmp_path, monkeypatch):
    path = str(tmp_path / "c.db")
    monkeypatch.setattr(database, "DB_PATH", path)
    database.init_db()
    c = sqlite3.connect(path)
    c.executemany("INSERT INTO card_keys(keyword, sku, card_content) VALUES(?,?,?)",
                  [("会员", "月卡", "M1"), ("会员", "月卡", "M2"), ("会员", "年卡", "Y1"), ("会员", "年卡", "Y2"),
                   ("超级会员", "月卡", "S1")])
    c.commit(); c.close()
    return path


def test_topup_refuses_other_sku(db):
    r = claim_cards(db, "会员", "o1", quantity=3, sku="月卡")      # 只够 2 张，partial
    assert r.delivered == 2
    with pytest.raises(ValueError):
        claim_cards(db, "会员", "o1", quantity=3, sku="年卡")       # 不能用年卡补第 3 件
    with pytest.raises(ValueError):
        claim_cards(db, "超级会员", "o1", quantity=3, sku="月卡")   # 也不能换关键词池
    c = sqlite3.connect(db)
    assert c.execute("SELECT COUNT(*) FROM card_keys WHERE sku='年卡' AND is_used=1").fetchone()[0] == 0
    c.close()


def test_bool_quantity_rejected(db):
    with pytest.raises(ValueError):
        claim_cards(db, "会员", "o2", quantity=True, sku="月卡")


@pytest.mark.parametrize("host,ok", [("127.0.0.1", True), ("::1", True), ("10.0.0.8", True),
                                     ("192.168.1.2", True), ("172.16.5.5", True), ("172.31.0.1", True),
                                     ("172.217.160.78", False), ("172.64.1.1", False), ("8.8.8.8", False)])
def test_private_range(host, ok):
    assert is_local_or_private(host) is ok
