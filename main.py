import os
import uvicorn
from dotenv import load_dotenv

load_dotenv()  # 必须在导入 routes 之前，确保 ADMIN_TOKEN 等配置生效
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
    print(f"🚀 Xianyu Copilot 服务正在启动: http://{os.getenv('HOST', '127.0.0.1')}:{port}")
    uvicorn.run(app, host=os.getenv("HOST", "127.0.0.1"), port=port)
