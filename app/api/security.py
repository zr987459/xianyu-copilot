"""管理接口鉴权。

- ADMIN_TOKEN 必填（.env）；未配置时服务只允许 127.0.0.1 访问。
- 前端把 token 存在 localStorage，经 Authorization: Bearer 发送。
- 比较使用 hmac.compare_digest，防时序攻击。
"""
import hmac
import os

from fastapi import Request
from fastapi.responses import JSONResponse

PUBLIC_PATHS = {"/", "/healthz"}
LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}


async def admin_auth_middleware(request: Request, call_next):
    path = request.url.path
    if path in PUBLIC_PATHS or path.startswith("/static/") or request.method == "OPTIONS":
        return await call_next(request)
    token = os.getenv("ADMIN_TOKEN", "").strip()
    if not token:
        if request.client and request.client.host in LOCAL_HOSTS:
            return await call_next(request)
        return JSONResponse({"detail": "ADMIN_TOKEN 未配置，仅允许本机访问"}, status_code=403)
    got = request.headers.get("authorization", "").removeprefix("Bearer ").strip()
    if not got or not hmac.compare_digest(got, token):
        return JSONResponse({"detail": "unauthorized"}, status_code=401)
    return await call_next(request)


def mask_card(content: str) -> str:
    if not content:
        return ""
    if len(content) <= 6:
        return "*" * len(content)
    return content[:3] + "*" * min(8, len(content) - 5) + content[-2:]
