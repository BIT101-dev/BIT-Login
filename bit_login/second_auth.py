import base64
import hashlib
import random
import string


class SecondAuthMethod:
    name = ""

    def __init__(self, flow):
        self.flow = flow


class SmsSecondAuth(SecondAuthMethod):
    name = "sms"

    def send_code(self):
        state = self.flow.require_state()
        response = self.flow.request(
            "POST",
            "/api/protected/sms/publicNoToken/sendSmsCode",
            json={"phone": state["phone"], "businessNo": "0008"},
        )
        data = self.flow.response_json(response, "短信验证码发送失败")
        if data.get("code") != 200:
            detail = data.get("data") or {}
            self.flow.fail("短信验证码发送失败", detail, data)
        return data

    def verify_code(self, code):
        state = self.flow.require_state()
        response = self.flow.request(
            "POST",
            "/api/protected/sms/checkTokenResult",
            json={"phone": state["phone"], "token": str(code), "delete": False},
        )
        data = self.flow.response_json(response, "短信验证码校验失败")
        if data.get("code") != 200:
            detail = data.get("data") or {}
            self.flow.fail("短信验证码校验失败", detail, data)
        return self.flow.complete({
            "username": state["phone"],
            "password": str(code),
            "type": "smsLogin",
            "_eventId": "submit",
            "execution": state["execution"],
            "geolocation": "",
            "captcha_code": "",
        })


class DingtalkQrSecondAuth(SecondAuthMethod):
    name = "dingtalk_qr"

    def begin(self):
        state = self.flow.require_state()
        response = self.flow.request("GET", "/api/protected/qrlogin/loginid")
        data = self.flow.response_json(response, "钉钉二维码创建失败")
        if data.get("code") != 200 or not data.get("data"):
            raise self.flow.error("钉钉二维码创建失败: {}".format(data.get("code")))
        state["dingtalk_login_id"] = data["data"]
        qr_url = self.flow.api_url + "/api/public/qrlogin/qrgen/{}/dingDingQr".format(data["data"])
        qr_response = self.flow.session.get(qr_url, headers={"Referer": state["page_url"]})
        content_type = qr_response.headers.get("Content-Type", "")
        if (
            qr_response.status_code != 200
            or not qr_response.content
            or not content_type.lower().startswith("image/")
        ):
            raise self.flow.error("钉钉二维码下载失败: HTTP {}".format(qr_response.status_code))
        return {
            "login_id": data["data"],
            "qr_url": qr_url,
            "qr_content": qr_response.content,
            "qr_content_type": content_type,
            "status": "waiting",
        }

    def poll(self):
        state = self.flow.require_state()
        login_id = state.get("dingtalk_login_id")
        if not login_id:
            raise self.flow.error("请先调用 begin_dingtalk_qr() 创建二维码")
        response = self.flow.request("GET", "/api/protected/qrlogin/scan/{}".format(login_id))
        data = self.flow.response_json(response, "钉钉扫码状态查询失败")
        if data.get("code") != 200:
            return {"status": "waiting", "code": data.get("code")}
        return self.flow.complete({
            "username": data.get("data", ""),
            "type": "dingDingQr",
            "_eventId": "submit",
            "execution": state["execution"],
            "geolocation": "",
        })


class SecondAuthFlow:
    method_classes = (SmsSecondAuth, DingtalkQrSecondAuth)

    def __init__(self, session, base_url, api_url, error, complete):
        self.session = session
        self.base_url = base_url
        self.api_url = api_url.rstrip("/")
        self.error = error
        self._complete = complete
        self.state = None
        self.methods = {}
        for method_class in self.method_classes:
            self.register_method(method_class)

    def register_method(self, method_class):
        if not method_class.name:
            raise ValueError("二次认证方式必须定义 name")
        self.methods[method_class.name] = method_class(self)

    def start(self, state):
        self.state = state

    def require_state(self):
        if not self.state:
            raise self.error("当前没有待完成的二次认证")
        return self.state

    def describe(self):
        state = self.require_state()
        return {"required": True, "methods": list(self.methods), "phone": state["masked_phone"]}

    def method(self, name):
        self.require_state()
        try:
            return self.methods[name]
        except KeyError:
            raise self.error("不支持的二次认证方式: {}".format(name))

    def request(self, method, path, **kwargs):
        headers = kwargs.pop("headers", {})
        headers.update(self._csrf_headers())
        headers.setdefault("Accept-Language", "zh-CN,zh")
        headers.setdefault("sid-language", "zh_CN")
        return self.session.request(method, self.api_url + path, headers=headers, **kwargs)

    def response_json(self, response, message):
        try:
            return response.json()
        except ValueError:
            raise self.error("{}: HTTP {}".format(message, response.status_code))

    def fail(self, message, detail, data):
        raise self.error("{}: {}".format(
            message,
            detail.get("errorMessage") or detail.get("errorCode") or data.get("code"),
        ))

    def complete(self, form):
        state = self.require_state()
        result = self._complete(form, state)
        self.state = None
        return result

    @staticmethod
    def _csrf_headers():
        key = "".join(random.choice(string.ascii_letters + string.digits) for _ in range(32))
        encoded = base64.b64encode(key.encode("ascii")).decode("ascii")
        middle = len(encoded) // 2
        value = encoded[:middle] + encoded + encoded[middle:]
        return {"Csrf-Key": key, "Csrf-Value": hashlib.md5(value.encode("ascii")).hexdigest()}
