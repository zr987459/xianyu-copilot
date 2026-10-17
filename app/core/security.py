"""
管理端鉴权：所有 /api/* 需要 Authorization: Bearer <ADMIN_TOKEN>。
- 未配置 ADMIN_TOKEN 时只放行本机直连请求；带 X-Forwarded-For 的（经反向代理/隧道转发）一律拒绝，
  防止 nginx / cloudflared 把 127.0.0.1 上的服务意外暴露到公网。
- 常量时间比较，防计时攻击。
"""
import hmac
import os

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

LOCAL_HOSTS = {"127.0.0.1", "::1"}


def admin_token() -> str:
    return os.getenv("ADMIN_TOKEN", "").strip()


def is_local_or_private(host: str) -> bool:
    if not host:
        return True
    return (
        host in LOCAL_HOSTS
        or host.startswith("127.")
        or host.startswith("10.")
        or host.startswith("192.168.")
        or host.startswith("172.")
    )


class AdminAuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if not request.url.path.startswith("/api/") or request.method == "OPTIONS":
            return await call_next(request)
        token = admin_token()
        if not token:
            direct_local = (request.client and is_local_or_private(request.client.host)
                            and "x-forwarded-for" not in request.headers)
            if direct_local:
                return await call_next(request)
            return JSONResponse({"detail": "ADMIN_TOKEN not configured"}, status_code=403)
        got = request.headers.get("authorization", "")
        supplied = got[7:].strip() if got.startswith("Bearer ") else ""
        if not supplied or not hmac.compare_digest(supplied.encode(), token.encode()):
            return JSONResponse({"detail": "unauthorized"}, status_code=401)
        return await call_next(request)
