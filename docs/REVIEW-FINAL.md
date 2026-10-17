# Xianyu Copilot 终审（Hark）· main @ 45ece5e

## 结论
**UI 层和卡密出库可以验收，但 main 现在跑不起来，自动发货链路也还没接通，不能发 v1.0.0。** 本分支 `hark/final-review` 修好了阻断项，合并后可以启动、可以安全地对外访问，手动和半自动发货可用。全自动发货要等 T-010（WSS 监听）完成。

## 实测结果（main）
| 项 | 结果 |
|---|---|
| `python main.py` | ❌ `ModuleNotFoundError: utils`（3 个 service 仍写 `from utils.xianyu_utils`），bot_engine 还有 `from llm_client` |
| 提交信息里的「startup runner」 | ❌ 不存在：main.py 没有启动任何监听或 worker；bot_engine / delivery_manager 没有被任何代码调用 |
| 卡密并发 | ✅ 150 线程抢 100 张卡，100 张全部不重复（BEGIN IMMEDIATE + RETURNING 有效，SQLite 3.46） |
| XSS | ✅ 13 处 innerHTML 都是静态文案，动态内容全部走 textContent；唯一的缺口是 `a.href = it.url`（未限制协议） |
| 鉴权 | ❌ 无。监听 0.0.0.0 + CORS 默认 `*`：同网段任何人都能 `GET /api/cards/list` 拿到全部卡密，或用 `POST /api/cards/claim` 随意填单号把卡池清空（已复现：匿名调用返回 3×200） |
| 空订单号 | ❌ `claim_card(kw, "")` 两次返回同一张卡 → 两个没有单号的订单会拿到同一张卡 |
| 锁超时 | ❌ delivery_manager 遇到 `database is locked` 时返回 None，被当成缺货处理，买家会收到「火速补货中」，而且之后没有任何补发 |
| 重复付款事件 | ❌ 同一订单推送两次 → 消息发两遍、确认接口调两遍（卡是同一张） |
| 日志 | ❌ `logger.info(... 卡密: {card})` 把卡密明文写进日志 |
| 订单 Tab | ❌ 「待发货/已发货」筛选按钮没有生效；失败和缺货状态也显示成绿色 |
| 可读性 | `text-slate-400` 在白底上只有 2.56:1，有 26 处 |

## 本分支改动
1. 修正导入：`app.utils.xianyu_utils`、`app.core.llm_client`。
2. `main.py`：先 `load_dotenv()`；`HOST` 默认 127.0.0.1；对外监听但没配 `ADMIN_TOKEN` 时拒绝启动。
3. `app/core/security.py`：`/api/*` 需要 `Bearer ADMIN_TOKEN`（常量时间比较）。没配口令时只放行本机直连，**带 X-Forwarded-For 的请求一律拒绝**（防 nginx / cloudflared 把服务意外暴露到公网）。CORS 不再默认 `*`。
4. 前端：给同源 `/api/*` 的 fetch 自动带上口令，遇 401 弹窗输入并存 localStorage；链接只放行 http(s)。
5. `app/db/cards.py`：唯一的取卡实现（routes 和 delivery_manager 共用）。拒绝空单号；拿不到锁抛 `StockLockError`（不再当缺货处理）；busy_timeout 调到 15s。
6. delivery_manager：没有单号的订单转人工；已发过货的订单忽略重复事件；缺货记为「缺货待补发」；日志只记单号末 4 位和卡密长度。
7. 批量导入：去掉本批重复行，改用 executemany；空关键词返回 400。
8. 订单 Tab 筛选生效（待处理 = 缺货 / 确认失败），状态按颜色区分；`slate-400` 改为 `slate-500`（4.76:1）。
9. `tests/test_cards_and_auth.py`：线程并发、4 进程并发、幂等、空单号、鉴权、反代拒绝、导入去重，共 5 项全部通过。

## 演进路线（按优先级）
- **T-010 WSS 监听器（P0，自动发货的前提）**：心跳、`_m_h5_tk` 过期刷新、断线指数退避；收到「等待卖家发货」后只做一件事：写入 orders 表（order_id 主键）。
- **T-011 订单表 + worker**：`orders(order_id PK, status, attempts, next_retry_at)`，状态流转 PAID→RESERVED→SENT→DONE；发消息、调确认接口放在事务外，失败按退避重试；补卡后自动把「缺货待补发」的订单重新放回队列。可直接复用 `hark/t-007-review` 里的 `fulfillment.py` 和它的 8 项测试。
- **T-012 实时与体验**：`/api/events`（SSE）推新消息和新订单，替掉 10 秒轮询；会话左滑「已读/删除」、订单左滑「补发/复制单号」；对话页悬浮卡加「手动发货」按钮。
- **T-013 生产化前端**：Tailwind 改为 CLI 预编译，去掉 Play CDN 运行时；lucide 锁定版本（现在是 `@latest`）；加 manifest，支持「添加到主屏幕」；去掉 `user-scalable=no`。
- **T-014 运维**：`/healthz`；已发卡禁止删除（改为作废）；每日把 `data/xianyu.db` 备份一份（卡密就是钱）。
