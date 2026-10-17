import os
import sqlite3
from contextlib import contextmanager

DB_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "data", "xianyu.db",
)


def get_db() -> sqlite3.Connection:
    """
    autocommit 模式（isolation_level=None）：事务边界完全由我们显式控制，
    避免 Python sqlite3 隐式 BEGIN（DEFERRED）导致的“读后写”锁升级死锁。
    """
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")      # 读写并发：读不阻塞写
    conn.execute("PRAGMA synchronous=NORMAL;")    # WAL 下的安全/性能平衡
    conn.execute("PRAGMA busy_timeout=10000;")    # 写锁竞争时排队 10s 而不是立刻报 locked
    conn.execute("PRAGMA foreign_keys=ON;")
    return conn


@contextmanager
def db():
    """普通读写：保证连接在任何异常下都会关闭。"""
    conn = get_db()
    try:
        yield conn
    finally:
        conn.close()


@contextmanager
def write_tx(conn: sqlite3.Connection):
    """
    SQLite 版 “SELECT ... FOR UPDATE”：
    BEGIN IMMEDIATE 在事务开始时就拿到 RESERVED 写锁，
    同一时刻只有一个写事务能进入临界区，其它写者在 busy_timeout 内排队。
    """
    conn.execute("BEGIN IMMEDIATE;")
    try:
        yield conn
        conn.execute("COMMIT;")
    except Exception:
        conn.execute("ROLLBACK;")
        raise


def _column_exists(c, table, column):
    return any(r["name"] == column for r in c.execute(f"PRAGMA table_info({table})"))


def init_db():
    with db() as conn:
        c = conn.cursor()
        c.executescript("""
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL,
            item_id TEXT NOT NULL,
            chat_id TEXT,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS chat_bargain_counts (
            chat_id TEXT PRIMARY KEY,
            count INTEGER DEFAULT 0,
            last_updated DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS auto_delivery_rules (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            keyword TEXT UNIQUE,
            delivery_content TEXT,
            use_card_pool INTEGER DEFAULT 0,
            auto_confirm INTEGER DEFAULT 1,
            enabled INTEGER DEFAULT 1,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS card_keys (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            keyword TEXT,
            card_content TEXT,
            is_used INTEGER DEFAULT 0,
            order_id TEXT,
            used_at DATETIME,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
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
        CREATE TABLE IF NOT EXISTS published_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            item_id TEXT,
            title TEXT,
            price REAL,
            url TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        """)

        # --- 增量迁移（老库平滑升级） ---
        if not _column_exists(c, "delivery_logs", "status"):
            # claimed: 卡密已锁定出库，待发送 / sent: 已发送 / failed: 发送失败，可补发（同一张卡）
            c.execute("ALTER TABLE delivery_logs ADD COLUMN status TEXT DEFAULT 'sent'")
        if not _column_exists(c, "delivery_logs", "card_id"):
            c.execute("ALTER TABLE delivery_logs ADD COLUMN card_id INTEGER")
        if not _column_exists(c, "delivery_logs", "updated_at"):
            c.execute("ALTER TABLE delivery_logs ADD COLUMN updated_at DATETIME")

        # --- 索引与约束：防超卖的最后一道闸 ---
        c.executescript("""
        CREATE INDEX IF NOT EXISTS idx_msg_user_item ON messages(user_id, item_id, id);
        CREATE INDEX IF NOT EXISTS idx_card_pick ON card_keys(keyword, is_used, id);
        -- 一张卡只能属于一个订单；一个订单只能锁一张卡（规则：每单独享 1 张）
        CREATE UNIQUE INDEX IF NOT EXISTS uq_card_order ON card_keys(order_id) WHERE order_id IS NOT NULL;
        -- 同一订单只会有一条发货记录 → 幂等
        CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_order ON delivery_logs(order_id) WHERE order_id IS NOT NULL;
        CREATE INDEX IF NOT EXISTS idx_published_item ON published_items(item_id);
        """)
        # 同关键词下卡密去重，防止重复导入导致“一码多发”。
        # 老库若已有重复数据，建索引会失败：先告警，不阻断启动。
        try:
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_card_kw_content ON card_keys(keyword, card_content)")
        except sqlite3.IntegrityError:
            print("[WARN] card_keys 存在重复卡密，去重索引未创建。请先执行：\n"
                  "  DELETE FROM card_keys WHERE is_used=0 AND id NOT IN "
                  "(SELECT MIN(id) FROM card_keys GROUP BY keyword, card_content);")
