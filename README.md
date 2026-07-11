# bit-login 北京理工大学统一身份认证登录库

为北理工设计的统一身份认证登录模块，只需要账号密码，即可获取各个平台鉴权，直接提供 session 供使用。支持 Python 库调用和 RESTful API 服务两种方式。

## 📥 安装

```bash
git clone https://github.com/yht0511/bit-login.git
cd bit-login
pip install -r requirements.txt
```

## 🚀 快速开始 (Python SDK)

### 基础登录
```python
import bit_login

username = "your_username"
password = "your_password"

# 1. 登录 WebVPN
webvpn = bit_login.webvpn_login().login(username, password)
session = webvpn.get_session()
# 使用 session 访问校内资源
response = session.get("https://webvpn.bit.edu.cn/...")

# 2. 登录教务系统 (JWB)
jwb_login = bit_login.jwb_login().login(username, password)
# 获取成绩
scores = bit_login.jwb.score(jwb_login.get_session()).get_all_score()

# 3. 登录教学中心/一站式大厅 (JXZXEHALL)
hall_login = bit_login.jxzxehall_login().login(username, password)
# 获取学分信息
credits = bit_login.jxzxehall.credit(hall_login.get_session()).get_credit()
# 获取课程表
courses = bit_login.jxzxehall.courses(hall_login.get_session()).get_courses()

# 4. 其他服务支持
# - bit_login.ibit_login()      # iBIT
# - bit_login.yanhekt_login()   # 延河课堂
# - bit_login.library_login()   # 图书馆
```

### 二次认证

启用二次认证的账号可使用短信验证码或钉钉扫码继续登录：

```python
import time
import bit_login

client = bit_login.login()

try:
    result = client.login(username, password, callback_url)
except bit_login.second_auth_required:
    # 短信验证码
    client.send_sms_code()
    result = client.verify_sms_code(input("短信验证码: "))

    # 或使用钉钉扫码
    # qr = client.begin_dingtalk_qr()
    # print(qr["qr_url"])
    # while True:
    #     result = client.poll_dingtalk_qr()
    #     if result.get("status") != "waiting":
    #         break
    #     time.sleep(1)

print(result["callback"])
```

二次认证过程必须使用同一个 `client` 实例，以保留 CAS Cookie 和登录流程状态。

## 🌐 RESTful API 服务

本项目提供了一个基于 FastAPI 的高性能 RESTful API 服务，支持连接池复用和自动重试，适合生产环境使用。

### 启动服务

```bash
# 启动服务器 (默认端口 8000)
bash start.sh

# 或者手动启动
gunicorn server:app --workers 4 --worker-class uvicorn.workers.UvicornWorker --bind 0.0.0.0:8000
```

### 🐳 Docker 部署

如果你更喜欢使用 Docker，我们也提供了 Dockerfile 支持一键构建和部署。

#### 1. 构建镜像

在项目根目录下执行以下命令：

```bash
docker build -t bit-login-server -f server/Dockerfile .
```

#### 2. 启动容器

```bash
docker run -d -p 16384:16384 --name bit-login-server bit-login-server
```

服务启动后，可以通过 `http://localhost:16384` 访问服务。

#### 3. 环境变量配置

支持通过环境变量调整服务配置：

- `WORKERS`: Gunicorn 工作进程数 (默认: 4)
- `PORT`: 服务端口 (默认: 16384)
- `HOST`: 监听地址 (默认: 0.0.0.0)
- `MFA_STATE_KEY`: 二次认证状态加密密钥，32 字节 URL-safe Base64 编码
- `MFA_STATE_TTL`: 二次认证凭据有效期秒数 (默认: 300)

示例：修改端口为 8080 并设置 8 个工作进程

```bash
docker run -d -p 8080:8080 \
  -e PORT=8080 \
  -e WORKERS=8 \
  bit-login-server
```

可使用以下命令生成 `MFA_STATE_KEY`：

```bash
python -c "import base64, os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())"
```

### 服务端二次认证

启用二次认证的账号首次请求业务接口时会收到 HTTP 428，响应的
`detail.challenge_token` 由客户端临时保存。服务端不会保存待验证的登录对象，
因此后续请求可以由不同 Worker 处理。

- `POST /api/auth/second/sms/send`: 请求短信验证码
- `POST /api/auth/second/sms/verify`: 提交短信验证码和密码
- `POST /api/auth/second/dingtalk/begin`: 创建钉钉二维码
- `POST /api/auth/second/dingtalk/poll`: 携带密码轮询扫码状态

短信发送或扫码等待响应都会返回新的 `challenge_token`，后续请求应始终使用
最新 token。认证成功后，客户端重新请求原业务接口；该请求会复用服务端现有的
30 分钟业务 Session 缓存。

### 接口文档

所有接口均为 POST 请求，Content-Type 为 `application/json`。

#### 通用请求参数
接口都需要携带用户的账号密码用于认证。
```json
{
  "username": "your_username",
  "password": "your_password"
}
```

#### 1. 教务系统 - 获取成绩 (全部)
**URL**: `/api/jwb/all_score`
**参数**:
- `detailed` (bool, 可选): 是否获取详细信息

**示例**:
```bash
curl -X POST "http://localhost:8000/api/jwb/all_score" \
     -H "Content-Type: application/json" \
     -d '{"username": "...", "password": "..."}'
```

#### 2. 教务系统 - 获取成绩 (指定学期)
**URL**: `/api/jwb/score`
**参数**:
- `kksj` (string, 可选): 开课时间(学期)，如 "2023-2024-1"

#### 3. 教学中心 - 获取个人信息
**URL**: `/api/jxzxehall/student_data`

#### 4. 教学中心 - 获取学分信息
**URL**: `/api/jxzxehall/credit`

#### 5. 教学中心 - 获取课程表
**URL**: `/api/jxzxehall/courses`
**参数**:
- `kksj` (string, 可选): 学期代码

## 🛠️ 项目结构

- `bit_login/`: 核心 SDK 代码
  - `login.py`: 基础登录逻辑 (SSO)
  - `service.py`: 各个服务的登录封装
  - `services/`: 具体业务逻辑 (如教务查分、课程表)
- `server.py`: FastAPI 服务端入口
- `test.py`: SDK 测试脚本

## 🔗 参考仓库

+ https://github.com/BIT101-dev/BIT101-GO
+ https://github.com/BIT101-dev/BIT101
