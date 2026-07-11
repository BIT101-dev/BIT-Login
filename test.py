import argparse
import os
import tempfile
import time
import webbrowser

import bit_login
from dotenv import load_dotenv


load_dotenv()

DEFAULT_SERVICES = (
    "webvpn",
    "jwb",
    "jwb_cjd",
    "jxzxehall",
    "ibit",
    "yanhekt",
    "library",
)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def login_service(login_cls, username, password):
    client = login_cls()
    try:
        return client.login(username=username, password=password)
    except bit_login.second_auth_required:
        challenge = client.get_second_auth()
        print("需要二次认证，绑定手机：{}".format(challenge.get("phone", "未知")))
        method = os.getenv("BIT_SECOND_AUTH", "").strip().lower()
        while method not in ("sms", "dingtalk"):
            method = input("选择验证方式 [sms/dingtalk]: ").strip().lower()

        if method == "sms":
            client.send_sms_code()
            code = os.getenv("BIT_SMS_CODE", "").strip()
            if not code:
                code = input("请输入短信验证码: ").strip()
            return client.verify_sms_code(code)

        qr = client.begin_dingtalk_qr()
        suffix = ".svg" if "svg" in qr["qr_content_type"].lower() else ".png"
        qr_file = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
        qr_file.write(qr["qr_content"])
        qr_file.close()
        print("请使用钉钉扫描二维码：{}".format(qr_file.name))
        webbrowser.open("file://" + qr_file.name)
        while True:
            result = client.poll_dingtalk_qr()
            if not isinstance(result, dict) or result.get("status") != "waiting":
                os.unlink(qr_file.name)
                return client
            time.sleep(1)


def test_webvpn(username, password):
    client = login_service(bit_login.webvpn_login, username, password)
    response = client.get_session().get(
        "https://webvpn.bit.edu.cn/connection/log?page=1&limit=10"
    )
    response.raise_for_status()
    require(response.json().get("Message") == "获取成功", "WebVPN API 未返回成功状态")


def test_jwb(username, password):
    client = login_service(bit_login.jwb_login, username, password)
    scores = bit_login.jwb.score(client.get_session()).get_all_score()
    require(isinstance(scores, list), "教务系统成绩接口未返回列表")
    total_credit = sum(float(item["credit"]) for item in scores if item.get("credit"))
    print("总学分: {}".format(total_credit))


def test_jwb_cjd(username, password):
    client = login_service(bit_login.jwb_cjd_login, username, password)
    transcript = bit_login.jwb.cjd(client.get_session()).get_cjd()
    require(transcript is not None, "成绩单接口未返回数据")


def test_jxzxehall(username, password):
    client = login_service(bit_login.jxzxehall_login, username, password)
    credit = bit_login.jxzxehall.credit(client.get_session()).get_credit()
    require("total_credit" in credit, "教学中心学分接口缺少 total_credit")


def test_ibit(username, password):
    client = login_service(bit_login.ibit_login, username, password)
    response = client.get_session().get(
        "https://ibit.yanhekt.cn/proxy/v1/user?with_desensitize=false"
    )
    response.raise_for_status()
    require(response.json().get("code") == 0, "iBIT 用户接口未返回成功状态")


def test_yanhekt(username, password):
    client = login_service(bit_login.yanhekt_login, username, password)
    response = client.get_session().get("https://cbiz.yanhekt.cn/v1/user")
    response.raise_for_status()
    require(response.json().get("code") == 0, "延河课堂用户接口未返回成功状态")


def test_library(username, password):
    client = login_service(bit_login.library_login, username, password)
    result = client.get_result()
    require(result.get("cookie_json"), "图书馆登录未返回 Cookie")
    require(result.get("token"), "图书馆登录未返回 Token")


TESTS = {
    "webvpn": ("WEBVPN", test_webvpn),
    "jwb": ("JWB (教务系统)", test_jwb),
    "jwb_cjd": ("JWB_CJD (教务系统-成绩单)", test_jwb_cjd),
    "jxzxehall": ("JXZXEHALL (教学中心/一站式大厅)", test_jxzxehall),
    "ibit": ("IBIT (iBIT 手机端聚合页)", test_ibit),
    "yanhekt": ("YANHEKT (延河课堂)", test_yanhekt),
    "library": ("LIBRARY (图书馆)", test_library),
}


def parse_args():
    parser = argparse.ArgumentParser(description="BIT Login 在线集成测试")
    parser.add_argument(
        "--services",
        nargs="+",
        choices=sorted(TESTS),
        default=list(DEFAULT_SERVICES),
        help="指定要测试的服务（默认测试全部稳定服务）",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    username = os.getenv("BITUSERNAME", "").strip()
    password = os.getenv("BITPASSWORD", "")
    if not username or not password:
        raise RuntimeError("请在 .env 中设置 BITUSERNAME 和 BITPASSWORD")

    print("========== 开始测试登录模块 ==========")
    failures = []
    for service in args.services:
        label, test = TESTS[service]
        print("\nTesting: {}".format(label))
        try:
            test(username, password)
        except Exception as exc:
            failures.append((label, exc))
            print("FAIL: {}: {}".format(label, exc))
        else:
            print("PASS: {}".format(label))

    if failures:
        details = "; ".join("{}: {}".format(label, exc) for label, exc in failures)
        raise RuntimeError("{} 个测试失败: {}".format(len(failures), details))
    print("\n========== 全部测试通过 ==========")


if __name__ == "__main__":
    main()
