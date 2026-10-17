import os
from dotenv import load_dotenv

load_dotenv()  # 必须在导入 app 之前：ADMIN_TOKEN / API_KEY 等都从 .env 读取

import uvicorn
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from app.api.routes import app

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "static")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

@app.get("/")
async def read_index():
    return FileResponse(os.path.join(STATIC_DIR, "index.html"))

if __name__ == "__main__":
    port = int(os.getenv("PORT", "9005"))
    # 默认只监听本机；要在局域网/公网访问，必须同时设置 HOST 与 ADMIN_TOKEN
    host = os.getenv("HOST", "127.0.0.1")
    if host not in ("127.0.0.1", "localhost", "::1") and not os.getenv("ADMIN_TOKEN", "").strip():
        raise SystemExit("拒绝启动：对外监听必须先在 .env 配置 ADMIN_TOKEN")
    print(f"🚀 Xianyu Copilot 服务正在启动: http://{host}:{port}")
    uvicorn.run(app, host=host, port=port)
