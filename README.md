# bit-login 北理工统一身份认证登录模块

北理工统一身份认证登录模块

## 📥 安装

```bash
git clone https://github.com/BIT101-dev/bit-login.git
cd bit-login
pip install -e '.[captcha]'
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

## 🌐 RESTful API 服务

本项目提供 FastAPI 服务。

### 启动服务

```bash
bash server/start.sh

# 或者手动启动
gunicorn server:app --workers 4 --worker-class uvicorn.workers.UvicornWorker --bind 0.0.0.0:16384
```

### 🐳 Docker 部署

#### 1. 构建镜像

在项目根目录下执行以下命令：

```bash
docker build -t bit-login-server -f server/Dockerfile .
```

#### 2. 启动容器

```bash
docker run -d -p 16384:16384 \
  -v bit-login-data:/app/data \
  --name bit-login-server bit-login-server
```

通过 `http://localhost:16384` 访问服务。

#### 3. 环境变量配置

- `WORKERS`: Gunicorn 工作进程数 (默认: 4)
- `PORT`: 服务端口 (默认: 16384)
- `HOST`: 监听地址 (默认: 0.0.0.0)
- `AUTH_DB_PATH`: SQLite 路径（Docker 默认 `/app/data/auth.db`，本机默认 `/tmp/bit-login/auth.db`）
- `AUTH_CHALLENGE_TTL`: 等待短信验证码的秒数（默认 300）
- `AUTH_SESSION_TTL`: 下游 Session 保留秒数（默认 1800）

### 接口文档

### 短信 challenge

```bash
# 1. 开始登录，可一次建立多个下游 Session
curl -X POST http://localhost:16384/api/auth/start \
  -H 'Content-Type: application/json' \
  -d '{"username":"学号","password":"密码","services":["jwb","jxzxehall"]}'

# 2. status=waiting_sms 后提交验证码
curl -X POST http://localhost:16384/api/auth/CHALLENGE_ID/sms \
  -H 'Content-Type: application/json' \
  -H 'X-Challenge-Token: ACCESS_TOKEN' \
  -d '{"code":"123456"}'

# 3. 查询直至 status=authenticated
curl http://localhost:16384/api/auth/CHALLENGE_ID \
  -H 'X-Challenge-Token: ACCESS_TOKEN'

# 4. 获取下游登录结果
curl http://localhost:16384/api/auth/CHALLENGE_ID/services/yanhekt \
  -H 'X-Challenge-Token: ACCESS_TOKEN'

# 5. 使用已建立的 JWB Session，不再发送账号密码
curl -X POST http://localhost:16384/api/jwb/all_score \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer ACCESS_TOKEN' \
  -d '{"challenge_id":"CHALLENGE_ID","detailed":false}'
```

支持的 challenge service 名称：`webvpn`、`jwb`、`jwb_cjd`、`jxzxehall`、`ibit`、`yanhekt`、`library`、`dekt`、`cxcy`。

### 注册 JWT

注册认证直接复用上面的 challenge。若不需要其他下游服务，第一步传
`"services":["webvpn"]` 即可。轮询到 `status=authenticated` 后，使用同一个
`ACCESS_TOKEN` 换取默认有效期为 5 分钟的注册 JWT：

```bash
curl -X POST http://localhost:16384/api/auth/CHALLENGE_ID/registration-token \
  -H 'X-Challenge-Token: ACCESS_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"audience":"bit101-main"}'
```

生成并配置 Ed25519 私钥：

```bash
openssl genpkey -algorithm ED25519 -out registration-private.pem
openssl pkey -in registration-private.pem -pubout -out registration-public.pem

export REGISTRATION_JWT_PRIVATE_KEY_FILE=/path/to/registration-private.pem
export REGISTRATION_JWT_ISSUER=bit-login
export REGISTRATION_JWT_ALLOWED_AUDIENCES=bit101-main,course-app
export REGISTRATION_JWT_TTL=300
export REGISTRATION_JWT_KEY_ID=registration-1
```

私钥只部署在服务器上，公钥可公开。每个主程序只接受自己的 `aud`；
未列入 `REGISTRATION_JWT_ALLOWED_AUDIENCES` 的 audience 无法签发。


## 🔗 参考仓库

+ https://github.com/BIT101-dev/BIT101-GO
+ https://github.com/BIT101-dev/BIT101
