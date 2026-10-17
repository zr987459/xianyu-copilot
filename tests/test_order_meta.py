"""订单件数解析：不能把金额 / 计数字段当成购买件数。"""
from app.services.delivery_manager import parse_order_meta


def test_qty_ignores_price_and_count_fields():
    # 9.9 元订单若把 amount 当件数，会一次发出 9 张卡
    assert parse_order_meta({"amount": "9", "count": 4, "skuDesc": "月卡"}) == ("月卡", 1)
    assert parse_order_meta({"order": {"amount": 1990, "buyAmount": "2"}}) == ("", 2)


def test_qty_key_priority_not_payload_order():
    # 嵌套里的 itemNum 先出现，也应以优先级更高的 buyAmount 为准
    assert parse_order_meta({"item": {"itemNum": 5}, "buyAmount": 2}) == ("", 2)
    assert parse_order_meta({"item": {"itemNum": "3"}}) == ("", 3)


def test_qty_rejects_garbage():
    assert parse_order_meta({"buyAmount": "abc", "quantity": "0"}) == ("", 1)
