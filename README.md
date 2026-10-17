# 🐟 Xianyu Copilot (闲鱼智能协同与全自动化运营系统)

[![GitHub stars](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Python Version](https://img.shields.io/badge/python-3.10%2B-blue.svg)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.115%2B-009688.svg)](https://fastapi.tiangolo.com/)
[![Tailwind CSS](https://img.shields.io/badge/TailwindCSS-Mobile%20First-38B2AC.svg)](https://tailwindcss.com/)

> 专为闲鱼个人卖家与店主打造的全自动化协同与运营管家。深度对标 `zhinianboke/xianyu-auto-reply` 与 `xianyu-mobile`，提供高颜值移动端 UI、多专家 AI 阶梯砍价客服、复合模式自动发货（网盘链接+独立卡密池双发）以及免浏览器单品一键上架发布。

---

## 🌟 核心特性

### 1. 📱 深度对标 xianyu-mobile 的移动端 UI
- **4 大标准底部导航 Tab**：
  - **消息 (Messages)**：实时会话列表、多轮人机交互气泡、议价轮次标记与搜索。
  - **订单 (Orders)**：订单状态流转（待付款 / 待发货 / 已发货）、单号复制与快速查验。
  - **商品与发货 (Products & Delivery)**：
    - **卡密资产池**：支持**单码快捷录入**与**多行批量导入**，实时追踪每一张卡密的状态（🟢 在库就绪 vs 🔴 已发放核销及对应订单号）。
    - **复合发货规则**：支持同时下发固定内容（如网盘链接、解压密码、安装教程）+ 抽取一张独享卡密。
    - **一键上架发布**：流式上传图片至阿里官方图床，免浏览器调用发布接口。
  - **我的设置 (Mine & Settings)**：账号保鲜状态、4 大核心指标卡片、AI 专家 Prompt 在线热更新。

### 2. 🤖 多专家 Agent 协同砍价与客服
- **多模型协议中立**：原生支持 Anthropic-messages 与 OpenAI 规范，支持提取并过滤 `thinking` 思维链。
- **阶梯博弈**：根据砍价轮次动态调节温度与让步幅度，死守利润底线。
- **一键人工接管**：闲鱼私聊发送句号 `。` 即可随时在 AI 值守与真人代管之间无缝切换。

### 3. ⚡ 官方协议无需物流自动发货
- 毫秒级监听订单付款事件。
- 自动调用官方 `mtop.taobao.idle.logistic.consign.dummy/1.0/` 接口确认发货。
- 发货日志全流程追踪，卡密绝不重复发放。

---

## 🚀 极速启动

### 环境要求
- Python 3.10+
- 手机端 Termux / PRoot Linux 或 任何 Linux 云服务器均可秒级启动

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
