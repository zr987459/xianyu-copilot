import os
from dotenv import load_dotenv
load_dotenv()  # 必须早于 routes 导入：ADMIN_TOKEN / API_KEY 等在导入期读取
import uvicorn
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from app.api.routes import app

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "static")

@app.get("/")
async def read_index():
    return FileResponse(os.path.join(STATIC_DIR, "index.html"))

if __name__ == "__main__":
    port = int(os.getenv("PORT", "9005"))
    print(f"🚀 Xianyu Copilot 服务正在启动: http://0.0.0.0:{port}")
    uvicorn.run(app, host="0.0.0.0", port=port)
