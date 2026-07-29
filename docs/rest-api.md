# REST API 接口

## 服务约定

默认服务地址为 `http://localhost:16384`。生产环境应使用 HTTPS 反向代理。

- 编码：JSON 请求和响应均为 UTF-8。
- 除下载接口外，带请求体的接口使用 `Content-Type: application/json`。
- 服务端 CORS 当前允许任意来源；浏览器调用仍应只向可信 HTTPS 服务发送凭据。
- 业务接口支持两种认证模式：用户名密码，或已认证挑战的 Bearer 会话。两种模式不可混用。

## 端点索引

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/` | 服务健康检查。 |
| `POST` | `/api/auth/start` | 创建认证 challenge。 |
| `GET` | `/api/auth/{challenge_id}` | 查询 challenge 状态。 |
| `POST` | `/api/auth/{challenge_id}/sms` | 提交短信验证码。 |
| `GET` | `/api/auth/{challenge_id}/services/{service}` | 获取已完成服务的登录结果。 |
| `POST` | `/api/auth/{challenge_id}/registration-token` | 签发注册 JWT。 |
| `DELETE` | `/api/auth/{challenge_id}` | 删除 challenge。 |
| `POST` | `/api/jwb/score` | 查询单学期成绩。 |
| `POST` | `/api/jwb/all_score` | 查询全部成绩。 |
| `POST` | `/api/jwb/bit101/score` | 查询 BIT101 表格格式成绩。 |
| `POST` | `/api/jwb/cjd/img` | 获取成绩单图片 URL。 |
| `POST` | `/api/jwb/cookies` | 获取教务 Cookie。 |
| `POST` | `/api/jwb/cjd/cookies` | 获取成绩单系统 Cookie。 |
| `POST` | `/api/jxzxehall/student_data` | 查询学生资料。 |
| `POST` | `/api/jxzxehall/credit` | 查询学分。 |
| `POST` | `/api/jxzxehall/courses` | 查询课程表。 |
| `POST` | `/api/jxzxehall/schedule_ics` | 生成临时 ICS 日历。 |
| `POST` | `/api/jxzxehall/cookies` | 获取教学中心 Cookie。 |
| `GET` | `/tmp/{filename}` | 下载临时 `.ics` 文件。 |

### `GET /`

无需认证。响应：

```json
{"message":"BIT Login Services API is running"}
```

## 通用错误格式

除 ICS 非法后缀的特殊响应外，错误为：

```json
{"detail":"错误说明"}
```

`/api/auth/start` 的非法服务名返回嵌套对象：

```json
{
  "detail": {
    "message": "invalid authentication services",
    "invalid": ["unknown"],
    "supported": ["cxcy", "dekt", "ibit", "jwb", "jwb_cjd", "jxzxehall", "library", "webvpn", "yanhekt"]
  }
}
```

| 状态码 | 含义 |
|---|---|
| `200` | 请求成功。 |
| `202` | 登录仍在进行或需要短信验证；响应体是挑战快照，包含 `access_token`。 |
| `400` | 缺少用户名密码、缺失 Bearer 模式的 `challenge_id` 等。 |
| `401` | 登录失败，或 Authorization header 不是有效的 Bearer 形式。 |
| `403` | challenge token 无效，或下载路径不是 `.ics`。 |
| `404` | challenge、服务名或 ICS 文件不存在。 |
| `409` | challenge 尚未认证、服务尚未就绪、SMS 状态冲突。 |
| `422` | JSON 无法转换、请求参数非法、教学中心数据不可用。 |
| `500` | 未处理的上游或服务内部错误。 |
| `503` | 注册 JWT 服务未配置。 |

## 认证模式

### 用户名密码模式

业务 POST 请求中传入 `username` 和 `password`：

```json
{"username":"1120230000","password":"secret"}
```

服务端会创建后台认证任务，最多等待 1 秒：

- 已完成认证时，继续执行目标业务接口并返回 `200`。
- 未完成或等待短信时，返回 `202`，`detail` 是含有 `challenge_id` 与 `access_token` 的挑战快照。
- 收到 `202` 后必须走下方挑战流程，不应反复提交密码请求。

### Bearer challenge 模式

先通过挑战流程获得对应服务的会话，再请求业务接口：

```http
Authorization: Bearer <access_token>
Content-Type: application/json
```

```json
{"challenge_id":"<challenge_id>","kksj":"2025-2026-1"}
```

- 必须同时提供 `Authorization: Bearer <access_token>` 和请求体中的 `challenge_id`。
- Bearer 模式下忽略 `username` 与 `password`。
- `challenge_id` 必须处于 `authenticated` 状态，且请求服务必须已在 `ready_services` 中。

## 挑战认证 API

### 挑战快照

`POST /api/auth/start` 以及业务接口的 `202` 响应会携带 `access_token`；其他挑战接口不会回显它。

```json
{
  "challenge_id": "string",
  "access_token": "string，仅创建响应和 202 响应提供",
  "status": "running | waiting_sms | processing | authenticated | failed | expired",
  "requested_services": ["jwb"],
  "ready_services": ["jwb"],
  "expires_in": 300,
  "masked_phone": "138****8000，仅 waiting_sms",
  "sms_purpose": "password_second_factor，仅 waiting_sms",
  "error": "安全脱敏后的失败原因，仅 failed"
}
```

`expires_in` 是剩余秒数。会话认证完成后，默认有效期为 1800 秒；等待状态默认有效期为 300 秒，可通过服务端环境变量调整。

### `POST /api/auth/start`

创建认证挑战。

请求：

```json
{
  "username": "1120230000",
  "password": "secret",
  "services": ["jwb", "jxzxehall"],
  "wait_seconds": 1.0
}
```

| 字段 | 必填 | 规则 |
|---|---|---|
| `username` | 是 | 学号或统一认证账号。 |
| `password` | 是 | 统一认证密码。 |
| `services` | 否 | 默认 `['jwb']`；会去重。可选：`cxcy`、`dekt`、`ibit`、`jwb`、`jwb_cjd`、`jxzxehall`、`library`、`webvpn`、`yanhekt`。 |
| `wait_seconds` | 否 | 默认 `1.0`；范围 `0` 至 `5` 秒。 |

响应：`202 Accepted`，返回包含 `access_token` 的挑战快照。

### `GET /api/auth/{challenge_id}`

查询挑战状态。

请求头：`X-Challenge-Token: <access_token>`。

响应：`200 OK`，返回不包含 `access_token` 的挑战快照。

### `POST /api/auth/{challenge_id}/sms`

提交短信验证码，仅状态为 `waiting_sms` 时可提交一次。

请求头：`X-Challenge-Token: <access_token>`。

请求：

```json
{"code":"123456"}
```

`code` 去除首尾空白后必须是 4 至 8 位数字。

响应：`200 OK`，等待最多 1 秒后返回最新挑战快照。无效验证码格式、非等待状态或重复提交返回 `409`。

### `GET /api/auth/{challenge_id}/services/{service}`

读取某个已完成服务登录的原始结果。

请求头：`X-Challenge-Token: <access_token>`。

`service` 必须是创建挑战时支持的服务名。挑战未认证或对应服务未就绪时返回 `409`。

响应：

```json
{
  "service": "jwb",
  "data": {
    "cookie_json": {"name":"value"},
    "cookie": "name=value"
  }
}
```

不同登录服务的 `data` 字段可包含 `callback`、`token` 或 `user_info`，见 [Kotlin SDK 文档](kotlin-sdk.md)。其中 Cookie 与 token 均为敏感信息。

### `POST /api/auth/{challenge_id}/registration-token`

为已认证挑战签发注册用 Ed25519 JWT。

请求头：`X-Challenge-Token: <access_token>`。

请求：

```json
{"audience":"example-client"}
```

响应：

```json
{
  "registration_token":"eyJ...",
  "token_type":"Bearer",
  "expires_in":300,
  "audience":"example-client"
}
```

仅 `authenticated` 状态可签发。`audience` 不在 `REGISTRATION_JWT_ALLOWED_AUDIENCES` 白名单时返回 `422`；未配置私钥时返回 `503`。

### `DELETE /api/auth/{challenge_id}`

删除挑战及其关联 SMS、服务会话。

请求头：`X-Challenge-Token: <access_token>`。

响应：

```json
{"status":"deleted"}
```

## 业务接口请求模型

下列业务接口均接受用户名密码模式或 Bearer challenge 模式。

通用字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `username` | string | 用户名密码模式必填。 |
| `password` | string | 用户名密码模式必填；空字符串视为缺失。 |
| `challenge_id` | string | Bearer challenge 模式必填。 |
| `kksj` | string | 可选学期代码，具体格式由上游系统决定。 |

## 教务系统 API

### `POST /api/jwb/score`

请求字段：通用字段，另有 `detail: boolean = false` 与 `detailed: boolean?`。如同时传递，`detailed` 优先。

```json
{"username":"1120230000","password":"secret","kksj":"2025-2026-1","detailed":true}
```

响应：

```json
{
  "data": [
    {
      "student":"张三",
      "course":"高等数学",
      "score":"95",
      "credit":"4",
      "hours":"64",
      "kksj":"2025-2026-1",
      "type":"必修",
      "class_number":null,
      "major_number":null,
      "study_number":null,
      "average":null,
      "max":null,
      "entry_complete":null,
      "self_score":null,
      "class_proportion":null,
      "major_proportion":null,
      "school_proportion":null
    }
  ]
}
```

### `POST /api/jwb/all_score`

请求字段：通用字段，另有 `detailed: boolean = false`。

响应：`{"data":[成绩对象,...]}`，结构与 `/api/jwb/score` 相同。

### `POST /api/jwb/bit101/score`

请求字段与 `/api/jwb/score` 相同。

响应中的 `data` 是字符串二维数组，第一行固定为列名；`detailed=true` 时追加 10 个详情列。

```json
{
  "msg":"查询成功OvO",
  "data":[["序号","开课学期","课程编号","课程名称","成绩"],["1","2025-2026-1","...","高等数学","95"]]
}
```

### `POST /api/jwb/cjd/img`

请求字段：通用字段；实现会忽略请求中的 `detailed`。

响应：

```json
{"data":{"url":"https://jwb.bit.edu.cn/cjd/Temp/..."}}
```

此 URL 指向上游成绩单图片，访问权限与有效期由上游控制。

### Cookie 接口

| 方法 | 所需服务会话 | 响应 |
|---|---|---|
| `POST /api/jwb/cookies` | `jwb` | `{"data":{"cookie":"value"},"cookie_str":"cookie=value"}` |
| `POST /api/jwb/cjd/cookies` | `jwb_cjd` | 同上 |

请求字段仅为通用字段。不要在浏览器前端或日志中暴露 Cookie。

## 教学中心 API

### `POST /api/jxzxehall/student_data`

请求字段：通用字段。

响应：

```json
{
  "data": {
    "name":"张三",
    "student_code":"1120230000",
    "major":"专业名称",
    "class":"班级名称",
    "grade":"2023",
    "gender":"男",
    "college":"学院名称",
    "total_credit":"160",
    "completed_credit":"80",
    "required_credit":"120",
    "id":"...",
    "detail": {"pyfadm":"...","zydm":"..."}
  }
}
```

### `POST /api/jxzxehall/credit`

请求字段：通用字段。

响应：

```json
{
  "data": {
    "total_credit":"160",
    "completed_credit":"80",
    "required_credit":"120"
  }
}
```

### `POST /api/jxzxehall/courses`

请求字段：通用字段，另有可选 `kksj`。

响应：

```json
{
  "data": {
    "term":"2025-2026-1",
    "firstDay":"2025-09-01",
    "data":[{"KCM":"课程名","SKZC":"..."}]
  }
}
```

`data` 是教学中心的原始课程记录，字段以其上游定义为准。当前账号不支持本科教学中心或数据异常时返回 `422`。

### `POST /api/jxzxehall/schedule_ics`

请求字段与课程表接口相同。

响应：

```json
{
  "url":"https://login.bit101.flwfdd.xyz/tmp/<随机名>.ics",
  "note":"一共添加了...",
  "msg":"获取成功OvO"
}
```

`url` 的 host 基于服务端 `BASE_URL` 配置。生成文件是临时资源，应及时下载。

### `POST /api/jxzxehall/cookies`

请求字段仅为通用字段。

响应：

```json
{"data":{"cookie":"value"},"cookie_str":"cookie=value"}
```

## ICS 下载

### `GET /tmp/{filename}`

仅接受以 `.ics` 结尾的 `filename`。成功时：

- 状态：`200 OK`
- `Content-Type: text/calendar`
- `Content-Disposition: attachment; filename="课程表.ics"`

文件不存在或过期时返回 `404`。非 `.ics` 路径返回：

```json
{"detail":"Forbidden"}
```

## 完整短信认证示例

```bash
BASE_URL="http://localhost:16384"

START=$(curl -sS -X POST "$BASE_URL/api/auth/start" \
  -H "Content-Type: application/json" \
  -d '{"username":"1120230000","password":"secret","services":["jwb"]}')

# 从 START 读取 challenge_id 和 access_token；若 status 为 waiting_sms，提交短信码。
curl -sS -X POST "$BASE_URL/api/auth/<challenge_id>/sms" \
  -H "Content-Type: application/json" \
  -H "X-Challenge-Token: <access_token>" \
  -d '{"code":"123456"}'

# 轮询到 status=authenticated 后，以该会话查询成绩。
curl -sS -X POST "$BASE_URL/api/jwb/score" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -d '{"challenge_id":"<challenge_id>","detailed":false}'
```

若 `/api/auth/start` 直接返回 `authenticated`，不需要 SMS 步骤；仍应使用返回的 `challenge_id` 和 `access_token` 访问已请求的服务。

## 服务端配置

| 环境变量 | 默认值 | 作用 |
|---|---|---|
| `HOST` | `0.0.0.0` | 监听地址。 |
| `PORT` | `16384` | 监听端口。 |
| `HTTP_CONNECT_TIMEOUT` | `5` | 上游 HTTP 连接超时，秒。 |
| `HTTP_READ_TIMEOUT` | `25` | 上游 HTTP 读取超时，秒。 |
| `BASE_URL` | `https://login.bit101.flwfdd.xyz` | 临时 ICS URL 的基础地址。 |
| `AUTH_DB_PATH` | `/tmp/bit-login/auth.db` | challenge SQLite 数据库路径。 |
| `AUTH_CHALLENGE_TTL` | `300` | 认证与 SMS challenge TTL，秒。 |
| `AUTH_SESSION_TTL` | `1800` | 成功认证的服务会话 TTL，秒。 |
| `REGISTRATION_JWT_PRIVATE_KEY_FILE` | 空 | Ed25519 PKCS#8 PEM 私钥路径。 |
| `REGISTRATION_JWT_ALLOWED_AUDIENCES` | 空 | 允许的 JWT audience，逗号分隔。 |
| `REGISTRATION_JWT_TTL` | `300` | 注册 JWT TTL，秒。 |
| `REGISTRATION_JWT_ISSUER` | `bit-login` | JWT issuer。 |
| `REGISTRATION_JWT_KEY_ID` | `registration-1` | JWT key ID。 |
