# bit-login-kt

[北京理工大学统一身份认证登录库](../bit-login) 的 Kotlin 移植版。monorepo 结构：纯 SDK 模块 + Ktor RESTful 服务。

## 🎯 项目结构

```
bit-login-kt/
├── bit-login/             # 纯 SDK：登录、解析、API 封装
└── bit-login-server/      # Ktor RESTful 服务（依赖 bit-login）
```

| 模块 | 说明 |
|---|---|
| `bit-login` | 完整认证逻辑 + 业务接口（成绩、课程表、空闲教室等） |
| `bit-login-server` | Ktor 2.x 服务，提供与 Python 版 1:1 的 RESTful 接口 |

## 📥 环境要求

- JDK 17+（推荐 JBR 17 / Eclipse Temurin 17）
- Gradle Wrapper 已包含（`./gradlew`），无需本地装 Gradle

> ⚠️ 当前 `gradle.properties` 中 `org.gradle.java.home` 指向 JBR 17，
> 因为 Kotlin Gradle plugin ≤ 2.4.x 无法解析 JDK 25 版本字符串。
> 如需更换，请覆盖此值或设置 `JAVA_HOME`。

## 🚀 快速开始

### 构建与测试

```bash
./gradlew build              # 全量构建
./gradlew test               # 跑全部测试（util + config + 解析 + 服务端健康检查）
./gradlew :bit-login:test    # 仅 SDK 测试
```

### 用 SDK 完成一次登录

```kotlin
import cn.bit101.bitlogin.BitLogin
import cn.bit101.bitlogin.api.jwb.Score

suspend fun demo() {
    val session = BitLogin.jwbLogin().login("学号", "密码").getSession()
    val scores = Score(session).getAllScore()
    println(scores)
}
```

### 启动 RESTful 服务

```bash
# 开发模式
./gradlew :bit-login-server:run

# 生产模式
./gradlew :bit-login-server:installDist
./bit-login-server/build/install/bit-login-server/bin/bit-login-server
```

默认监听 `0.0.0.0:16384`。

## 🔌 RESTful 接口

所有接口均为 `POST`，`Content-Type: application/json`，请求体包含 `username`/`password`。

### 通用错误响应

| 状态码 | 触发条件 | 响应体 |
|---|---|---|
| 401 | 账号密码错误 / 登录失败 | `{"detail":"Login failed: ..."}` |
| 500 | 业务调用重试后仍失败 | `{"detail":"..."}` |
| 404 | ICS 文件不存在/过期 | `{"detail":"File not found or expired"}` |
| 403 | 非 `.ics` 后缀下载请求 | `{"detail":"Forbidden"}` |

### 接口列表（与 Python 版字段完全对齐）

| 方法 | 路径 | 请求体 | 响应 |
|---|---|---|---|
| GET  | `/` | — | `{"message":"BIT Login Services API is running"}` |
| POST | `/api/auth/start` | `{username,password,services?,wait_seconds?}` | 202 `{"challenge_id","access_token","status",...}` |
| GET  | `/api/auth/{challenge_id}` | `X-Challenge-Token` header | `{"challenge_id","status",...}` |
| POST | `/api/auth/{challenge_id}/sms` | header + `{code}` | 最新状态 snapshot |
| GET  | `/api/auth/{challenge_id}/services/{service}` | `X-Challenge-Token` header | `{"service","data":{...}}` |
| POST | `/api/auth/{challenge_id}/registration-token` | header + `{audience}` | `{"registration_token","token_type","expires_in","audience"}` |
| DELETE | `/api/auth/{challenge_id}` | `X-Challenge-Token` header | `{"status":"deleted"}` |
| POST | `/api/jwb/score` | `{username,password,kksj?,detail?}` | `{"data":[...]}` |
| POST | `/api/jwb/all_score` | `{username,password,detailed?}` | `{"data":[...]}` |
| POST | `/api/jwb/bit101/score` | `{username,password,kksj?,detail?}` | `{"msg":"查询成功OvO","data":[...]}` |
| POST | `/api/jwb/cjd/img` | `{username,password,detailed?}` | `{"data":{"url":...}}` |
| POST | `/api/jwb/cookies` | `{username,password}` | `{"data":{...},"cookie_str":...}` |
| POST | `/api/jwb/cjd/cookies` | `{username,password}` | 同上 |
| POST | `/api/jxzxehall/student_data` | `{username,password}` | `{"data":{...}}` |
| POST | `/api/jxzxehall/credit` | `{username,password}` | `{"data":{...}}` |
| POST | `/api/jxzxehall/courses` | `{username,password,kksj?}` | `{"data":{...}}` |
| POST | `/api/jxzxehall/cookies` | `{username,password}` | `{"data":{...},"cookie_str":...}` |
| POST | `/api/jxzxehall/schedule_ics` | `{username,password,kksj?}` | `{"url","note","msg":"获取成功OvO"}` |
| GET  | `/tmp/{filename}.ics` | — | `text/calendar` 文件下载 |

### 认证挑战流程（`/api/auth/*`）

异步 SSO 认证支持短信二次验证，使用 SQLite WAL 持久化 challenge 状态：

```
1. POST /api/auth/start    → 202 {challenge_id, access_token, status:"running"}
2. GET  /api/auth/{id}     → {status:"waiting_sms", masked_phone:"138****8000"}
3. POST /api/auth/{id}/sms → {status:"authenticated"} 或 {status:"waiting_sms"}
4. GET  /api/auth/{id}/services/jwb → {"service":"jwb","data":{...}}
5. DELETE /api/auth/{id}   → {"status":"deleted"}
```

### Bearer Challenge 复用 Session

现有 JWB、JXZXEHALL、Cookie 接口支持 Bearer token 复用已认证 session，无需重复提交账号密码：

```bash
curl -X POST "http://localhost:16384/api/jwb/score" \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer <access_token>" \
     -d '{"challenge_id":"<challenge_id>","kksj":"20232"}'
```

### curl 示例（密码模式）

```bash
curl -X POST "http://localhost:16384/api/jwb/all_score" \
     -H "Content-Type: application/json" \
     -d '{"username":"...", "password":"..."}'
```

## ⚙️ 环境变量

| 变量 | 默认值 | 含义 |
|---|---|---|
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `16384` | 监听端口 |
| `HTTP_CONNECT_TIMEOUT` | `5` | HTTP 连接超时（秒） |
| `HTTP_READ_TIMEOUT` | `25` | HTTP 读取超时（秒） |
| `BASE_URL` | `https://login.bit101.flwfdd.xyz` | ICS 文件外链 base |
| `AUTH_DB_PATH` | `/tmp/bit-login/auth.db` | SQLite challenge 数据库路径 |
| `AUTH_CHALLENGE_TTL` | `300` | challenge / SMS 有效期（秒） |
| `AUTH_SESSION_TTL` | `1800` | 已认证 session 有效期（秒） |
| `REGISTRATION_JWT_PRIVATE_KEY_FILE` | _(空)_ | Ed25519 PKCS#8 PEM 私钥路径 |
| `REGISTRATION_JWT_ALLOWED_AUDIENCES` | _(空)_ | JWT audience 白名单（逗号分隔） |
| `REGISTRATION_JWT_TTL` | `300` | JWT 有效期（秒） |
| `REGISTRATION_JWT_ISSUER` | `bit-login` | JWT 签发者 |
| `REGISTRATION_JWT_KEY_ID` | `registration-1` | JWT key ID |

## 🏗️ 架构要点

### 与 Python 版的技术映射

| Python | Kotlin |
|---|---|
| `requests.Session` | `HttpClient`（封装 Ktor Client，支持 cookie jar / 可变 headers / per-call redirect 控制） |
| `session.cookies.get_dict()` | `HttpClient.cookieMap()` |
| `allow_redirects=False` | `HttpClient.{get,post}(allowRedirects=false)` |
| BeautifulSoup | Jsoup |
| `pydantic.BaseModel` | `@Serializable data class` |
| `fastapi.HTTPException` | 自定义 `LoginError` / `HttpException` → `StatusPages` 映射 |
| `threading.RLock` | `kotlinx.coroutines.sync.Mutex` |
| `time.time()` + TTL | `java.time.Instant.now()` |
| CORS regex | 自定义 pipeline intercept（ktor 2.x 内置 CORS 不支持 regex） |
| `cryptography AES-ECB` | `javax.crypto.Cipher` |

### Session 缓存

- 缓存 key：`(username, service_name)`，例如 `("1120231337", "jwb")`
- TTL：30 分钟空闲后自动清除
- per-key `Mutex` 保证同一用户同一服务的并发请求串行化
- 业务调用失败时自动失效缓存 + 重试一次

### 自动重试

每次业务调用最多 2 次尝试：

1. 取（或新建）session
2. 调用业务方法
3. 失败 → 失效缓存 → 重新登录 → 再试一次
4. 仍失败 → 返回 500

`LoginError`（账号密码错误等）直通 401，不参与重试。

## 🧪 测试覆盖

| 测试类 | 内容 |
|---|---|
| `WebVpnUrlTest` | AES-ECB 编码对 6 组 Python golden vector 逐字节对照 |
| `PasswordCryptoTest` | XOR+Base64 往返 + 6 组 golden vector |
| `NetEnvTest` | DNS 探测：localhost / 不可解析域名 |
| `ConvertChineseScoreTest` | 优秀/良好/中等 等映射 |
| `ConstantsTest` | TIME_TABLE / BUILDING_MAP / getBuildingCoord / STATUS_MAP |
| `ConfigTest` | Config 数据完整性 |
| `ScoreParserTest` | Jsoup 解析样例 HTML（学生姓名、行数、中文成绩转换、bit101 表头） |
| `sso/CryptoTest` | AES-ECB / RSA-PKCS1 / CSRF header golden vector 与解包边界 |
| `sso/ParserTest` | CAS 登录页 / 二次验证页 HTML 解析与 form action resolve |
| `sso/FingerprintTest` | 浏览器指纹确定性 / 结构 / 不同 profile 差异 |
| `sso/BitSsoClientTest` | mock HTTP 密码登录 / captcha 拒绝 / SMS 二次验证流程 |
| `auth/ChallengeStoreTest` | challenge 生命周期 / SMS 单消费 / session restore / TTL / 脱敏 |
| `auth/RegistrationTokenTest` | Ed25519 JWT 签名 / audience 白名单 / TTL 校验 |
| `HealthRouteTest` | ktor-server-testkit 测 `GET /` |
| `AuthRoutesTest` | `/api/auth/start` → `status` → `delete` 全链路 |

```bash
$ ./gradlew test
> Task :bit-login:test
> Task :bit-login-server:test
```

## 🚫 已知限制

1. **图形验证码 OCR**：JVM 无成熟 ddddocr 替代。要求图形验证码时抛 `CaptchaError`；可通过 `CaptchaSolver` 接口注入自定义识别器。
2. **`CxcyLogin` 的 postInterceptor**：Python 用 `functools.wraps` 动态替换实例方法，Kotlin 用 interceptor 槽更干净。
3. **TLS 指纹**：Ktor CIO 的 TLS 指纹与 Python `requests` 不同。如遇反爬识别，可切换到 OkHttp 引擎。
4. **SQLite 并发**：使用 WAL + busy_timeout 支持多 worker 共享。所有 JDBC 操作在 `Dispatchers.IO` 执行。

## 📦 部署

### 单一可执行分发

```bash
./gradlew :bit-login-server:installDist
tar -czf bit-login-server.tar.gz -C bit-login-server/build/install bit-login-server
```

运行：

```bash
./bin/bit-login-server
```

### 后台运行

```bash
nohup ./bin/bit-login-server > server.log 2>&1 &
```

## 🔗 相关仓库

- 原版 Python：[../bit-login](../bit-login)
- 参考实现：https://github.com/BIT101-dev/BIT101-GO
