"""进程内事件总线：业务侧 publish，移动端经 /ws/events 订阅。
每条事件带单调递增 seq，客户端断线重连后用 seq 补齐缺口。"""
import asyncio
import itertools
from collections import deque
from typing import Any

_seq = itertools.count(1)
_backlog: deque = deque(maxlen=500)          # 最近 500 条，用于断线补发
_subscribers: set[asyncio.Queue] = set()


def publish(event_type: str, data: Any) -> dict:
    evt = {"seq": next(_seq), "type": event_type, "data": data}
    _backlog.append(evt)
    for q in list(_subscribers):
        if q.full():                          # 慢客户端：丢弃并让其走全量刷新
            _subscribers.discard(q)
            q.put_nowait({"type": "resync"})
        else:
            q.put_nowait(evt)
    return evt


def replay_since(seq: int) -> list | None:
    """返回 seq 之后的事件；缺口超出 backlog 时返回 None，客户端需全量刷新"""
    if _backlog and seq < _backlog[0]["seq"] - 1:
        return None
    return [e for e in _backlog if e["seq"] > seq]


def subscribe() -> asyncio.Queue:
    q: asyncio.Queue = asyncio.Queue(maxsize=200)
    _subscribers.add(q)
    return q


def unsubscribe(q: asyncio.Queue):
    _subscribers.discard(q)
