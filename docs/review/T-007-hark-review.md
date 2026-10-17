# T-007 架构与移动端交互审查（Hark）

- 审查基线：`main @ 6ab83c6`
- 补丁分支：`review/hark-t007`（未合入 main，由本地工程执行官审阅后合并）
- 验证：`python -m pytest tests -q` 7 项通过（含 4 进程 80 单抢 30 张卡的防超卖测试）；FastAPI TestClient 冒烟通过；未在真实闲鱼账号上联调。

## 0. 结论先行

| 级别 | 问题 | 后果 | 本分支处理 |
|---|---|---|---|
| P0 | 管理 API 无任何鉴权，监听 `0.0.0.0`，CORS `*` + credentials | 同网段/公网任何人可读全部卡密、用你的 Cookie 上架商品、改 Prompt | `ADMIN_TOKEN` 中间件；默认只听 `127.0.0.1`；CORS 默认关闭 |
| P0 | 买家消息、卡密、规则直接拼进 `innerHTML` | **存储型 XSS**：买家发一句 `<img src=x onerror=...>`，你一打开会话，脚本就以管理员身份运行，可拉走整个卡密池 | `UI.esc()` 覆盖全部 16 处插值；onclick 改 `data-*` |
| P0 | `POST /api/prompts` 用请求里的 key 拼文件名 | 路径穿越，可覆写 `.env` 或任意文件 | 白名单 4 个 prompt 名 |
| P0 | `from utils.xianyu_utils import ...` | 以 `python main.py` 启动时三个 service 模块 `ImportError`，发货/上架不可用 | 改为 `app.utils.xianyu_utils` |
| P1 | 卡密只有 `is_used` 0/1，没有"占用"态，也没有订单唯一约束 | 并发/重复推送时一卡多发或一单多卡；发送失败后卡丢失 | 新状态机 + `BEGIN IMMEDIATE` 原子抢占 + 唯一索引 |
| P1 | 卡密列表接口返回明文 | 截图/肩窥即泄露 | 列表默认脱敏，`?reveal=1` 才给明文 |
| P1 | 仓库里**没有**订单监听与发货编排（只有确认发货接口） | TASKS 里 T-003/T-004 标为完成，但"付款→发卡→确认"链路实际不存在 | 提供 `DeliveryEngine`，待接入 WS 监听（见 T-010） |
| P2 | SQLite 默认 rollback journal、无 busy_timeout | 前端轮询与发货写入互相 `database is locked` | WAL + busy_timeout=5s |
| P2 | Tailwind Play CDN、`lucide@latest` | 生产不稳定、版本漂移、离线不可用 | 建议见 §3.4（本分支未改） |

## 1. 与 xianyu-mobile 的架构对照

xianyu-mobile（zhinianboke/xianyu-auto-reply 子项目）是 Expo 57 + RN 0.86 + expo-router + zustand + openapi-fetch，4 Tab、左滑已读/删除/手动发货、WebSocket 指数退避重连、token 存 SecureStore。

| 维度 | xianyu-mobile | xianyu-copilot 现状 | 建议（不引入构建链的前提下） |
|---|---|---|---|
| 渲染 | 原生组件 | 单文件 HTML + 字符串模板 | 保持单文件 PWA，抽 `ui-kit.js` 组件层；规模再翻倍时迁 Preact + htm（无需打包） |
| 路由 | 文件路由，返回键可用 | `switchTab` 显隐，返回键直接退出 | `history.pushState` + `popstate`（抽屉已实现），Tab 用 `#hash` |
| 状态 | zustand | 全局变量 | 一个 `store = {chats, orders, ...}` + 订阅函数即可 |
| API 类型 | openapi-typescript 生成 | 手写 fetch | FastAPI 已自带 `/openapi.json`，加 Pydantic 响应模型即可生成 |
| 实时 | WS + 退避重连 + 前台恢复 | 手动刷新 | `/api/events` SSE（比 WS 简单，自带重连），`visibilitychange` 时补拉 |
| 凭据 | SecureStore | 无鉴权 | `ADMIN_TOKEN` + localStorage；公网务必 HTTPS 反代 |
| 安装形态 | APK | 网页 | 加 `manifest.webmanifest` + Service Worker，"添加到主屏幕"后全屏无地址栏 |

## 2. 移动端 UI 深度升级（`app/static/ui-kit.css` + `ui-kit.js`）

| 组件 | 用法 | 要点 |
|---|---|---|
| 毛玻璃 TabBar | `<nav class="glass-tabbar">`，内容区加 `has-glass-tabbar` | `saturate(180%) blur(20px)`、0.5px 发丝线、安全区、不支持时降级实底；激活图标微缩放 + 8ms 震动 |
| 左滑操作 | `UI.swipe(listEl, (act, dataset) => ...)` | 事件委托（重绘不用重绑）、方向锁定（不抢纵向滚动）、超程橡皮筋阻尼、过半吸附、同时只开一行、滑动后吞掉误触 click |
| 底部抽屉 | `UI.sheet(sheetEl, maskEl).open()` | iOS 曲线 `cubic-bezier(.32,.72,0,1)`、拖动把手下拉 >120px 关闭、点遮罩关闭、**安卓返回键关闭**而不是退出页面 |
| 浮动宝贝卡 | `UI.itemCard(item, scrollEl)` 放在聊天列表顶部 | `position:sticky` + 毛玻璃，滚动后自动收缩为紧凑态；图片 `referrerpolicy=no-referrer` 绕过闲鱼图床防盗链 |
| AI 思考链 | `UI.thinking({steps, ms, streaming})` 插在 AI 回复气泡前 | 原生 `<details>` 折叠，零 JS；流式时脉冲点 |
| 下拉刷新 / 骨架屏 / Toast | `UI.pullToRefresh(el, fn)`、`.skeleton`、`UI.toast()` | `prefers-reduced-motion` 全局降级 |

接入示例（会话列表改为可左滑）：

```js
html += `
<div class="swipe-row" data-user="${esc(c.user_id)}" data-item="${esc(c.item_id)}">
  <div class="swipe-row__actions">
    <button data-act="takeover" class="bg-amber-500">人工接管</button>
    <button data-act="delete" class="bg-rose-500">删除</button>
  </div>
  <div class="swipe-row__content p-3 border border-slate-200 rounded-xl">…原卡片内容…</div>
</div>`;
UI.swipe(document.getElementById('chat-list-container'), (act, d) => {
  if (act === 'takeover') api('/api/chat/takeover', {method:'POST', headers:{'Content-Type':'application/json'},
                                body: JSON.stringify({user_id:d.user, item_id:d.item})});
});
```

订单列表同理：`补发 / 复制单号 / 转人工`，对应 `delivery_orders.state`。

思考链需要后端落库（建议 schema v3）：`messages` 增加 `meta TEXT`（JSON：`{"intent":"price","expert":"PriceAgent","floor":88,"ms":1320}`），`/api/chat/messages` 原样返回，前端：

```js
const t = m.meta ? JSON.parse(m.meta) : null;
html += isMe && t ? UI.thinking({ms: t.ms, steps: [
  {label:'意图', text:t.intent}, {label:'专家', text:t.expert}, {label:'底价', text:'¥'+t.floor}]}) : '';
```
注意 meta 里**不要**存底价以外的敏感策略原文，也不要存模型原始 thinking（可能含 prompt 内容）。

### 3.4 其他前端建议（未在本分支实现）
- Tailwind Play CDN 官方不建议生产使用：用 Tailwind CLI 预编译一次 `app.css` 提交进仓库；`lucide@latest` 锁版本或改内联 SVG sprite。
- 加 CSP：`default-src 'self'; img-src 'self' https://*.alicdn.com data:; script-src 'self'`（需先把内联脚本外移）。
- `user-scalable=no` 影响无障碍，建议只保留 `viewport-fit=cover`，用 `touch-action:manipulation` 消除双击缩放延迟（ui-kit 已加）。

## 4. 卡密池与自动发货状态机

```
PAID ──► RESERVED ──► SENT ──► CONFIRMED
  │          │          │
  │          ▼          ▼
  │     SEND_FAILED  CONFIRM_FAILED   （各自最多重试 3 次，超限 → MANUAL）
  ▼
OUT_OF_STOCK ──补货后自动回到 PAID
```

| 保证 | 实现 |
|---|---|
| 不超卖 | `BEGIN IMMEDIATE` 拿写锁 + `UPDATE card_keys ... WHERE id=(SELECT ... LIMIT 1) RETURNING`，选卡与占卡是同一条语句 |
| 一单一卡 | `UNIQUE INDEX uq_card_reserved_order ON card_keys(reserved_order)`，跨进程兜底 |
| 重复推送幂等 | `delivery_orders.order_id` 主键 + `INSERT OR IGNORE`；同订单再次 reserve 返回同一张卡 |
| 不丢卡 | 发送失败时卡保持 `reserved`，重试发**同一张**；只有确认未发出（取消/退款）才 `release()` |
| 不重复推进 | 每次迁移是 CAS：`UPDATE ... WHERE order_id=? AND state=?`，rowcount=0 即放弃；进程内再加 per-order `asyncio.Lock` |
| 不长时间持锁 | 网络调用（发消息、确认发货）全部在事务外 |
| 可恢复 | `retry_pending()` 每 30–60s 调一次，捡起中断在任何中间态的订单 |
| 旧库兼容 | 只做 `ADD COLUMN` / `CREATE IF NOT EXISTS`，`is_used=1` 自动映射为 `delivered`，可重复执行 |

接入方式（T-010）：

```python
engine = DeliveryEngine(get_db(), send_fn=live.send_text, confirm_fn=confirm_async)
# WS 收到"买家已付款"系统消息时：
await engine.on_paid(PaidOrder(order_id, buyer_id, chat_id, item_id, keyword=match_rule(item_title)))
# 后台：
while True: await engine.retry_pending(); await asyncio.sleep(30)
```
`confirm_async` 用 `asyncio.to_thread(delivery_service.confirm_dummy_shipping, order_id)` 包一层（现有实现是同步 requests）。

已知边界：
- 购买数量 >1：当前一单一卡。需要时把 `reserved_order` 存为 `order_id#1..n`，reserve 循环 n 次，全部成功才推进。
- 多实例部署：SQLite 只适合单机；上 MySQL/PG 时改为 `SELECT ... FOR UPDATE SKIP LOCKED`，状态机代码不变。

## 5. 本分支改动清单

| 文件 | 改动 |
|---|---|
| `app/db/database.py` | WAL、busy_timeout、`write_tx()`、schema v2 无损迁移、`delivery_orders` 表 |
| `app/services/card_pool.py` | 新增：原子抢占 / 幂等 / 标记已发 / 释放 / 库存 |
| `app/services/delivery_engine.py` | 新增：发货状态机 + 补偿重试 |
| `app/api/security.py` | 新增：ADMIN_TOKEN 中间件、卡密脱敏 |
| `app/api/routes.py` | 接入鉴权、收紧 CORS、prompt 白名单、卡密列表脱敏、禁删已占用卡、批量导入单事务、`/api/delivery/orders`、`/healthz` |
| `app/services/*.py` | 修正 `app.utils` 导入路径 |
| `main.py` | `load_dotenv()`、默认 `127.0.0.1`、挂载 `/static` |
| `app/static/index.html` | 引入 ui-kit、16 处 XSS 修复、`api()` 带令牌、毛玻璃 TabBar |
| `app/static/ui-kit.css/js` | 新增组件层 |
| `tests/test_delivery.py` | 7 个测试 |
| `.env.example` | `ADMIN_TOKEN` / `HOST` / `CORS_ORIGINS` |

**升级注意**：合并后必须在 `.env` 设置 `ADMIN_TOKEN`，否则只有本机能访问；手机访问需 `HOST=0.0.0.0` 并强烈建议经 HTTPS 反代。
