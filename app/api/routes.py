import os
import base64
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List

from pydantic import Field

from app.db.database import get_db, init_db, DB_PATH
from app.db.cards import claim_cards as _claim_cards, stock_by_sku, StockLockError, MAX_QUANTITY
from app.core.security import AdminAuthMiddleware
from app.services.publisher_service import XianyuPublisherService
from app.services.XianyuApis import XianyuApis

app = FastAPI(title="Xianyu Copilot API", version="1.1.0")

# 同源访问无需 CORS；只有显式配置 ALLOW_ORIGINS 才放行跨域（不再默认 "*"）
_origins = os.getenv("ALLOW_ORIGINS", "").split(",")
app.add_middleware(AdminAuthMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[o.strip() for o in _origins if o.strip() and o.strip() != "*"],
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["*"],
)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PROMPTS_DIR = os.path.join(BASE_DIR, "prompts")
ENV_PATH = os.path.join(BASE_DIR, ".env")

ALLOWED_PROMPTS = {"classify_prompt", "price_prompt", "tech_prompt", "default_prompt"}


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
    conn = get_db()
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA busy_timeout=5000;")
    # 索引与 SKU / 一单多件迁移统一在 init_db() 里完成（idx_card_order_seq / idx_card_claim_sku）
    conn.commit()
    conn.close()


# ========= 卡密原子出库（防超卖 / 事务独享） =========
def _db_path():
    from app.db import database
    return database.DB_PATH


def claim_cards(keyword: str, order_id: str, quantity: int = 1, sku: str = "", allow_partial: bool = True):
    """委托 app.db.cards.claim_cards（唯一实现）"""
    return _claim_cards(_db_path(), keyword, order_id, quantity=quantity, sku=sku, allow_partial=allow_partial)


def claim_card(keyword: str, order_id: str, sku: str = ""):
    """兼容旧调用：一单一卡"""
    res = claim_cards(keyword, order_id, 1, sku)
    return res.cards[0] if res.cards else None


class ClaimRequest(BaseModel):
    keyword: str
    order_id: str
    sku: str = ""
    quantity: int = Field(1, ge=1, le=MAX_QUANTITY)
    allow_partial: bool = False  # 手动出库默认“凑齐才出”，避免误操作把库存拆散


@app.post("/api/cards/claim")
def claim_card_api(data: ClaimRequest):
    try:
        res = claim_cards(data.keyword, data.order_id, data.quantity, data.sku, data.allow_partial)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except StockLockError:
        raise HTTPException(status_code=503, detail="库存繁忙，请稍后重试")
    if not res.cards:
        raise HTTPException(status_code=409, detail="库存不足，卡密已被抢空")
    return {
        "success": True,
        "complete": res.complete,
        "requested": res.requested,
        "delivered": res.delivered,
        "shortage": res.shortage,
        "cards": [{"seq": c["order_seq"], "card_content": c["card_content"]} for c in res.cards],
        "card_content": res.cards[0]["card_content"],  # 兼容旧前端
    }


@app.get("/api/cards/stock")
def get_card_stock(keyword: Optional[str] = ""):
    """库存分布：按 关键词 → 套餐 汇总在库 / 已发"""
    return {"stock": stock_by_sku(_db_path(), keyword or None)}


# ========= 概览指标 =========
@app.get("/api/stats")
def get_stats():
    conn = get_db()
    c = conn.cursor()
    total_msgs = c.execute("SELECT count(*) AS count FROM messages").fetchone()["count"]
    total_users = c.execute("SELECT count(distinct user_id) AS count FROM messages").fetchone()["count"]
    total_items = c.execute("SELECT count(*) AS count FROM published_items").fetchone()["count"]
    total_delivered = c.execute("SELECT count(*) AS count FROM delivery_logs").fetchone()["count"]
    available_cards = c.execute("SELECT count(*) AS count FROM card_keys WHERE is_used=0").fetchone()["count"]
    used_cards = c.execute("SELECT count(*) AS count FROM card_keys WHERE is_used=1").fetchone()["count"]
    conn.close()
    return {
        "total_msgs": total_msgs,
        "total_users": total_users,
        "total_items": total_items,
        "total_delivered": total_delivered,
        "available_cards": available_cards,
        "used_cards": used_cards,
    }


# ========= 消息 Tab =========
@app.get("/api/chats")
def get_chats():
    conn = get_db()
    c = conn.cursor()
    rows = c.execute(
        """
        SELECT m.user_id, m.item_id, m.chat_id,
               max(m.timestamp) AS last_time, count(*) AS msg_count
        FROM messages m
        GROUP BY m.user_id, m.item_id
        ORDER BY last_time DESC
        LIMIT 50
        """
    ).fetchall()
    chats = []
    for r in rows:
        last_msg = c.execute(
            "SELECT role, content FROM messages WHERE user_id=? AND item_id=? ORDER BY id DESC LIMIT 1",
            (r["user_id"], r["item_id"]),
        ).fetchone()
        b_count = 0
        if r["chat_id"]:
            bc = c.execute("SELECT count FROM chat_bargain_counts WHERE chat_id=?", (r["chat_id"],)).fetchone()
            if bc:
                b_count = bc["count"]
        chats.append({
            "user_id": r["user_id"],
            "item_id": r["item_id"],
            "chat_id": r["chat_id"],
            "last_time": r["last_time"],
            "msg_count": r["msg_count"],
            "last_role": last_msg["role"] if last_msg else "",
            "last_content": last_msg["content"] if last_msg else "",
            "bargain_count": b_count,
        })
    conn.close()
    return {"chats": chats}


@app.get("/api/chat/messages")
def get_chat_messages(user_id: str, item_id: str):
    conn = get_db()
    c = conn.cursor()
    rows = c.execute(
        "SELECT id, role, content, timestamp FROM messages WHERE user_id=? AND item_id=? ORDER BY id ASC",
        (user_id, item_id),
    ).fetchall()
    conn.close()
    return {"messages": [dict(r) for r in rows]}


# ========= 发货规则 & 卡密池 Tab =========
class SkuRule(BaseModel):
    sku_name: str = Field(..., min_length=1, max_length=40)
    aliases: str = Field("", max_length=200)          # 逗号分隔的别名，如 “1个月,30天”
    delivery_content: str = Field("", max_length=4000)  # 该套餐专属说明/网盘，留空则沿用主规则
    use_card_pool: bool = True


class RuleCreate(BaseModel):
    keyword: str
    delivery_content: Optional[str] = ""
    use_card_pool: bool = False
    auto_confirm: bool = True
    shortage_policy: str = Field("partial", pattern="^(partial|hold)$")
    skus: Optional[List[SkuRule]] = None  # None=不改动已有套餐；[]=清空套餐细分


@app.get("/api/delivery/rules")
def get_delivery_rules():
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("SELECT * FROM auto_delivery_rules ORDER BY id DESC").fetchall()
    rules = []
    for r in rows:
        item = dict(r)
        stock = c.execute(
            """
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN is_used=0 THEN 1 ELSE 0 END) AS unused,
                   SUM(CASE WHEN is_used=1 THEN 1 ELSE 0 END) AS used
            FROM card_keys WHERE keyword=?
            """,
            (item["keyword"],),
        ).fetchone()
        item["card_total"] = stock["total"] or 0
        item["card_stock"] = stock["unused"] or 0
        item["card_used"] = stock["used"] or 0
        dist = {d["sku"]: d for d in stock_by_sku(_db_path(), item["keyword"])}
        item["skus"] = []
        for s in c.execute("SELECT * FROM delivery_rule_skus WHERE rule_id=? ORDER BY id", (item["id"],)):
            sd = dict(s)
            d = dist.get(sd["sku_name"], {})
            sd["card_stock"], sd["card_used"] = d.get("unused", 0), d.get("used", 0)
            item["skus"].append(sd)
        g = dist.get("", {})
        item["generic_stock"], item["generic_used"] = g.get("unused", 0), g.get("used", 0)
        rules.append(item)
    conn.close()
    return {"rules": rules}


@app.post("/api/delivery/rules")
def create_rule(data: RuleCreate):
    keyword = data.keyword.strip()
    if not keyword:
        raise HTTPException(status_code=400, detail="keyword 不能为空")
    names = [s.sku_name.strip() for s in (data.skus or [])]
    if len(set(names)) != len(names) or any(not n for n in names):
        raise HTTPException(status_code=400, detail="套餐名不能为空或重复")
    conn = get_db()
    try:
        conn.execute("BEGIN IMMEDIATE")
        # UPSERT 保留规则 id（INSERT OR REPLACE 会换 id，并级联删掉套餐细分）
        conn.execute(
            """
            INSERT INTO auto_delivery_rules
                (keyword, delivery_content, use_card_pool, auto_confirm, shortage_policy, enabled)
            VALUES (?, ?, ?, ?, ?, 1)
            ON CONFLICT(keyword) DO UPDATE SET
                delivery_content=excluded.delivery_content, use_card_pool=excluded.use_card_pool,
                auto_confirm=excluded.auto_confirm, shortage_policy=excluded.shortage_policy, enabled=1
            """,
            (keyword, (data.delivery_content or "").strip(), 1 if data.use_card_pool else 0,
             1 if data.auto_confirm else 0, data.shortage_policy),
        )
        rule_id = conn.execute("SELECT id FROM auto_delivery_rules WHERE keyword=?", (keyword,)).fetchone()["id"]
        if data.skus is not None:
            conn.execute("DELETE FROM delivery_rule_skus WHERE rule_id=?", (rule_id,))
            conn.executemany(
                "INSERT INTO delivery_rule_skus (rule_id, sku_name, aliases, delivery_content, use_card_pool) "
                "VALUES (?, ?, ?, ?, ?)",
                [(rule_id, s.sku_name.strip(), s.aliases.strip(), s.delivery_content.strip(),
                  1 if s.use_card_pool else 0) for s in data.skus],
            )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return {"success": True, "rule_id": rule_id}


@app.delete("/api/delivery/rules/{rule_id}")
def delete_rule(rule_id: int):
    conn = get_db()
    conn.execute("DELETE FROM delivery_rule_skus WHERE rule_id=?", (rule_id,))
    conn.execute("DELETE FROM auto_delivery_rules WHERE id=?", (rule_id,))
    conn.commit()
    conn.close()
    return {"success": True}


class SingleCardCreate(BaseModel):
    keyword: str
    card_content: str
    sku: str = Field("", max_length=40)  # 套餐/规格，留空=商品通用池


@app.post("/api/cards/add")
def add_single_card(data: SingleCardCreate):
    if not data.keyword.strip() or not data.card_content.strip():
        raise HTTPException(status_code=400, detail="keyword 与 card_content 不能为空")
    conn = get_db()
    conn.execute(
        "INSERT INTO card_keys (keyword, sku, card_content, is_used) VALUES (?, ?, ?, 0)",
        (data.keyword.strip(), data.sku.strip(), data.card_content.strip()),
    )
    conn.commit()
    conn.close()
    return {"success": True}


class BatchCardImport(BaseModel):
    keyword: str
    raw_keys: str
    sku: str = Field("", max_length=40)


@app.post("/api/cards/import")
def import_cards(data: BatchCardImport):
    keyword = data.keyword.strip()
    if not keyword:
        raise HTTPException(status_code=400, detail="keyword 不能为空")
    # 去掉空行与本批重复行，避免同一张卡入库两次被发给两个买家
    lines = list(dict.fromkeys(ln.strip() for ln in data.raw_keys.splitlines() if ln.strip()))
    conn = get_db()
    c = conn.cursor()
    sku = data.sku.strip()
    c.executemany("INSERT INTO card_keys (keyword, sku, card_content, is_used) VALUES (?, ?, ?, 0)",
                  [(keyword, sku, card) for card in lines])
    conn.commit()
    conn.close()
    return {"success": True, "count": len(lines)}


@app.get("/api/cards/list")
def get_card_list(keyword: Optional[str] = "", status: Optional[str] = "all", sku: Optional[str] = None):
    conn = get_db()
    query = "SELECT * FROM card_keys WHERE 1=1"
    params = []
    if keyword:
        query += " AND keyword=?"
        params.append(keyword)
    if sku is not None:  # sku= 显式传空串时只看通用池
        query += " AND sku=?"
        params.append(sku.strip())
    if status == "unused":
        query += " AND is_used=0"
    elif status == "used":
        query += " AND is_used=1"
    query += " ORDER BY id DESC LIMIT 200"
    rows = conn.execute(query, params).fetchall()
    conn.close()
    return {"cards": [dict(r) for r in rows]}


@app.delete("/api/cards/{card_id}")
def delete_card(card_id: int):
    conn = get_db()
    cur = conn.execute("DELETE FROM card_keys WHERE id=? AND is_used=0", (card_id,))
    conn.commit()
    conn.close()
    if cur.rowcount == 0:
        raise HTTPException(status_code=409, detail="卡密不存在或已发出（已发卡密是售后凭证，不可删除）")
    return {"success": True}


@app.get("/api/delivery/orders")
def get_delivery_orders(status: Optional[str] = ""):
    """订单级发货状态；status=pending 只看缺货待补发 / 转人工"""
    conn = get_db()
    q = "SELECT * FROM delivery_orders"
    if status == "pending":
        q += " WHERE status IN ('partial','pending_stock','manual')"
    rows = conn.execute(q + " ORDER BY updated_at DESC LIMIT 100").fetchall()
    conn.close()
    return {"orders": [dict(r) for r in rows]}


@app.get("/api/delivery/logs")
def get_delivery_logs():
    conn = get_db()
    rows = conn.execute("SELECT * FROM delivery_logs ORDER BY id DESC LIMIT 50").fetchall()
    conn.close()
    return {"logs": [dict(r) for r in rows]}


# ========= 商品上架 Tab =========
class PublishItemRequest(BaseModel):
    title: str
    description: str
    price: float
    original_price: Optional[float] = None
    image_base64: Optional[str] = ""


@app.post("/api/publish/item")
def publish_item_api(data: PublishItemRequest):
    publisher = get_publisher_instance()
    images = []
    if data.image_base64:
        raw_b64 = data.image_base64.split(",", 1)[1] if "," in data.image_base64 else data.image_base64
        try:
            images.append(base64.b64decode(raw_b64))
        except Exception:
            pass
    success, res = publisher.publish_item(
        title=data.title,
        description=data.description,
        price=data.price,
        original_price=data.original_price or data.price,
        image_urls_or_bytes=images,
    )
    if success:
        conn = get_db()
        conn.execute(
            "INSERT INTO published_items (item_id, title, price, url) VALUES (?, ?, ?, ?)",
            (str(res.get("item_id")), data.title, data.price, res.get("url")),
        )
        conn.commit()
        conn.close()
    return {"success": success, "data": res}


@app.get("/api/publish/list")
def get_published_list():
    conn = get_db()
    rows = conn.execute("SELECT * FROM published_items ORDER BY id DESC").fetchall()
    conn.close()
    return {"items": [dict(r) for r in rows]}


# ========= 设置与 Prompt Tab（路径白名单防穿越） =========
@app.get("/api/prompts")
def get_prompts():
    prompts = {}
    for name in ALLOWED_PROMPTS:
        path = os.path.join(PROMPTS_DIR, f"{name}.txt")
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as fp:
                prompts[name] = fp.read()
    return {"prompts": prompts}


@app.post("/api/prompts")
def save_prompts(data: dict):
    for name, content in data.items():
        if name not in ALLOWED_PROMPTS:
            raise HTTPException(status_code=400, detail=f"invalid prompt name: {name}")
        if not isinstance(content, str):
            raise HTTPException(status_code=400, detail="content must be string")
        path = os.path.join(PROMPTS_DIR, f"{name}.txt")
        if os.path.dirname(os.path.abspath(path)) != os.path.abspath(PROMPTS_DIR):
            raise HTTPException(status_code=400, detail="path escape blocked")
        with open(path, "w", encoding="utf-8") as fp:
            fp.write(content)
    return {"success": True}


@app.get("/api/config")
def get_config():
    cfg = {}
    allow = {"API_TYPE", "MODEL_NAME", "MODEL_BASE_URL", "TOGGLE_KEYWORDS", "SIMULATE_HUMAN_TYPING"}
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH, "r", encoding="utf-8") as fp:
            for line in fp:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    if k in allow:
                        cfg[k] = v
                    elif k == "API_KEY":
                        cfg[k] = (v[:6] + "..." + v[-4:]) if len(v) > 10 else "***"
    return {"config": cfg}
