import time
import uuid
import json
from io import BytesIO
import requests
from PIL import Image
from loguru import logger
from utils.xianyu_utils import generate_sign

class XianyuPublisherService:
    """闲鱼商品上架发布服务 (移植自成熟开源项目方案)"""

    IMAGE_UPLOAD_URL = "https://stream-upload.goofish.com/api/upload.api?floderId=0&appkey=fleamarket&_input_charset=utf-8"
    PUBLISH_API = "mtop.idle.pc.idleitem.publish"
    PUBLISH_URL = "https://h5api.m.goofish.com/h5/mtop.idle.pc.idleitem.publish/1.0/"

    def __init__(self, session, cookie_str):
        self.session = session
        self.cookie_str = cookie_str

    def upload_image(self, image_bytes: bytes, filename: str = "upload.jpg") -> dict:
        """上传图片到闲鱼官方图床"""
        with Image.open(BytesIO(image_bytes)) as img:
            width, height = img.width, img.height

        headers = {
            "Accept": "*/*",
            "Cookie": self.cookie_str,
            "Origin": "https://seller.goofish.com",
            "Referer": "https://seller.goofish.com/?site=COMMONPRO",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "X-Requested-With": "XMLHttpRequest",
        }

        files = {
            "file": (filename, image_bytes, "image/jpeg")
        }

        resp = requests.post(self.IMAGE_UPLOAD_URL, headers=headers, files=files, timeout=30)
        res_json = resp.json()
        if not res_json.get("success") or not res_json.get("object", {}).get("url"):
            raise RuntimeError(f"图片上传失败: {res_json}")

        uploaded = res_json["object"]
        return {
            "extraInfo": {"isH": "false", "isT": "false", "raw": "false"},
            "isQrCode": False,
            "url": str(uploaded["url"]),
            "heightSize": height,
            "widthSize": width,
            "major": False,
            "type": 0,
            "status": "done"
        }

    def publish_item(self, title: str, description: str, price: float, original_price: float = None, image_urls_or_bytes: list = None):
        """发布一件商品到闲鱼"""
        if not original_price:
            original_price = price

        # 处理图片
        image_list = []
        for idx, item in enumerate(image_urls_or_bytes or []):
            if isinstance(item, bytes):
                img_obj = self.upload_image(item)
            elif isinstance(item, str) and item.startswith("http"):
                r = requests.get(item, timeout=15)
                img_obj = self.upload_image(r.content)
            else:
                continue
            img_obj["major"] = (idx == 0)
            image_list.append(img_obj)

        if not image_list:
            # 如果没有传图片，生成一张精美默认展品图
            canvas = Image.new('RGB', (800, 800), color=(245, 245, 245))
            buf = BytesIO()
            canvas.save(buf, format='JPEG')
            img_obj = self.upload_image(buf.getvalue())
            img_obj["major"] = True
            image_list.append(img_obj)

        params = {
            'jsv': '2.7.2',
            'appKey': '34839810',
            't': str(int(time.time()) * 1000),
            'sign': '',
            'v': '1.0',
            'type': 'originaljson',
            'accountSite': 'xianyu',
            'dataType': 'json',
            'timeout': '20000',
            'api': self.PUBLISH_API,
            'sessionOption': 'AutoLoginOnly',
            'spm_cnt': 'a21ybx.publish.0.0'
        }

        payload = {
            "freebies": False,
            "itemTypeStr": "b",
            "quantity": "1",
            "simpleItem": "true",
            "imageInfoDOList": image_list,
            "itemTextDTO": {
                "title": title,
                "desc": description
            },
            "itemLabelExtList": [],
            "itemPriceDTO": {
                "origPriceInCent": str(round(original_price * 100)),
                "priceInCent": str(round(price * 100)),
            },
            "userRightsProtocols": [],
            "itemPostFeeDTO": {
                "canFreeShipping": True,
                "supportFreight": True,
                "onlyTakeSelf": False
            },
            "itemAddrDTO": {
                "divisionId": "310100", # 默认上海市行政区划
                "gps": "31.230416,121.473701"
            },
            "defaultPrice": False,
            "itemCatDTO": {
                "catId": "",
                "catName": "",
                "channelCatId": "",
                "tbCatId": ""
            },
            "uniqueCode": f"{int(time.time() * 1000)}",
            "sourceId": "pcMainPublish",
            "bizcode": "pcMainPublish",
            "publishScene": "pcMainPublish",
        }

        data_val = json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
        data = {'data': data_val}

        token = self.session.cookies.get('_m_h5_tk', '').split('_')[0]
        sign = generate_sign(params['t'], token, data_val)
        params['sign'] = sign

        headers = {
            "Host": "h5api.m.goofish.com",
            "Origin": "https://www.goofish.com",
            "Referer": "https://www.goofish.com/publish",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "Content-Type": "application/x-www-form-urlencoded",
        }

        logger.info(f"🚀 正在调用闲鱼发布商品接口: 《{title}》 售价: ¥{price}")
        resp = self.session.post(self.PUBLISH_URL, headers=headers, params=params, data=data, timeout=20)
        res_json = resp.json()
        logger.info(f"闲鱼商品发布返回结果: {res_json}")
        
        ret_msg = res_json.get('ret', ['未知结果'])[0] if res_json.get('ret') else '未知结果'
        if 'SUCCESS' in ret_msg or '调用成功' in ret_msg:
            # 提取 itemId
            item_id = res_json.get('data', {}).get('itemId') or res_json.get('data', {}).get('id')
            return True, {
                "item_id": item_id,
                "url": f"https://www.goofish.com/item?id={item_id}" if item_id else None,
                "message": ret_msg
            }
        else:
            return False, {"message": ret_msg, "raw": res_json}
