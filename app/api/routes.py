import os
import hmac
import base64
import hashlib
import secrets
import sqlite3
from typing import Optional, Literal

from fastapi import FastAPI, Request, HTTPException, Query
from fastapi.responses import JSONResponse, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from app.db.database import db, write_tx, init_db
from app.db.card_pool import OutOfStock, claim_card_for_order, mark_delivery  # noqa: F401  (mark_delivery 供机器人复用)
from app.services.publisher_service import XianyuPublisherService
from app.services.XianyuApis import XianyuApis

app = FastAPI(title="Xianyu Copilot API", version="1.1.0")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PROMPTS_DIR = os.path.join(BASE_DIR, "prompts")
ENV_PATH = os.path.join(BASE_DIR, ".env")

# ======================================================================
# 安全基线
# ======================================================================

# 1) CORS：原来是 allow_origins=["*"] + allow_credentials=True，任何网页都能驱动你的后台。
#    现在默认同源；确需跨域时用 CORS_ORIGINS=https://a.com,https://b.com 显式放行。
_cors = [o.strip() for o in os.getenv("CORS_ORIGINS", "").split(",") if o.strip()]
if _cors:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=_cors,
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Authorization", "Content-Type", "If-None-Match"],
        expose_headers=["ETag"],
    )

# 2) 鉴权：所有 /api/* 需要 Bearer Token。未配置时启动生成一次性随机 Token 并打印到控制台。
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN") or secrets.token_urlsafe(24)
if not os.getenv("ADMIN_TOKEN"):
    print(f"[SECURITY] 未设置 ADMIN_TOKEN，本次启动临时 Token：{ADMIN_TOKEN}")

BARGAIN_MAX_ROUNDS = int(os.getenv("BARGAIN_MAX_ROUNDS", "3"))
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_IMPORT_LINES = 5000


@app.middleware("http")
async def security_middleware(request: Request, call_next):
    if request.url.path.startswith("/api/") and request.method != "OPTIONS":
        auth = request.headers.get("authorization", "")
        token = auth[7:] if auth.lower().startswith("bearer ") else ""
        if not token or not hmac.compare_digest(token, ADMIN_TOKEN):
            return JSONResponse({"detail": "unauthorized"}, status_code=401)
    resp = await call_next(request)
    # 纵深防御：即便前端将来有漏网的注入点，CSP 也能挡住外部脚本与内联事件
    resp.headers.setdefault("X-Content-Type-Options", "nosniff")
    resp.headers.setdefault("Referrer-Policy", "no-referrer")
    resp.headers.setdefault("X-Frame-Options", "DENY")
    if request.url.path.startswith("/api/"):
        resp.headers.setdefault("Cache-Control", "no-store")
    return resp


def etag_json(request: Request, payload: dict) -> Response:
    """弱网优化：内容没变就回 304，移动端静默轮询几乎零流量。"""
    import json
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), default=str)
    tag = 'W/"' + hashlib.sha1(body.encode()).hexdigest()[:16] + '"'
    if request.headers.get("if-none-match") == tag:
        return Response(status_code=304, headers={"ETag": tag})
    return Response(body, media_type="application/json", headers={"ETag": tag})


def mask_card(s: str) -> str:
    if not s:
        return ""
    if len(s) <= 6:
        return s[0] + "•" * (len(s) - 1)
    return s[:3] + "•" * min(8, len(s) - 6) + s[-3:]


def get_publisher_instance():
    apis = XianyuApis()
    cookie_str = os.getenv("COOKIES_STR", "")
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH, "r", encoding="utf-8") as fp:
            for line in fp:
                if line.startswith("COOKIES_STR="):
                    cookie_str = line.split("=", 1)[1].strip()
    for item in cookie_str.split(";"):
        if "=" in item:
            k, v = item.strip().split("=", 1)
            apis.session.cookies.set(k, v)
    return XianyuPublisherService(apis.session, cookie_str)


@app.on_event("startup")
def startup_event():
    init_db()


# ======================================================================
# 概览
# ======================================================================

@app.get("/api/stats")
def get_stats(request: Request):
    with db() as conn:
        c = conn.cursor()
        one = lambda q: c.execute(q).fetchone()[0] or 0
        payload = {
            "total_msgs": one("SELECT count(*) FROM messages"),
            "total_users": one("SELECT count(DISTINCT user_id) FROM messages"),
            "total_items": one("SELECT count(*) FROM published_items"),
            "total_delivered": one("SELECT count(*) FROM delivery_logs WHERE status='sent'"),
            "pending_delivery": one("SELECT count(*) FROM delivery_logs WHERE status IN ('claimed','failed')"),
            "available_cards": one("SELECT count(*) FROM card_keys WHERE is_used=0"),
            "used_cards": one("SELECT count(*) FROM card_keys WHERE is_used=1"),
            "latest_msg_id": one("SELECT max(id) FROM messages"),
        }
    return etag_json(request, payload)


# ======================================================================
# 消息
# ======================================================================

@app.get("/api/chats")
def get_chats(request: Request):
    with db() as conn:
        rows = conn.execute("""
            WITH last AS (
                SELECT user_id, item_id, max(id) AS last_id, count(*) AS msg_count, max(timestamp) AS last_time
                FROM messages GROUP BY user_id, item_id
            )
            SELECT l.user_id, l.item_id, l.msg_count, l.last_time, m.chat_id, m.role AS last_role,
                   m.content AS last_content, COALESCE(b.count, 0) AS bargain_count,
                   p.title AS item_title, p.price AS item_price
            FROM last l
            JOIN messages m ON m.id = l.last_id
            LEFT JOIN chat_bargain_counts b ON b.chat_id = m.chat_id
            LEFT JOIN published_items p ON p.item_id = l.item_id
            ORDER BY l.last_id DESC LIMIT 50
        """).fetchall()   # 原实现是 N+1 查询，50 个会话要打 101 次 SQL
    return etag_json(request, {"chats": [dict(r) for r in rows]})


@app.get("/api/chat/messages")
def get_chat_messages(request: Request, user_id: str = Query(..., max_length=64),
                      item_id: str = Query(..., max_length=64), after_id: int = 0):
    """after_id>0 时只返回增量，抽屉打开期间静默轮询用。"""
    with db() as conn:
        rows = conn.execute("""
            SELECT id, role, content, timestamp FROM messages
            WHERE user_id=? AND item_id=? AND id>?
            ORDER BY id ASC LIMIT 500
        """, (user_id, item_id, after_id)).fetchall()
    return etag_json(request, {"messages": [dict(r) for r in rows]})


@app.get("/api/chat/context")
def get_chat_context(request: Request, user_id: str = Query(..., max_length=64),
                     item_id: str = Query(..., max_length=64)):
    """【在谈宝贝】悬浮卡 + 议价状态条的数据源。"""
    with db() as conn:
        item = conn.execute(
            "SELECT item_id, title, price, url FROM published_items WHERE item_id=? ORDER BY id DESC LIMIT 1",
            (item_id,),
        ).fetchone()
        chat = conn.execute(
            "SELECT chat_id FROM messages WHERE user_id=? AND item_id=? AND chat_id IS NOT NULL ORDER BY id DESC LIMIT 1",
            (user_id, item_id),
        ).fetchone()
        rounds = 0
        if chat:
            b = conn.execute("SELECT count FROM chat_bargain_counts WHERE chat_id=?", (chat["chat_id"],)).fetchone()
            rounds = b["count"] if b else 0
        delivered = conn.execute(
            "SELECT status, order_id FROM delivery_logs WHERE user_id=? ORDER BY id DESC LIMIT 1", (user_id,)
        ).fetchone()

    if delivered and delivered["status"] == "sent":
        stage = "delivered"
    elif rounds >= BARGAIN_MAX_ROUNDS:
        stage = "floor"          # 已到底价，AI 不再让步
    elif rounds > 0:
        stage = "bargaining"
    else:
        stage = "inquiry"

    return etag_json(request, {
        "item": dict(item) if item else {"item_id": item_id, "title": None, "price": None, "url": None},
        "bargain": {"rounds": rounds, "max_rounds": BARGAIN_MAX_ROUNDS, "stage": stage},
        "order": dict(delivered) if delivered else None,
    })


# ======================================================================
# 发货规则 & 卡密池
# ======================================================================

class RuleCreate(BaseModel):
    keyword: str = Field(..., min_length=1, max_length=64)
    delivery_content: Optional[str] = Field("", max_length=4000)
    use_card_pool: bool = False
    auto_confirm: bool = True


@app.get("/api/delivery/rules")
def get_delivery_rules(request: Request):
    with db() as conn:
        rows = conn.execute("""
            SELECT r.*,
                   COUNT(k.id) AS card_total,
                   COALESCE(SUM(k.is_used=0), 0) AS card_stock,
                   COALESCE(SUM(k.is_used=1), 0) AS card_used
            FROM auto_delivery_rules r
            LEFT JOIN card_keys k ON k.keyword = r.keyword
            GROUP BY r.id ORDER BY r.id DESC
        """).fetchall()
    return etag_json(request, {"rules": [dict(r) for r in rows]})


@app.post("/api/delivery/rules")
def create_rule(data: RuleCreate):
    if not data.use_card_pool and not (data.delivery_content or "").strip():
        raise HTTPException(400, "至少选择一种发货方式")
    with db() as conn:
        # 用 UPSERT 代替 INSERT OR REPLACE：后者会先删后插，id 变化且丢失 created_at
        conn.execute("""
            INSERT INTO auto_delivery_rules (keyword, delivery_content, use_card_pool, auto_confirm, enabled)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT(keyword) DO UPDATE SET
                delivery_content=excluded.delivery_content,
                use_card_pool=excluded.use_card_pool,
                auto_confirm=excluded.auto_confirm,
                enabled=1
        """, (data.keyword.strip(), (data.delivery_content or "").strip(),
              int(data.use_card_pool), int(data.auto_confirm)))
    return {"success": True}


@app.delete("/api/delivery/rules/{rule_id}")
def delete_rule(rule_id: int):
    with db() as conn:
        conn.execute("DELETE FROM auto_delivery_rules WHERE id=?", (rule_id,))
    return {"success": True}


class SingleCardCreate(BaseModel):
    keyword: str = Field(..., min_length=1, max_length=64)
    card_content: str = Field(..., min_length=1, max_length=2000)


@app.post("/api/cards/add")
def add_single_card(data: SingleCardCreate):
    with db() as conn:
        try:
            conn.execute("INSERT INTO card_keys (keyword, card_content, is_used) VALUES (?, ?, 0)",
                         (data.keyword.strip(), data.card_content.strip()))
        except sqlite3.IntegrityError:
            raise HTTPException(409, "该卡密已存在")
    return {"success": True}


class BatchCardImport(BaseModel):
    keyword: str = Field(..., min_length=1, max_length=64)
    raw_keys: str = Field(..., max_length=1_000_000)


@app.post("/api/cards/import")
def import_cards(data: BatchCardImport):
    lines = list(dict.fromkeys(l.strip() for l in data.raw_keys.splitlines() if l.strip()))
    if len(lines) > MAX_IMPORT_LINES:
        raise HTTPException(400, f"单次最多导入 {MAX_IMPORT_LINES} 条")
    kw = data.keyword.strip()
    with db() as conn, write_tx(conn):     # 整批一个事务：要么全进，要么全不进
        before = conn.total_changes
        conn.executemany(
            "INSERT OR IGNORE INTO card_keys (keyword, card_content, is_used) VALUES (?, ?, 0)",
            [(kw, x) for x in lines],
        )
        inserted = conn.total_changes - before
    return {"success": True, "count": inserted, "skipped": len(lines) - inserted}


@app.get("/api/cards/list")
def get_card_list(request: Request, keyword: Optional[str] = "",
                  status: Literal["all", "unused", "used"] = "all"):
    query, params = "SELECT id, keyword, card_content, is_used, order_id, used_at, created_at FROM card_keys WHERE 1=1", []
    if keyword:
        query += " AND keyword=?"
        params.append(keyword)
    if status == "unused":
        query += " AND is_used=0"
    elif status == "used":
        query += " AND is_used=1"
    query += " ORDER BY id DESC LIMIT 200"
    with db() as conn:
        rows = conn.execute(query, params).fetchall()
    cards = []
    for r in rows:
        d = dict(r)
        d["card_masked"] = mask_card(d.pop("card_content"))   # 列表默认脱敏，明文需单独请求
        cards.append(d)
    return etag_json(request, {"cards": cards})


@app.get("/api/cards/{card_id}/reveal")
def reveal_card(card_id: int):
    with db() as conn:
        r = conn.execute("SELECT card_content FROM card_keys WHERE id=?", (card_id,)).fetchone()
    if not r:
        raise HTTPException(404)
    return {"card_content": r["card_content"]}


@app.delete("/api/cards/{card_id}")
def delete_card(card_id: int):
    with db() as conn:
        cur = conn.execute("DELETE FROM card_keys WHERE id=? AND is_used=0", (card_id,))
    if cur.rowcount == 0:
        raise HTTPException(409, "已发出的卡密不能删除（保留对账凭证）")
    return {"success": True}


@app.get("/api/delivery/logs")
def get_delivery_logs(request: Request, status: Literal["all", "pending", "sent"] = "all"):
    q = "SELECT * FROM delivery_logs"
    if status == "pending":
        q += " WHERE status IN ('claimed','failed')"
    elif status == "sent":
        q += " WHERE status='sent'"
    q += " ORDER BY id DESC LIMIT 100"
    with db() as conn:
        rows = conn.execute(q).fetchall()
    return etag_json(request, {"logs": [dict(r) for r in rows]})


class ClaimRequest(BaseModel):
    order_id: str = Field(..., min_length=1, max_length=64)
    keyword: str = Field(..., min_length=1, max_length=64)
    user_id: Optional[str] = Field("", max_length=64)
    chat_id: Optional[str] = Field("", max_length=64)


@app.post("/api/delivery/claim")
def claim_api(data: ClaimRequest):
    """手动发货 / 补发入口。重复点击同一单只会拿到同一张卡。"""
    with db() as conn:
        rule = conn.execute("SELECT delivery_content FROM auto_delivery_rules WHERE keyword=?",
                            (data.keyword,)).fetchone()
    try:
        res = claim_card_for_order(data.order_id, data.keyword, data.user_id, data.chat_id,
                                   rule["delivery_content"] if rule else "")
    except OutOfStock:
        raise HTTPException(409, f"「{data.keyword}」卡密库存不足")
    res.pop("content", None)       # 明文不回传给前端，发送由后端机器人完成
    return {"success": True, **res}


# ======================================================================
# 商品上架
# ======================================================================

class PublishItemRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=60)
    description: str = Field(..., min_length=1, max_length=5000)
    price: float = Field(..., gt=0, lt=1_000_000)
    original_price: Optional[float] = Field(None, gt=0, lt=1_000_000)
    image_base64: Optional[str] = Field("", max_length=8 * 1024 * 1024)


@app.post("/api/publish/item")
def publish_item_api(data: PublishItemRequest):
    images = []
    if data.image_base64:
        raw_b64 = data.image_base64.split(",", 1)[1] if "," in data.image_base64 else data.image_base64
        try:
            img = base64.b64decode(raw_b64, validate=True)
        except Exception:
            raise HTTPException(400, "图片编码无效")
        if len(img) > MAX_IMAGE_BYTES:
            raise HTTPException(413, "图片不能超过 5MB")
        if not (img[:3] == b"\xff\xd8\xff" or img[:8] == b"\x89PNG\r\n\x1a\n" or img[8:12] == b"WEBP"):
            raise HTTPException(400, "仅支持 JPG / PNG / WEBP")
        images.append(img)

    publisher = get_publisher_instance()
    success, res = publisher.publish_item(
        title=data.title, description=data.description, price=data.price,
        original_price=data.original_price or data.price, image_urls_or_bytes=images,
    )
    if success:
        url = res.get("url") or ""
        if not url.startswith("https://"):
            url = ""                       # 只存 https 链接，前端也会再校验一次
        with db() as conn:
            conn.execute("INSERT INTO published_items (item_id, title, price, url) VALUES (?, ?, ?, ?)",
                         (str(res.get("item_id")), data.title, data.price, url))
    return {"success": success, "data": res}


@app.get("/api/publish/list")
def get_published_list(request: Request):
    with db() as conn:
        rows = conn.execute("SELECT * FROM published_items ORDER BY id DESC LIMIT 200").fetchall()
    return etag_json(request, {"items": [dict(r) for r in rows]})


# ======================================================================
# 设置与 Prompt
# ======================================================================

PROMPT_FILES = ("classify_prompt", "price_prompt", "tech_prompt", "default_prompt")


@app.get("/api/prompts")
def get_prompts():
    prompts = {}
    for name in PROMPT_FILES:
        path = os.path.join(PROMPTS_DIR, f"{name}.txt")
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as fp:
                prompts[name] = fp.read()
    return {"prompts": prompts}


@app.post("/api/prompts")
def save_prompts(data: dict):
    # 原实现直接用 body 的 key 拼路径：{"../.env": "..."} 就能覆盖任意文件 → 严重漏洞
    for name, content in data.items():
        if name not in PROMPT_FILES:
            raise HTTPException(400, f"未知 Prompt: {name}")
        if not isinstance(content, str) or len(content) > 50_000:
            raise HTTPException(400, "Prompt 内容无效或过长")
    os.makedirs(PROMPTS_DIR, exist_ok=True)
    for name, content in data.items():
        path = os.path.join(PROMPTS_DIR, f"{name}.txt")
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fp:
            fp.write(content)
        os.replace(tmp, path)               # 原子替换，写一半断电也不会留下半截文件
    return {"success": True}


@app.get("/api/config")
def get_config():
    cfg = {}
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH, "r", encoding="utf-8") as fp:
            for line in fp:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    if k in ("API_TYPE", "MODEL_NAME", "TOGGLE_KEYWORDS", "SIMULATE_HUMAN_TYPING"):
                        cfg[k] = v
                    elif k == "MODEL_BASE_URL":
                        cfg[k] = v.split("?")[0]
                    elif k == "API_KEY":
                        cfg[k] = "已配置" if v else "未配置"   # 前后各露几位也会降低爆破成本，直接不露
    return {"config": cfg}
