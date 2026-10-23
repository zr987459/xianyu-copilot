# 🐟 Xianyu Copilot (闲鱼智能协同与全自动化运营系统)

[![GitHub stars](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android App](https://img.shields.io/badge/Android-Standalone%20APK-3DDC84.svg?logo=android&logoColor=white)](android/)
[![Python Version](https://img.shields.io/badge/python-3.10%2B-blue.svg)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.115%2B-009688.svg)](https://fastapi.tiangolo.com/)
[![Tailwind CSS](https://img.shields.io/badge/TailwindCSS-Mobile%20First-38B2AC.svg)](https://tailwindcss.com/)

> 专为闲鱼个人卖家与店主打造的全自动化协同与运营管家。深度对标 `zhinianboke/xianyu-auto-reply` 与 `xianyu-mobile`，提供高颜值移动端 UI、复合模式自动发货（网盘链接+独立卡密池双发）、免浏览器单品一键上架发布，并支持**纯安卓独立版 APK（后台常驻守护，无需命令行）**！

---

## 🌟 核心特性

### 1. 📱 纯安卓独立版 APK (android/)
- **免启动、零命令行**：无需 Termux，无需 PRoot，无需在电脑或终端敲命令，直接安装 APK 即可在手机独立运行；
- **常驻后台守护服务 (Foreground DaemonService)**：系统通知栏常驻小图标，24 小时后台循环监测待发货订单，点击随时唤醒控制台；
- **同源嵌入式引擎**：基于 Kotlin + OkHttp3 + ServerSocket 内嵌运行于 `http://127.0.0.1:9005`，零跨域、零文件协议限制；
- **永久固定签名证书**：采用固定密钥打包，后续更新升级支持平滑无感覆盖安装。

### 2. 🎫 卡密资产池与原子防超卖出库
- **单码录入与批量导入**：支持区分套餐/规格 (SKU)，卡密库存状态清晰（在库就绪 vs 已发放核销及关联订单号）；
- **Hark 架构审查加固**：采用 `BEGIN IMMEDIATE` 写锁与单语句 CAS 抢占（`UPDATE ... WHERE id=(SELECT id ... LIMIT 1) AND is_used=0`），从根源杜绝高并发超卖；
- **全链路幂等**：订单重复推送或网络重试自动返回已绑定卡密，绝不二次克隆出库。

### 3. ⚡ 官方协议无需物流自动发货
- 自动监听「买家已付款 / 等待发货」订单；
- 复合发货规则：支持同时下发固定内容（如网盘链接、激活说明、解压密码）+ 抽取一张独享卡密；
- 自动调用官方 `mtop.taobao.idle.logistic.consign.dummy/1.0/` 接口确认发货并自动留档。

### 4. 🚀 免浏览器一键上架发布
- 免打开淘宝或闲鱼 App，在控制台输入标题、售价、原价与宝贝描述，直接调用发布协议一键上架至店铺。

### 5. 💬 消息中枢与自动回复（v1.5.0）
- **IM 长连接**：多账号同时在线，实时接收买家消息，会话列表 / 聊天记录 / 未读角标，App 内直接回复；
- **自动回复**：关键词（包含 / 精确 / 按商品）、商品专属回复、默认回复、敏感词过滤、防抖与冷却、人工接管自动暂停；
- **AI 客服与议价**：OpenAI 兼容（DeepSeek / 通义 / Kimi …）与 Anthropic Claude，可设最大优惠、议价轮数与单品底价。

### 6. 📦 发货增强、订单与商品管理（v1.5.0）
- IM「已付款」消息秒级触发发货，订单轮询兜底，统一幂等任务表防重复；延时发货、发货模板变量、卡密私信买家、低库存预警；
- 订单中心（状态 / 来源 / 发货进度 / 补发）；商品擦亮（含每日定时）、改价、下架、重新上架；采集复制闲鱼商品到发布页。

### 7. 🔔 通知与看板（v1.5.0）
- 企业微信、钉钉、飞书、Bark、Telegram、自定义 Webhook 与本机通知，按事件订阅（新消息 / 新订单 / 发货结果 / 库存 / 账号失效）；
- 首页看板：今日消息、订单、成功发货、营收与 IM 连接状态。

> **需要配置**：AI 回复需在「功能 → AI 客服」填入 API Key；外部推送需在「功能 → 通知渠道」添加 Webhook。所有密钥只保存在本机（Android Keystore 加密）。

---

## 🚀 两种使用方式

### 方式一：手机直接安装纯安卓独立版 (推荐)
前往 [Releases 页面](https://github.com/zr987459/xianyu-copilot/releases) 下载 `XianyuCopilot.apk`，安装后打开即可使用，后台常驻守护，零外部依赖！

### 方式二：Python 服务端运行 (Docker / Linux 云服务器)

### 安装与运行
```bash
# 1. 克隆仓库
git clone https://github.com/zr987459/xianyu-copilot.git
cd xianyu-copilot

# 2. 安装依赖
pip install -r requirements.txt

# 3. 配置环境变量
cp .env.example .env
# 编辑 .env 填入您的大模型 API Key 与闲鱼 Cookie

# 4. 启动服务
python main.py
```
启动后在手机或电脑浏览器访问：`http://127.0.0.1:9005` 即可打开移动端管理控制台！

---

## 📂 项目结构
```text
xianyu-copilot/
├── app/
│   ├── api/routes.py         # FastAPI 业务接口 (Stats/Chats/Cards/Rules/Publish)
│   ├── core/llm_client.py    # OpenAI & Anthropic 双协议大模型适配器
│   ├── db/database.py        # SQLite 持久化层
│   ├── services/             # 官方无物流发货、流式上架、WebSocket 核心服务
│   ├── static/index.html     # 高颜值响应式移动端 Web App (Tailwind CSS)
│   └── utils/                # 闲鱼签名加密算法与工具类
├── docs/                     # PRD 与架构设计文档
├── prompts/                  # 阶梯砍价、意图识别、日常客服提示词库
├── main.py                   # 服务入口
├── requirements.txt
└── README.md
```

## 📜 许可证
本项目采用 MIT 许可证开源。

### 致谢
IM 长连接协议、消息解析、AI 议价与部分商品接口移植 / 参考自以下 MIT 项目：
[zhinianboke/xianyu-auto-reply](https://github.com/zhinianboke/xianyu-auto-reply)、[shudaxiag/xianyu-guanjia](https://github.com/shudaxiag/xianyu-guanjia)。
