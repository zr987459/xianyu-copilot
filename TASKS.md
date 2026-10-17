# 开发任务清单 (TASKS)

- [x] T-001: 项目工程初始化与双智能体 GitOps 规范注入 (AGENTS.md / docs)
- [x] T-002: 后端轻量架构设计（FastAPI + Async SQLite + 状态监控）
- [x] T-003: 移植闲鱼官方无需物流虚拟发货接口与自动确认机制
- [x] T-004: 实现卡密池一卡一发独享核销引擎与复合发货模式（网盘+卡密双发）
- [x] T-005: 移植闲鱼流式图片上传与免浏览器单品上架发布协议
- [x] T-006: 打造对齐 xianyu-mobile 风格的 4 大 Tab 移动端高颜值 Web/APP 界面
- [x] T-007: 委托 Hark 进行代码架构、UI组件体系与移动端原生交互审查（见 docs/review/T-007-hark-review.md，补丁在 review/hark-t007）
- [ ] T-008: 审阅并合并 review/hark-t007（P0 安全修复 + 发货状态机 + ui-kit），配置 ADMIN_TOKEN 后发布 Release v1.0.0
- [ ] T-009: 订单监听：WS 识别"买家已付款"系统消息 → 解析 order_id/item_id → `DeliveryEngine.on_paid`；后台 30s 调 `retry_pending()`
- [ ] T-010: 会话/订单列表接入 `UI.swipe`（人工接管/删除；补发/复制单号/转人工），聊天抽屉换 `UI.sheet` + `UI.itemCard`
- [ ] T-011: schema v3：`messages.meta` 落库 AI 意图/专家/耗时，前端 `UI.thinking` 展示
- [ ] T-012: `/api/events` SSE 实时推送 + `visibilitychange` 补拉，替代手动刷新
- [ ] T-013: PWA（manifest + Service Worker）；Tailwind 预编译、lucide 锁版本、CSP
- [ ] T-014: 购买数量 >1 的多卡发货（`reserved_order = order_id#n`）
