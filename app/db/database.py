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
    _migrate_sku_multi_qty(c)
    conn.commit()
    conn.close()


def _columns(c, table):
    return {r[1] for r in c.execute(f"PRAGMA table_info({table})")}


def _migrate_sku_multi_qty(c):
    """T-015 无损迁移：多规格（SKU）+ 一次拍多件。只 ADD COLUMN / 建新表 / 换索引，旧数据原样保留，可重复执行。"""
    cols = _columns(c, "card_keys")
    if "sku" not in cols:
        # 旧卡密全部归入「默认套餐」（空字符串），旧规则不配套餐时照旧从这里出库
        c.execute("ALTER TABLE card_keys ADD COLUMN sku TEXT NOT NULL DEFAULT ''")
    if "order_line" not in cols:
        c.execute("ALTER TABLE card_keys ADD COLUMN order_line INTEGER")
    # 旧库已发出的卡按 id 顺序编号为第 1、2… 张（正常情况下每单只有 1 张）
    c.execute("""
        UPDATE card_keys SET order_line = (
            SELECT COUNT(*) FROM card_keys k2
             WHERE k2.order_id = card_keys.order_id AND k2.id <= card_keys.id)
         WHERE order_id IS NOT NULL AND order_line IS NULL
    """)
    # 旧索引「一个订单只能绑一张卡」会挡住多件发货，换成「订单 + 第几张」唯一
    c.execute("DROP INDEX IF EXISTS idx_card_order")
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_card_order_line "
              "ON card_keys(order_id, order_line) WHERE order_id IS NOT NULL")
    c.execute("DROP INDEX IF EXISTS idx_card_claim")
    c.execute("CREATE INDEX IF NOT EXISTS idx_card_pick ON card_keys(keyword, sku, is_used, id)")

    # 规则下的细分套餐：主关键词匹配商品标题，sku_pattern 再匹配订单里的规格文字
    c.execute("""
    CREATE TABLE IF NOT EXISTS delivery_rule_skus (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        rule_id INTEGER NOT NULL,
        sku_pattern TEXT NOT NULL,          -- 匹配订单规格的词，如「年卡」「专业版」；* 表示兜底
        card_sku TEXT NOT NULL DEFAULT '',  -- 从哪个套餐的卡密池出库，默认同 sku_pattern
        delivery_content TEXT,              -- 该套餐专属说明 / 网盘；为空则沿用规则的固定内容
        use_card_pool INTEGER,              -- NULL = 沿用规则设置
        enabled INTEGER DEFAULT 1,
        UNIQUE(rule_id, sku_pattern)
    )""")

    log_cols = _columns(c, "delivery_logs")
    for name, ddl in (("item_title", "TEXT DEFAULT ''"), ("sku_desc", "TEXT DEFAULT ''"),
                      ("card_sku", "TEXT DEFAULT ''"), ("quantity", "INTEGER DEFAULT 1")):
        if name not in log_cols:
            c.execute(f"ALTER TABLE delivery_logs ADD COLUMN {name} {ddl}")
    c.execute("CREATE INDEX IF NOT EXISTS idx_log_order ON delivery_logs(order_id)")
