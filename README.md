# bit-login 北京理工大学统一身份认证登录库

为北理工设计的统一身份认证登录模块，只需要账号密码，即可获取各个平台鉴权，直接提供 session 供使用。支持 Python 库调用和 RESTful API 服务两种方式。

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

如果你更喜欢使用 Docker，我们也提供了 Dockerfile 支持一键构建和部署。

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

服务启动后，可以通过 `http://localhost:16384` 访问服务。

#### 3. 环境变量配置

支持通过环境变量调整服务配置：

- `WORKERS`: Gunicorn 工作进程数 (默认: 4)
- `PORT`: 服务端口 (默认: 16384)
- `HOST`: 监听地址 (默认: 0.0.0.0)
- `AUTH_DB_PATH`: SQLite 路径（Docker 默认 `/app/data/auth.db`，本机默认 `/tmp/bit-login/auth.db`）
- `AUTH_CHALLENGE_TTL`: 等待短信验证码的秒数（默认 300）
- `AUTH_SESSION_TTL`: 下游 Session 保留秒数（默认 1800）

示例：修改端口为 8080 并设置 8 个工作进程

```bash
docker run -d -p 8080:8080 \
  -e PORT=8080 \
  -e WORKERS=8 \
  -v bit-login-data:/app/data \
  bit-login-server
```

### 接口文档

### 短信 challenge

服务端登录采用显式的异步 challenge，适用于任意数量的本机 Gunicorn worker：

```bash
# 1. 开始登录，可一次建立多个下游 Session
curl -X POST http://localhost:16384/api/auth/start \
  -H 'Content-Type: application/json' \
  -d '{"username":"学号","password":"密码","services":["jwb","jxzxehall"]}'

# 响应中的 challenge_id 用于定位流程，access_token 必须保密。

# 2. status=waiting_sms 后提交验证码
curl -X POST http://localhost:16384/api/auth/CHALLENGE_ID/sms \
  -H 'Content-Type: application/json' \
  -H 'X-Challenge-Token: ACCESS_TOKEN' \
  -d '{"code":"123456"}'

# 3. 查询直至 status=authenticated
curl http://localhost:16384/api/auth/CHALLENGE_ID \
  -H 'X-Challenge-Token: ACCESS_TOKEN'

# 4. 获取下游登录结果（Cookie、iBIT badge、延河/图书馆 token 等）
curl http://localhost:16384/api/auth/CHALLENGE_ID/services/yanhekt \
  -H 'X-Challenge-Token: ACCESS_TOKEN'

# 5. 使用已建立的 JWB Session，不再发送账号密码
curl -X POST http://localhost:16384/api/jwb/all_score \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer ACCESS_TOKEN' \
  -d '{"challenge_id":"CHALLENGE_ID","detailed":false}'
```

支持的 challenge service 名称：`webvpn`、`jwb`、`jwb_cjd`、`jxzxehall`、`ibit`、`yanhekt`、`library`、`dekt`、`cxcy`。


#### 通用请求参数

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
- `server/server.py`: FastAPI 服务端入口
- `server/auth.py`: SQLite WAL challenge 与加密 Session 存储

## 🔗 参考仓库

+ https://github.com/BIT101-dev/BIT101-GO
+ https://github.com/BIT101-dev/BIT101
