# T-007 架构审查与增强方案（Hark）

> 审查对象：main @ 6ab83c6 · 对标：zhinianboke/xianyu-auto-reply 的 xianyu-mobile（Expo 57 + RN 0.86 + expo-router + zustand + openapi-fetch + expo-secure-store）
> 本分支 `hark/t-007-review` 已包含：P0 修复、发货状态机引擎及测试、原生交互层 CSS/JS。index.html 的接入由本地工程执行官按第 4 节完成。

## 0. 结论

| 级别 | 问题 | 状态 |
|---|---|---|
| P0 | `python main.py` 启动即崩：3 个 service 用 `from utils.xianyu_utils`，应为 `app.utils` | 本分支已修 |
| P0 | `.env` 从未加载（无 `load_dotenv`），API_KEY / MODEL_* 读不到 | 本分支已修 |
| P0 | 服务监听 0.0.0.0、无任何鉴权、CORS `*`+credentials：同网段任何人可读全部卡密、聊天、改 prompt、发商品 | 本分支已修（ADMIN_TOKEN + 默认 127.0.0.1） |
| P0 | `POST /api/prompts` 用请求里的 key 拼文件名 → 路径穿越写任意 `.txt` | 本分支已修（白名单） |
| P0 | index.html 13 处 `innerHTML` 直接拼买家消息 → 买家发一句 `<img onerror>` 即可在管理面板执行脚本、读走卡密 | 提供 `Native.esc`，接入见 4.0 |
| P0 | README 宣称的「WSS 监听 / 毫秒级自动发货 / 卡密绝不重复」在代码里不存在：没有消息监听器，没有任何代码从 card_keys 取卡 | 引擎已补（fulfillment.py），监听器待做 T-010 |
| P1 | 同步 `requests` 在 async FastAPI 里调用、SQLite 无 WAL / 无索引 / 每请求新连接 | 见第 3 节 |
| P1 | Tailwind Play CDN（运行时 JIT，官方不建议生产）+ `lucide@latest`（未锁版本，供应链风险） | 见 4.5 |

## 1. 与 xianyu-mobile 的架构差距与取舍

| 维度 | xianyu-mobile | 当前 | 建议 |
|---|---|---|---|
| 客户端 | 原生 RN，50+ 页 | 单 index.html | **阶段 1 做 PWA**（本分支），阶段 2 再考虑 Expo 壳；不要现在就上 RN |
| API 契约 | openapi-typescript 生成类型 | 手写 fetch | FastAPI 自带 `/openapi.json`；给所有路由补 `response_model`，日后 Expo/TS 客户端直接生成类型 |
| 鉴权 | token 存 expo-secure-store | 无 | ADMIN_TOKEN（Bearer）；PWA 存 localStorage，仅自托管场景可接受 |
| 实时 | WebSocket + 指数退避 + AppState 前台恢复 | 无（手动刷新） | SSE `/api/events` + 退避 + `visibilitychange` 重连（native.js 已实现客户端） |
| 列表交互 | 左滑 已读/删除、手动发货/拉黑/复制单号 | 无 | `Native.swipe` |
| 状态 | zustand | 全局变量 | 单文件阶段维持简单 store 对象即可 |

## 2. 发货状态机（app/services/fulfillment.py）

```
PAID ──reserve──> RESERVED ──send ok──> SENT ──consign ok──> DONE
 │                  │  ▲                 │
 │                  │  └─send fail───────┤ 重试同一张卡，绝不换卡
 │                  │                    └─consign fail → 停在 SENT，只重试确认
 ├─无库存─> OUT_OF_STOCK ─补货 requeue─> PAID
 └─(SENT 之前) cancel ─> CANCELLED（RESERVED 的卡退回池）
任意状态失败 ≥5 次 → MANUAL（面板人工处理）
```

防超卖与一致性的五道闸：
1. **幂等入单**：`orders.order_id` 主键 + `INSERT OR IGNORE`，WSS 重复推送付款事件只建一单。
2. **写锁**：预留在 `BEGIN IMMEDIATE` 事务内，多进程同一时刻只有一个写者。
3. **CAS 抢卡**：`UPDATE card_keys SET status='reserved' ... WHERE id=? AND status='available'`，rowcount≠1 即被抢，重选。
4. **唯一索引**：`card_keys(order_id) WHERE order_id IS NOT NULL` 唯一 → 数据库层面一单最多一张卡。
5. **网络调用在事务外**，发送失败保留预留、下次发同一张；SENT 之后卡永不回池。

迁移：`migrate()` 增量加 `status` / `reserved_at` 列，旧 `is_used=1 → sent`、`0 → available`，旧数据不丢；`is_used` 列保留以兼容现有 `/api/stats`。

测试 `tests/test_fulfillment.py`（8 项全过）：8 线程 80 单抢 50 卡、4 进程 60 单抢 40 卡均零重复；重试发同一张卡；确认失败不重发消息；取消退卡；缺货→补货；非法迁移拒绝；旧数据迁移。

### 接入 worker（待本地执行官实现，约半天）
```python
# app/services/fulfillment_worker.py（示意）
async def run_worker(db_path, apis, delivery):
    conn = F.connect(db_path); F.migrate(conn)
    while True:
        rows = conn.execute("SELECT order_id, keyword FROM orders WHERE status IN ('PAID','RESERVED','SENT') "
                            "AND attempts < 5 AND updated_at < ? ORDER BY updated_at LIMIT 20",
                            (time.time() - backoff_seconds,)).fetchall()
        for r in rows:
            rule = load_rule(conn, r["keyword"])
            await asyncio.to_thread(F.process, conn, r["order_id"], rule,
                                    lambda chat, text: apis.send_text(chat, text),   # 由 WSS 客户端提供
                                    lambda oid: delivery.confirm_dummy_shipping(oid)[0])
        await asyncio.sleep(2)
```
- WSS 监听器收到「已付款」只调用 `F.on_paid(...)`，不在回调里直接发货。
- 补卡接口 `/api/cards/import` 成功后调用 `F.requeue_out_of_stock(conn, keyword)`。
- `confirm_dummy_shipping` 目前用同步 requests：保持在 `to_thread` 里调用；遇到 `FAIL_SYS_TOKEN_EXOIRED` 先刷新 `_m_h5_tk` 再计一次失败。
- `/api/delivery/logs` 改读 `orders` + `order_events`，订单 Tab 的 待发货/已发货 筛选才有真实数据。

### SQLite 基线（database.py）
`get_db()` 增加 `PRAGMA journal_mode=WAL; PRAGMA busy_timeout=30000;`，`messages(user_id,item_id,id)` 建索引；`/api/chats` 的 N+1 查询改为一次窗口函数查询。

## 3. 后端补充接口（供 UI 使用）

| 接口 | 用途 |
|---|---|
| `GET /api/items/{item_id}` | 对话页浮动卡：标题、主图、价格、原价、最近一单状态、`can_ship` |
| `POST /api/orders/{id}/ship` | 左滑「手动发货」：`on_paid` 若不存在 + 立即 `process` |
| `POST /api/chats/{chat_id}/read` / `DELETE` | 左滑 已读 / 删除 |
| `GET /api/events`（SSE） | 新消息、新订单、发货结果推送，驱动 TabBar 角标 |
| messages 表加 `thinking TEXT, think_ms INTEGER` | 思考链折叠。`llm_client.call()` 改为返回 `(text, thinking)`：Anthropic 取 `type=="thinking"` 块，OpenAI 兼容网关取 `reasoning_content`；**thinking 只入库给卖家看，绝不发给买家** |

## 4. index.html 接入（本地执行官照做）

### 4.0 先修 XSS（必须）
所有 `${c.last_content}`、`${m.content}`、`${l.delivery_content}`、卡密内容、标题，一律改为 `${Native.esc(...)}`；`onclick="openChatModal('${c.user_id}', ...)"` 改为 `data-uid` + 事件委托。所有 `fetch(` 改为 `Native.api(`（自动带口令）。

### 4.1 头部
```html
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#F8FAFC" media="(prefers-color-scheme: light)">
<meta name="apple-mobile-web-app-capable" content="yes">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="stylesheet" href="/static/native.css">
<script src="/static/native.js" defer></script>
```
`<body class="app ...">`，`<main>` 加 `app-scroll`，去掉 `h-screen`。不要再加 `user-scalable=no`（iOS 已忽略，且损害可访问性）。

### 4.2 毛玻璃 TabBar
```html
<nav class="tabbar" role="tablist" id="tabbar">
  <button class="tabbar__item" role="tab" data-tab="messages" aria-selected="true">
    <i data-lucide="message-circle"></i><span>消息</span><span class="tabbar__badge" data-n="0"></span></button>
  <button class="tabbar__item" role="tab" data-tab="orders" aria-selected="false">
    <i data-lucide="clipboard-list"></i><span>订单</span><span class="tabbar__badge" data-n="0"></span></button>
  <button class="tabbar__item" role="tab" data-tab="products" aria-selected="false">
    <i data-lucide="tag"></i><span>商品</span></button>
  <button class="tabbar__item" role="tab" data-tab="settings" aria-selected="false">
    <i data-lucide="user"></i><span>我的</span></button>
</nav>
<script>addEventListener('DOMContentLoaded', () => Native.tabbar(document.getElementById('tabbar'), switchTab));</script>
```

### 4.3 左滑操作
```js
// 会话列表
box.innerHTML = list.map(c => Native.swipeRow(
  `<div class="p-3 flex items-center gap-3">… ${Native.esc(c.last_content)} …</div>`,
  [{id:'read', label:'已读', tone:'read'}, {id:'del', label:'删除', tone:'danger'}],
  {uid: c.user_id, item: c.item_id, chat: c.chat_id})).join('');
Native.swipe(box, { onAction: (act, d) => act === 'read' ? markRead(d.chat) : delChat(d.chat) });
box.addEventListener('click', e => { const r = e.target.closest('.swipe'); if (r) openChatModal(r.dataset.uid, r.dataset.item); });
// 订单列表：[{id:'ship',label:'发货',tone:'ship'},{id:'copy',label:'复制单号',tone:'copy'}]
```
手势细节已内建：6px 判向、横向 1.2 倍于纵向才锁定横滑（不抢列表滚动）、超出按钮宽度橡皮筋、40% 阈值吸附、同时只开一行、点空白收起、滑动后吞掉误触 click、Android 触感。

### 4.4 对话页：宝贝浮动卡 + 思考链 + 下拉关闭
```js
const [item, res] = await Promise.all([Native.api(`/api/items/${encodeURIComponent(itemId)}`),
                                       Native.api(`/api/chat/messages?user_id=${encodeURIComponent(userId)}&item_id=${encodeURIComponent(itemId)}`)]);
body.innerHTML = Native.itemCard(item) + res.messages.map(m => {
  const me = m.role === 'assistant';
  return `<div class="flex flex-col ${me ? 'items-end' : 'items-start'}">
    ${me ? Native.thinking(m.thinking, m.think_ms) : ''}
    <div class="bubble ${me ? 'bubble--me' : ''}">${Native.esc(m.content)}</div></div>`;
}).join('');
Native.bindCompactOnScroll(body);
Native.sheet(document.querySelector('#chat-modal .sheet'), { onClose: closeChatModal });
```
弹层 HTML：外层 `#chat-modal` 加 `sheet-backdrop`，内层白卡加 `sheet` 并在顶部放 `<div class="sheet__grabber"></div>`。

### 4.5 构建与 PWA
- 用 Tailwind standalone CLI 预编译：`tailwindcss -i in.css -o app/static/app.css --minify`，去掉 Play CDN 运行时。
- lucide 锁定版本并下载到 `app/static/`，不走 `@latest`。
- 新增 `manifest.webmanifest`（`display: standalone`、图标 192/512）与只缓存静态资源的 `sw.js`；API 不缓存。

## 5. 建议的任务拆分（追加到 TASKS.md，由主人确认）
- T-009 合并本分支：P0 修复 + fulfillment 引擎 + native 层（已测）
- T-010 WSS 消息监听器（含心跳、token 刷新），付款事件 → `F.on_paid`
- T-011 fulfillment_worker + 发货相关接口（第 2、3 节）
- T-012 index.html 接入第 4 节（先 4.0 XSS）
- T-013 llm_client 返回 thinking + messages 表加列
- T-014 Tailwind 预编译 + PWA
- T-008 发布 v1.0.0 推迟到 T-010～T-012 完成之后
