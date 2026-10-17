# T-015 多规格套餐（SKU）× 一次拍多件 发货引擎

- 基线：main @ ebb6d91；分支 `hark/feat-sku-and-multi-qty`
- 依赖关系：订单的规格文字 `sku_desc` 与件数 `quantity` 需要由 T-010 WSS 监听器从订单详情里取出，传给 `handle_paid_order(..., sku_desc, quantity)`。两个参数都可缺省，缺省等同旧版「一单一卡」。

## 1. 数据模型（无损迁移，`init_db()` 可重复执行）

| 表 | 变化 |
|---|---|
| `card_keys` | `+ sku TEXT NOT NULL DEFAULT ''`（旧卡全部归入默认套餐）、`+ order_line INTEGER`（订单内第几张） |
| 索引 | 删 `idx_card_order(order_id)` 唯一索引（它让一单只能绑一张卡）→ 建 `idx_card_order_line(order_id, order_line)` 唯一；取卡索引改为 `(keyword, sku, is_used, id)` |
| `delivery_rule_skus`（新） | `rule_id, sku_pattern, card_sku, delivery_content, use_card_pool, enabled`，`UNIQUE(rule_id, sku_pattern)` |
| `delivery_logs` | `+ item_title, sku_desc, card_sku, quantity`，供补货后自动重发 |

规则 = 主关键词（匹配商品标题）+ 0..N 个细分套餐（匹配订单规格）。**没配套餐的规则行为与旧版完全一致。**

## 2. 套餐匹配（`app/services/delivery_plan.py`）
1. 规格按 `; ， / 空格` 切段、取冒号后的值，与 `sku_pattern` **整段相等**优先 —— 「半年卡」不会被「年卡」抢走
2. 其次取被规格文字包含的最长 `sku_pattern`；同层命中多个互不包含的套餐（「专业版」+「年卡」）视为歧义
3. 都没有时用 `*` 兜底套餐（需主人显式配置）
4. 仍然没有 / 歧义 → **转人工（`待人工:套餐未匹配`），绝不发错档位**

全角半角、空白统一归一化（`ＰＲＯ`=`PRO`，`年 卡`=`年卡`），比较不区分大小写。

## 3. 原子批量出库 `claim_cards(db, keyword, order_id, quantity=1, sku="")`
- 单个 `BEGIN IMMEDIATE` 事务：先查该订单已绑的卡 → 数库存 → 循环 `UPDATE … RETURNING` 占卡，编号 1..N
- **幂等**：同一 `order_id` 再调用返回同一批卡，编号不变，不多占
- **全有或全无（默认）**：库存不足 N 张时一张都不占，返回 `complete=False, available=剩余`。理由：闲鱼「确认发货」是整单动作，先发 2/5 张会让订单卡在已发未确认、买家纠纷
- `allow_partial=True` 可先占可用的，补货后再次调用从第 len+1 张续上（API 层暂不开放）
- 数量必须是 1~50 的整数（`MAX_QUANTITY`），超过转人工，防异常数量一把清空卡池
- 拿不到锁抛 `StockLockError`，不当缺货处理
- `claim_card()` 保留为单张兼容入口（走默认套餐）

## 4. 发货流程（`AutoDeliveryManager.handle_paid_order`）
已发过 → 忽略 ｜ 匹配规则 ｜ 解析套餐（失败转人工）｜ 出库 N 张（不足 → 给买家发一次补货通知，记 `缺货待补发`，不确认发货）｜ 排版 → 私信 → 确认发货（`asyncio.to_thread`，不再阻塞事件循环）→ 记日志

- 同一订单的并发重复推送用 per-order `asyncio.Lock` 串行，5 路并发只发 1 条
- `retry_pending()`：把「缺货待补发 / 待人工」且还没发出的订单重新走一遍；导入卡密后或定时调用

### 私信样例
```
【自动发货】亲，感谢您的购买！
规格：年卡 × 3

【下载与使用说明】
……

【您的专属卡密】共 3 张，每张仅限使用一次
① AAAA-1111
② BBBB-2222
③ CCCC-3333

如有任何问题随时发消息联系，祝您生活愉快！
```
多行卡密续行对齐缩进；超过 900 字（保守值，闲鱼未公开上限）按卡密边界拆成多条「（1/3）…」，单张卡密不会被拆断；⑳ 之后用 `21.`。

## 5. API
| 接口 | 变化 |
|---|---|
| `POST /api/cards/add`、`/api/cards/import` | 可选 `sku` |
| `GET /api/cards/list` | 可选 `sku`（空串 = 只看默认套餐） |
| `GET /api/cards/skus?keyword=` | 新增：关键词 × 套餐库存分布 |
| `POST /api/delivery/rules` | 可选 `variants:[{sku_pattern, card_sku, delivery_content, use_card_pool}]`；改用 UPSERT，规则 id 不再变化 |
| `GET /api/delivery/rules` | 每条带 `variants`（含各套餐剩余）与 `sku_stock` |
| `POST /api/cards/claim` | 可选 `sku`、`quantity`；返回 `cards[]`，不足返回 409 且不占卡 |

## 6. 移动端
- 录入卡密：新增「套餐 / 规格」输入框，按关键词联想已有套餐
- 卡密明细：顶部按关键词显示各套餐「在库/总数」胶囊，缺货标红；每张卡显示套餐标签、多件订单显示「第 N 张」
- 发货规则：新增「细分套餐」多行输入（`匹配词 = 出库套餐 | 专属说明`），卡片上展示「规格 → 卡池（剩余）· 专属说明」映射

## 7. 未做 / 风险
- 订单详情里 `sku_desc`、`quantity` 的真实字段名未抓包确认，需在 T-010 监听器里对接；未做真实闲鱼联调
- 私信长度上限 900 字是保守假设
- 多实例部署需换 MySQL/PostgreSQL 行锁；当前单机 SQLite
