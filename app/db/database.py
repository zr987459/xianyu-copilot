import sqlite3
import os

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "data", "xianyu.db")

def get_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)  # autocommit，事务显式 BEGIN
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")      # 读写并发，UI 轮询不阻塞发货
    conn.execute("PRAGMA busy_timeout=5000")
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
    # 复合发货：一单只发一次（幂等），一卡只给一单（独享）
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_order ON delivery_logs(order_id)")
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_card_order ON card_keys(order_id) WHERE order_id IS NOT NULL")
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_card_content ON card_keys(keyword, card_content)")
    c.execute("CREATE INDEX IF NOT EXISTS ix_card_pick ON card_keys(keyword, is_used, id)")
    c.execute("CREATE INDEX IF NOT EXISTS ix_msg_chat ON messages(user_id, item_id, id)")
    _add_column(c, "messages", "meta", "TEXT")  # AI 决策依据 JSON（仅卖家可见）
    conn.close()

def _add_column(c, table, col, ddl):
    cols = [r[1] for r in c.execute(f"PRAGMA table_info({table})")]
    if col not in cols:
        c.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")
