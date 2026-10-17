import os
import requests
from typing import List, Dict, Optional
from loguru import logger

class UnifiedLLMClient:
    """统一兼容 OpenAI 与 Anthropic Messages 协议的客户端"""

    def __init__(self):
        self.api_type = os.getenv("API_TYPE", "openai").lower().strip()
        self.api_key = os.getenv("API_KEY", "").strip()
        self.base_url = os.getenv("MODEL_BASE_URL", "").rstrip("/")
        self.model_name = os.getenv("MODEL_NAME", "gemini-3.8-flash-fast")
        self.mode = "anthropic" if self.api_type in ["anthropic", "claude", "anthropic-messages"] else "openai"
        logger.info(f"LLM 客户端已初始化: {self.mode} 模式 (模型: {self.model_name})")

    def call(self, messages: List[Dict], temperature: float = 0.4, max_tokens: int = 500) -> str:
        """统一调用接口"""
        if self.mode == "anthropic":
            system_prompt = ""
            chat_messages = []
            for msg in messages:
                if msg["role"] == "system":
                    if system_prompt:
                        system_prompt += "\n\n" + msg["content"]
                    else:
                        system_prompt = msg["content"]
                elif msg["role"] in ["user", "assistant"]:
                    chat_messages.append({"role": msg["role"], "content": msg["content"]})

            if not chat_messages:
                chat_messages = [{"role": "user", "content": "你好"}]

            url = f"{self.base_url}/v1/messages" if not self.base_url.endswith("/v1/messages") else self.base_url
            headers = {
                "x-api-key": self.api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json"
            }
            payload = {
                "model": self.model_name,
                "max_tokens": max_tokens,
                "temperature": temperature,
                "messages": chat_messages
            }
            if system_prompt:
                payload["system"] = system_prompt

            resp = requests.post(url, headers=headers, json=payload, timeout=60)
            if resp.status_code != 200:
                logger.error(f"Anthropic API 请求失败 [{resp.status_code}]: {resp.text}")
                return "抱歉，正在忙，请稍后。"
            res_data = resp.json()
            # 兼容 thinking 思考链返回，过滤提取 text 类型的 block
            content_list = res_data.get("content", [])
            reply_texts = [block.get("text", "") for block in content_list if block.get("type") == "text"]
            if reply_texts:
                return "\n".join(reply_texts).strip()
            # 兜底
            return str(content_list)
        else:
            from openai import OpenAI
            kwargs = {"api_key": self.api_key}
            if self.base_url:
                kwargs["base_url"] = self.base_url
            client = OpenAI(**kwargs)
            res = client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                top_p=0.8,
            )
            return res.choices[0].message.content
