import os
from dotenv import load_dotenv

load_dotenv()  # 必须在导入 app 之前：ADMIN_TOKEN / API_KEY / COOKIES_STR 都从 .env 读取

import uvicorn
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from app.api.routes import app

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "static")

@app.get("/")
async def read_index():
    return FileResponse(os.path.join(STATIC_DIR, "index.html"))

if __name__ == "__main__":
    port = int(os.getenv("COPILOT_PORT", "9005"))
    host = os.getenv("HOST", "0.0.0.0")
    print(f"🚀 Xianyu Copilot 服务正在启动: http://0.0.0.0:{port}")
    uvicorn.run(app, host=host, port=port)
