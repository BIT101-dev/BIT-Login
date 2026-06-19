import bit_login
import os

username = ""
password = ""

if not username and not password:
    username = os.getenv("BITUSERNAME")
    password = os.getenv("BITPASSWORD")

print("========== 开始测试登录模块 ==========")

print("Testing: WEBVPN")
assert bit_login.webvpn_login().login(username=username, password=password).get_session().get("https://webvpn.bit.edu.cn/connection/log?page=1&limit=10").json()["Message"] == "获取成功"
print("✅ PASS: WEBVPN\n")

print("Testing: JWB (教务系统)")
c=0
for i in bit_login.jwb.score(bit_login.jwb_login().login(username=username, password=password).get_session()).get_all_score():
    if i["credit"]: c+=float(i["credit"])
print(f"总学分:{c}")
print("✅ PASS: JWB\n")

print("Testing: JWB_CJD (教务系统-成绩单)")
print(bit_login.jwb.cjd(bit_login.jwb_cjd_login().login(username=username,password=password).get_session()).get_cjd())
print("✅ PASS: JWB\n")

print("Testing: JXZXEHALL (教学中心/一站式大厅)")
print(bit_login.jxzxehall.credit(bit_login.jxzxehall_login().login(username=username, password=password).get_session()).get_credit())
print(bit_login.jxzxehall.course(bit_login.jxzxehall_login().login(username=username, password=password).get_session()).get_courses())
# print(bit_login.jxzxehall.classroom(bit_login.jxzxehall_login().login(username=username, password=password).get_session()).get_occupancy("2026-03-04"))
print("✅ PASS: JXZXEHALL\n")


print("Testing: IBIT (iBIT 手机端聚合页)")
assert '{"code":0,"message":"","data":' in bit_login.ibit_login().login(username=username, password=password).get_session().get("https://ibit.yanhekt.cn/proxy/v1/user?with_desensitize=false").text
print("✅ PASS: IBIT\n")

print("Testing: YANHEKT (延河课堂)")
assert '{"code":0,"message":"","data":{"' in bit_login.yanhekt_login().login(username=username, password=password).get_session().get('https://cbiz.yanhekt.cn/v1/user').text
print("✅ PASS: YANHEKT\n")

print("Testing: LIBRARY (图书馆)")
print(bit_login.library_login().login(username=username, password=password).get_result())
print("✅ PASS: LIBRARY\n")

# print("Testing: DEKT (第二课堂)")
# assert "cookie_json" in bit_login.dekt_login().login(username=username, password=password).get_result()
# print("✅ PASS: DEKT\n")

# print("Testing: CXCY")
# bit_login.cxcy_login().login(username=username, password=password).get_result()
# print("✅ PASS: CXCY\n")

print("========== 全部测试通过 ==========")
