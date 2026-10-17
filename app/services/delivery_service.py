import time
import json
import requests
from loguru import logger
from utils.xianyu_utils import generate_sign

class XianyuDeliveryService:
    """闲鱼自动发货与确认服务"""

    CONFIRM_API_URL = "https://h5api.m.goofish.com/h5/mtop.taobao.idle.logistic.consign.dummy/1.0/"
    CONFIRM_API_NAME = "mtop.taobao.idle.logistic.consign.dummy"

    def __init__(self, session):
        self.session = session

    def confirm_dummy_shipping(self, order_id: str, trade_text: str = "自动发货已完成"):
        """
        调用官方无需物流（虚拟商品）发货接口进行发货确认
        """
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
            'api': self.CONFIRM_API_NAME,
            'sessionOption': 'AutoLoginOnly',
        }
        
        data_val = json.dumps({
            "orderId": str(order_id),
            "tradeText": trade_text,
            "picList": [],
            "newUnconsign": True
        }, ensure_ascii=False, separators=(',', ':'))
        
        data = {'data': data_val}
        
        token = self.session.cookies.get('_m_h5_tk', '').split('_')[0]
        sign = generate_sign(params['t'], token, data_val)
        params['sign'] = sign

        headers = {
            "Host": "h5api.m.goofish.com",
            "sec-ch-ua-platform": "\"Windows\"",
            "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
            "accept": "application/json",
            "content-type": "application/x-www-form-urlencoded",
            "origin": "https://www.goofish.com",
            "referer": "https://www.goofish.com/",
        }

        try:
            resp = self.session.post(self.CONFIRM_API_URL, headers=headers, params=params, data=data, timeout=15)
            res_json = resp.json()
            ret_msg = res_json.get('ret', ['未知结果'])[0] if res_json.get('ret') else '未知结果'
            if 'SUCCESS' in ret_msg or '调用成功' in ret_msg:
                logger.info(f"✅ 闲鱼订单 {order_id} 自动确认发货成功")
                return True, ret_msg
            elif 'ORDER_ALREADY_DELIVERY' in ret_msg or '已发货' in ret_msg:
                logger.info(f"✅ 闲鱼订单 {order_id} 已经发货过，无需重复发货")
                return True, ret_msg
            else:
                logger.warning(f"❌ 闲鱼订单 {order_id} 确认发货返回: {ret_msg}")
                return False, ret_msg
        except Exception as e:
            logger.error(f"确认发货请求异常: {e}")
            return False, str(e)
