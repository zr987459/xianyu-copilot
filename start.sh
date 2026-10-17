#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"

echo "=== 🐟 启动 Xianyu Copilot 智能运营与协同控制台 ==="

# 检查虚拟环境
if [ ! -d "venv" ]; then
    echo "创建 Python 虚拟环境..."
    python3 -m venv venv
    ./venv/bin/pip install --upgrade pip
    ./venv/bin/pip install -r requirements.txt
fi

export PORT=${PORT:-9005}
echo "控制台界面启动于: http://127.0.0.1:${PORT}"
./venv/bin/python3 main.py
