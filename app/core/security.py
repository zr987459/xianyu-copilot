"""
管理端鉴权：所有 /api/* 需要 Authorization: Bearer <ADMIN_TOKEN>。
- ADMIN_TOKEN 只放在 .env（已在 .gitignore），未配置时服务拒绝绑定到非本机地址。
- 常量时间比较，防计时攻击。
"""
import hmac
import os

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

PUBLIC_PATHS = {"/", "/manifest.webmanifest", "/sw.js", "/healthz"}


def admin_token() -> str:
    return os.getenv("ADMIN_TOKEN", "").strip()


class AdminAuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        if path in PUBLIC_PATHS or path.startswith("/static/") or request.method == "OPTIONS":
            return await call_next(request)
        token = admin_token()
        if not token:
            # 未配置口令：只允许本机访问
            if request.client and request.client.host in ("127.0.0.1", "::1"):
                return await call_next(request)
            return JSONResponse({"detail": "ADMIN_TOKEN not configured"}, status_code=403)
        got = request.headers.get("authorization", "")
        supplied = got[7:] if got.startswith("Bearer ") else ""
        if not supplied and path == "/api/events":
            supplied = request.query_params.get("token", "")  # EventSource 不能带请求头
        if not supplied or not hmac.compare_digest(supplied.encode(), token.encode()):
            return JSONResponse({"detail": "unauthorized"}, status_code=401)
        return await call_next(request)


# 只允许修改这几份 prompt，杜绝 name="../../xxx" 路径穿越写文件
ALLOWED_PROMPTS = {"classify_prompt", "price_prompt", "tech_prompt", "default_prompt"}
