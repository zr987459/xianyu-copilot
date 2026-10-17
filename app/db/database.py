import sqlite3
import os

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "data", "xianyu.db")

def get_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    c = conn.cursor()
    # 消息记录
    c.execute('''
    CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        item_id TEXT NOT NULL,
        chat_id TEXT,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
    );
    ''')
    # 议价轮次记录
    c.execute('''
    CREATE TABLE IF NOT EXISTS chat_bargain_counts (
        chat_id TEXT PRIMARY KEY,
        count INTEGER DEFAULT 0,
        last_updated DATETIME DEFAULT CURRENT_TIMESTAMP
    );
    ''')
    # 自动发货规则（支持复合模式：固定内容 + 卡密池）
    c.execute('''
    CREATE TABLE IF NOT EXISTS auto_delivery_rules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        keyword TEXT UNIQUE,
        delivery_content TEXT,
        use_card_pool INTEGER DEFAULT 0,
        auto_confirm INTEGER DEFAULT 1,
        enabled INTEGER DEFAULT 1,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );
    ''')
    # 卡密资产库（支持单码/批量与状态追踪）
    c.execute('''
    CREATE TABLE IF NOT EXISTS card_keys (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        keyword TEXT,
        card_content TEXT,
        is_used INTEGER DEFAULT 0,
        order_id TEXT,
        used_at DATETIME,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );
    ''')
    # 发货日志
    c.execute('''
    CREATE TABLE IF NOT EXISTS delivery_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        order_id TEXT,
        user_id TEXT,
        chat_id TEXT,
        keyword TEXT,
        delivery_content TEXT,
        confirm_status TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );
    ''')
    # 已发布商品列表
    c.execute('''
    CREATE TABLE IF NOT EXISTS published_items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        item_id TEXT,
        title TEXT,
        price REAL,
        url TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );
    ''')
    _migrate_sku_and_quantity(c)
    conn.commit()
    conn.close()


def _columns(c, table):
    return {r[1] for r in c.execute(f"PRAGMA table_info({table})").fetchall()}


def _migrate_sku_and_quantity(c):
    """多规格 (SKU) + 一单多件 的增量迁移，可重复执行，不丢旧数据。

    - card_keys.sku：卡密所属套餐，'' 表示该商品的通用池（旧数据全部落在这里）
    - card_keys.order_seq：一单多件时这张卡是第几件（1..N），与 order_id 组成唯一键
    - auto_delivery_rules.shortage_policy：partial=有几张先发几张，余量补发；hold=凑齐再发
    - delivery_rule_skus：主规则下的套餐细分（套餐名 + 别名 + 专属说明 + 是否走卡密池）
    - delivery_orders：订单级发货状态，一单多件 / 缺货补发的幂等依据
    """
    cols = _columns(c, "card_keys")
    if "sku" not in cols:
        c.execute("ALTER TABLE card_keys ADD COLUMN sku TEXT NOT NULL DEFAULT ''")
    if "order_seq" not in cols:
        c.execute("ALTER TABLE card_keys ADD COLUMN order_seq INTEGER")
        # 旧数据：一单一卡，补成第 1 件
        c.execute("UPDATE card_keys SET order_seq=1 WHERE order_id IS NOT NULL AND order_seq IS NULL")
    if "shortage_policy" not in _columns(c, "auto_delivery_rules"):
        c.execute("ALTER TABLE auto_delivery_rules ADD COLUMN shortage_policy TEXT NOT NULL DEFAULT 'partial'")

    # 旧的 order_id 单列唯一索引与一单多件冲突，换成 (order_id, order_seq)
    c.execute("DROP INDEX IF EXISTS idx_card_order")
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_card_order_seq "
              "ON card_keys(order_id, order_seq) WHERE order_id IS NOT NULL")
    c.execute("DROP INDEX IF EXISTS idx_card_claim")
    c.execute("CREATE INDEX IF NOT EXISTS idx_card_claim_sku ON card_keys(keyword, sku, is_used, id)")

    c.execute('''
    CREATE TABLE IF NOT EXISTS delivery_rule_skus (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        rule_id INTEGER NOT NULL REFERENCES auto_delivery_rules(id) ON DELETE CASCADE,
        sku_name TEXT NOT NULL,
        aliases TEXT NOT NULL DEFAULT '',
        delivery_content TEXT NOT NULL DEFAULT '',
        use_card_pool INTEGER NOT NULL DEFAULT 1,
        UNIQUE(rule_id, sku_name)
    );
    ''')
    c.execute('''
    CREATE TABLE IF NOT EXISTS delivery_orders (
        order_id TEXT PRIMARY KEY,
        rule_id INTEGER,
        keyword TEXT,
        sku TEXT NOT NULL DEFAULT '',
        sku_desc TEXT NOT NULL DEFAULT '',
        quantity INTEGER NOT NULL DEFAULT 1,
        delivered_count INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL DEFAULT 'processing',
        user_id TEXT,
        chat_id TEXT,
        item_title TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );
    ''')
    c.execute("CREATE INDEX IF NOT EXISTS idx_delivery_orders_status ON delivery_orders(status, updated_at)")

    # 发货可靠性（v2）：
    # - sent_count：已"实际发到买家"的卡密件数（≠ 已绑定件数）。绑卡后发消息失败，重跑时按它补发，不会丢卡
    # - fixed_sent：固定说明是否已发过，补发时不再重复
    # - lease_until：订单处理租约（unix 秒）。WSS 重推 / 多 worker 同时处理同一订单时只有一个能进入
    # - confirm_status：确认发货结果；失败会进入 confirm_failed 并可重试
    oc = _columns(c, "delivery_orders")
    if "sent_count" not in oc:
        c.execute("ALTER TABLE delivery_orders ADD COLUMN sent_count INTEGER NOT NULL DEFAULT 0")
        c.execute("UPDATE delivery_orders SET sent_count=delivered_count")
    if "fixed_sent" not in oc:
        c.execute("ALTER TABLE delivery_orders ADD COLUMN fixed_sent INTEGER NOT NULL DEFAULT 0")
        c.execute("UPDATE delivery_orders SET fixed_sent=1 WHERE status IN ('partial','pending_stock','delivered')")
    if "lease_until" not in oc:
        c.execute("ALTER TABLE delivery_orders ADD COLUMN lease_until REAL")
    if "confirm_status" not in oc:
        c.execute("ALTER TABLE delivery_orders ADD COLUMN confirm_status TEXT NOT NULL DEFAULT ''")
    c.execute("CREATE INDEX IF NOT EXISTS idx_card_content ON card_keys(keyword, card_content)")
