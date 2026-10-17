"""发货计划：规则 + 订单规格 → 从哪个套餐出库、发什么说明；以及多卡密消息排版。纯函数，便于单测。"""
import re
from dataclasses import dataclass
from typing import List, Optional, Sequence

from app.db.cards import normalize_sku

WILDCARD = "*"
MAX_MSG_CHARS = 900  # 单条私信字数上限（保守取值，闲鱼未公开上限；超出按卡密边界拆成多条）
_CIRCLED = [chr(c) for c in range(0x2460, 0x2474)]  # ①…⑳


@dataclass
class DeliveryPlan:
    keyword: str            # 卡池主关键词（= 规则 keyword）
    card_sku: str           # 卡池套餐，'' 为默认套餐
    sku_label: str          # 给买家看的规格文字
    fixed_content: str
    use_card_pool: bool
    auto_confirm: bool
    variant_id: Optional[int] = None


def _fold(s: str) -> str:
    return normalize_sku(s).casefold()


def _segments(sku_desc: str) -> List[str]:
    """「套餐:年卡;版本:专业版」→ ['年卡', '专业版']，取每段冒号后的值。"""
    parts = re.split(r"[;；,，/|\s]+", sku_desc or "")
    out = []
    for p in parts:
        v = re.split(r"[:：]", p, maxsplit=1)[-1]
        v = _fold(v)
        if v:
            out.append(v)
    return out


def match_variant(variants: Sequence[dict], sku_desc: str) -> Optional[dict]:
    """在规则的套餐里选一个：
    1. 规格某一段与 sku_pattern 完全相等（「半年卡」不会被「年卡」抢走）
    2. 否则取被规格文字包含的最长 sku_pattern
    3. 都没有时用 * 兜底套餐（若配置了）；仍没有返回 None → 转人工，绝不发错档位
    同一层命中多个互不包含的套餐视为歧义，也返回 None
    """
    live = [v for v in variants if v.get("enabled", 1)]
    segs = _segments(sku_desc)
    full = _fold(sku_desc)
    exact, contains, fallback = [], [], None
    for v in live:
        pat = _fold(v["sku_pattern"])
        if pat == WILDCARD:
            fallback = v
        elif not pat:
            continue
        elif pat in segs:
            exact.append(v)
        elif pat in full:
            contains.append(v)
    for group in (exact, contains):
        if not group:
            continue
        # 嵌套的只留最长（「半年卡」含「年卡」→ 取「半年卡」）；互不包含的多个命中（「专业版」+「年卡」）
        # 说明规格同时落在两个套餐上，自动发货无法判断，返回 None 转人工
        pats = [_fold(v["sku_pattern"]) for v in group]
        top = [v for v, p in zip(group, pats) if not any(p != q and p in q for q in pats)]
        return top[0] if len({_fold(v["sku_pattern"]) for v in top}) == 1 else None
    return fallback


def resolve_plan(rule: dict, variants: Sequence[dict], sku_desc: str) -> Optional[DeliveryPlan]:
    """规则没配套餐 → 老行为（默认套餐卡池 + 规则固定内容）；配了套餐但没匹配上 → None（转人工）。"""
    base_fixed = (rule.get("delivery_content") or "").strip()
    base_pool = bool(rule.get("use_card_pool", 0))
    auto_confirm = bool(rule.get("auto_confirm", 1))
    label = (sku_desc or "").strip()
    live = [v for v in variants if v.get("enabled", 1)]
    if not live:
        return DeliveryPlan(rule["keyword"], "", label, base_fixed, base_pool, auto_confirm)
    v = match_variant(live, sku_desc)
    if v is None:
        return None
    pat = v["sku_pattern"].strip()
    card_sku = normalize_sku(v.get("card_sku") or ("" if pat == WILDCARD else pat))
    fixed = (v.get("delivery_content") or "").strip() or base_fixed
    pool = base_pool if v.get("use_card_pool") is None else bool(v["use_card_pool"])
    if not label and pat != WILDCARD:
        label = pat
    return DeliveryPlan(rule["keyword"], card_sku, label, fixed, pool, auto_confirm, v.get("id"))


def _num(i: int) -> str:
    return _CIRCLED[i - 1] if i <= len(_CIRCLED) else f"{i}."


def _card_line(i: int, content: str) -> str:
    first, *rest = (content or "").strip().splitlines() or [""]
    pad = " " * (len(_num(i)) + 1)
    return "\n".join([f"{_num(i)} {first}"] + [pad + r for r in rest])


def format_delivery_messages(plan: DeliveryPlan, cards: Sequence[dict], quantity: int,
                             max_chars: int = MAX_MSG_CHARS) -> List[str]:
    """排版发货私信。卡密按序号 ①②③ 列出，超长按卡密边界拆条，单张卡密不会被拆断。"""
    head = ["【自动发货】亲，感谢您的购买！"]
    spec = plan.sku_label
    if spec and quantity > 1:
        head.append(f"规格：{spec} × {quantity}")
    elif spec:
        head.append(f"规格：{spec}")
    elif quantity > 1:
        head.append(f"数量：× {quantity}")
    blocks = ["\n".join(head)]
    if plan.fixed_content:
        blocks.append(f"【下载与使用说明】\n{plan.fixed_content}")
    tail = "如有任何问题随时发消息联系，祝您生活愉快！"

    if not cards:
        return ["\n\n".join(blocks + [tail])] if plan.fixed_content else []

    n = len(cards)
    title = "【您的专属卡密】" if n == 1 else f"【您的专属卡密】共 {n} 张，每张仅限使用一次"
    lines = [_card_line(i, c["card_content"]) for i, c in enumerate(cards, 1)]

    one = "\n\n".join(blocks + [title + "\n" + "\n".join(lines), tail])
    if len(one) <= max_chars:
        return [one]

    # 拆条：第 1 条放抬头 + 说明，后面每条装尽量多的卡密
    msgs, cur = ["\n\n".join(blocks)], []
    budget = max_chars - 24
    for ln in lines:
        if cur and len("\n".join(cur + [ln])) > budget:
            msgs.append("\n".join(cur)); cur = []
        cur.append(ln)
    if cur:
        msgs.append("\n".join(cur))
    total = len(msgs) - 1
    out = [msgs[0]]
    for k, body in enumerate(msgs[1:], 1):
        t = title if k == 1 else "【专属卡密·续】"
        out.append(f"{t}（{k}/{total}）\n{body}")
    out[-1] += "\n\n" + tail
    return out


def format_shortage_notice(plan: DeliveryPlan, quantity: int, available: int) -> str:
    spec = f"{plan.sku_label} × {quantity}" if plan.sku_label else f"× {quantity}"
    return ("【自动发货】亲，感谢您的购买！\n"
            f"您拍下的 {spec} 正在火速补货中（当前可发 {available} 张），"
            "为保证同一订单一次发齐，补齐后会自动一次性发给您，店主已收到通知，请稍候～")
