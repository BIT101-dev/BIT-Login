# Kotlin SDK 接口

## 概览

SDK 模块为 `:bit-login`，根包为 `cn.bit101.bitlogin`。所有网络登录和业务查询方法均为 `suspend`，必须在协程中调用。

```kotlin
import cn.bit101.bitlogin.BitLogin
import cn.bit101.bitlogin.api.jwb.Score

suspend fun loadScores() {
    val login = BitLogin.jwbLogin().login("学号", "密码")
    val scores = Score(login.getSession()).getAllScore()
    println(scores)
}
```

每个 `BitLogin.*Login()` 工厂都会返回新的登录器。一次成功 `login()` 后，通过同一实例的 `getSession()` 将带 Cookie 的 `HttpClient` 传给业务 API。

## `BitLogin` 门面

类：`cn.bit101.bitlogin.BitLogin`

| 方法 | 返回类型 | 目标服务 |
|---|---|---|
| `webvpnLogin()` | `WebVpnLogin` | WebVPN |
| `jwbLogin()` | `JwbLogin` | 教务系统 |
| `jwbCjdLogin()` | `JwbCjdLogin` | 教务成绩单 |
| `jxzxehallLogin()` | `JxzxehallLogin` | 教学中心/一站式大厅 |
| `ibitLogin()` | `IbitLogin` | iBIT |
| `yanhektLogin()` | `YanhektLogin` | 延河课堂 |
| `libraryLogin()` | `LibraryLogin` | 图书馆 |
| `dektLogin()` | `DektLogin` | 第二课堂 |
| `cxcyLogin()` | `CxcyLogin` | 大创系统 |

常量：`VERSION`、`AUTHOR`、`EMAIL`、`DESCRIPTION`。`ServiceLogin` 是 `BaseLogin` 的类型别名。

## 通用登录器

所有服务登录器继承 `cn.bit101.bitlogin.service.BaseLogin`。

| 方法 | 说明 |
|---|---|
| `suspend fun login(username: String, password: String): BaseLogin` | 初始化网络环境，执行目标服务的 SSO 流程并返回当前对象，以便链式调用。 |
| `fun getSession(): HttpClient` | 返回已认证的会话。未成功登录时抛出 `IllegalStateException("未登录!")`。 |
| `fun getResult(): JsonObject` | 返回服务登录结果。未成功登录时抛出相同异常。 |

大多数登录器的 `getResult()` 具有以下 Cookie 结果结构：

```json
{
  "cookie_json": {"name": "value"},
  "cookie": "name=value; other=value"
}
```

服务特有字段：

| 登录器 | 额外结果字段或行为 |
|---|---|
| `WebVpnLogin` | 增加 `callback`。 |
| `IbitLogin` | 增加 `callback`；如可获得，会话中包含 iBIT badge。 |
| `YanhektLogin` | 增加 `token`；未获得 token 时登录失败。 |
| `LibraryLogin` | 增加 `user_info` 和 `token`。 |
| `CxcyLogin` | 为会话安装 POST 防伪 token 注入器。 |
| `DektLogin` | 保留实现，但上游服务标记为尚不可用。 |

## 直接 SSO 登录

类：`cn.bit101.bitlogin.login.SsoLogin`

当需要自行处理 CAS 回调时可直接使用。一般服务登录应优先使用 `BitLogin` 门面。

```kotlin
import cn.bit101.bitlogin.login.SsoLogin

suspend fun loginToCustomService() {
    val login = SsoLogin()
    val result = login.login(
        username = "学号",
        password = "密码",
        callbackUrl = "https://service.example/cas/callback",
    )
    println(result.callback)
}
```

```kotlin
suspend fun login(
    username: String,
    password: String,
    callbackUrl: String,
    webvpnMode: Boolean = false,
    retries: Int = 0,
    trustDevice: Boolean = false,
    smsCodeCallback: SmsCodeCallback? = null,
    captchaSolver: CaptchaSolver? = null,
): LoginResult
```

- `callbackUrl` 必填，CAS 成功后会带 service ticket 回调到该 URL。
- `session` 属性是持久化 Cookie 的 `HttpClient`，可传入已有会话，或在构造时注入。
- `retries` 和 `webvpnMode` 为兼容参数；当前服务登录流程负责网络环境与 WebVPN 会话处理。
- `LoginResult` 包含 `cookieJson: Map<String, String>`、`cookie: String`、`callback: String` 和可选 `ticket: String?`；`toJson()` 返回前三项的 `JsonObject`。

### 二次验证与验证码回调

```kotlin
import cn.bit101.bitlogin.sso.CaptchaSolver
import cn.bit101.bitlogin.sso.SmsCodeCallback

val smsCodeCallback: SmsCodeCallback = { context ->
    // context.phone、context.maskedPhone、context.purpose
    requestSmsCodeFromUser(context.maskedPhone)
}

val captchaSolver: CaptchaSolver = { image, context ->
    // image 是验证码图片字节；context 包含 purpose、username、phone
    solveImage(image)
}
```

- `SmsCodeCallback`：`suspend (SmsCodeContext) -> String`。
- `CaptchaSolver`：`suspend (ByteArray, CaptchaContext) -> String`。
- 未提供 `CaptchaSolver` 而 CAS 要求图形验证码时，会抛出 `CaptchaError`；SDK 不会伪造空验证码。

## 教务系统业务 API

所有业务 API 需要已认证的 `HttpClient`。先通过 `JwbLogin` 或 `JwbCjdLogin` 获取会话。

### `Score`

类：`cn.bit101.bitlogin.api.jwb.Score`

```kotlin
val session = BitLogin.jwbLogin().login(username, password).getSession()
val score = Score(session)
```

| 方法 | 返回值 | 说明 |
|---|---|---|
| `getScore(kksj: String? = null, detailed: Boolean = false)` | `List<Map<String, Any?>>` | 查询指定学期成绩；`kksj` 为空时按当前日期推导学期。 |
| `getAllScore(detailed: Boolean = false)` | `List<Map<String, Any?>>` | 查询所有成绩。 |
| `getBit101Score(kksj: String? = null, detailed: Boolean = false)` | `List<List<String>>` | 返回 BIT101 表格格式，首行是表头。 |
| `getAllBit101Score(detailed: Boolean = false)` | `List<List<String>>` | 查询所有成绩的 BIT101 表格格式。 |
| `getScoreDetail(url: String)` | `Map<String, Any?>` | 请求并解析单门成绩详情页面。 |
| `parseScore(html: String, detailed: Boolean = false)` | `List<Map<String, Any?>>` | 解析教务 HTML，不发起登录。 |
| `parseBit101Score(html: String, detailed: Boolean = false)` | `List<List<String>>` | 解析为 BIT101 表格。 |

普通成绩行包含：`student`、`course`、`score`、`credit`、`hours`、`kksj`、`type`。详情模式还包含 `class_number`、`major_number`、`study_number`、`average`、`max`、`entry_complete`、`self_score`、`class_proportion`、`major_proportion`、`school_proportion`；无法获得详情时这些字段为 `null`。

`convertChineseScore(score)` 会转换：优秀 `95`、良好 `85`、中等 `75`、及格 `65`、不及格 `0`。

### `Cjd`

类：`cn.bit101.bitlogin.api.jwb.Cjd`

```kotlin
val session = BitLogin.jwbCjdLogin().login(username, password).getSession()
val imageUrl = Cjd(session).getCjd(gpa = true)
```

`suspend fun getCjd(gpa: Boolean = true): String` 返回成绩单图片 URL。上游页面不符合预期时抛出 `IllegalStateException("成绩单获取失败!")`。

## 教学中心业务 API

### `Credit`

类：`cn.bit101.bitlogin.api.jxzxehall.Credit`

```kotlin
val session = BitLogin.jxzxehallLogin().login(username, password).getSession()
val credit = Credit(session)
val profile = credit.getStudentData()
val totals = credit.getCredit()
```

| 方法 | 返回值 | 说明 |
|---|---|---|
| `getStudentData()` | `JsonObject` | 学生资料、班级、学院与学分信息。 |
| `getCredit()` | `Map<String, String>` | 仅返回 `total_credit`、`completed_credit`、`required_credit`。 |

学生资料顶层字段为 `name`、`student_code`、`major`、`class`、`grade`、`gender`、`college`、`total_credit`、`completed_credit`、`required_credit`、`id`、`detail`。

### `Course`

类：`cn.bit101.bitlogin.api.jxzxehall.Course`

| 方法 | 返回值 | 说明 |
|---|---|---|
| `getCourses(kksj: String? = null)` | `Map<String, Any?>` | 返回 `term`、`firstDay`（`yyyy-MM-dd`）和教学中心原始课程数组 `data`。未传学期时请求当前学期。 |
| `generateIcs(kksj: String? = null)` | `Pair<String, String>` | 返回 ICS 文本与中文统计说明 `note`。 |

教学中心未返回适用数据时抛出 `JxzxehallDataError`。

### `Classroom`

类：`cn.bit101.bitlogin.api.jxzxehall.Classroom`

```kotlin
val rooms = Classroom(session).getOccupancy(
    dateStr = "2026-09-01",
    campusCode = "",
    buildingCode = "",
    classroomName = "理学A",
)
```

```kotlin
suspend fun getOccupancy(
    dateStr: String,
    semester: String? = null,
    week: Int? = null,
    campusCode: String? = null,
    buildingCode: String? = null,
    classroomName: String? = null,
): List<Map<String, Any?>>
```

- `dateStr` 必须为 `YYYY-MM-DD`，否则抛出 `IllegalArgumentException`。
- 未同时指定 `semester` 与 `week` 时，SDK 自动请求当前学期与周次。
- 每间教室返回 `name`、`building_code`、`type`、`seats`、可选 `coordinates` 和 `status`。`status` 以第 1 至 13 节为键，值包含 `state`、`start`、`end`。

## HTTP 会话接口

类：`cn.bit101.bitlogin.http.HttpClient`。这是 SDK 封装的持久 Cookie HTTP 会话，也可用于已登录服务的扩展请求。

| 方法 | 说明 |
|---|---|
| `get(url, headers, allowRedirects)` | GET 请求，返回 `HttpResponse`。 |
| `post(url, headers, data, json, allowRedirects)` | 表单或 JSON POST 请求。 |
| `request(method, url, headers, query, data, json, rawBody, allowRedirects)` | 通用文本请求。 |
| `getBytes(url, headers, allowRedirects)` | 获取二进制响应。 |
| `requestBytes(...)` | 通用二进制请求。 |
| `cookieMap()` / `cookieValue(name)` / `cookieString()` | 读取当前 Cookie。 |
| `cookieDetails()` | 返回带 domain、path、secure、过期秒级时间戳的 Cookie。 |
| `addCookie(...)` | 添加 Cookie；`expiresEpochSeconds` 单位为秒。 |
| `close()` | 关闭底层 Ktor 客户端。 |

`allowRedirects` 为 `null` 时使用构造参数默认值，显式传 `false` 可读取 3xx 的 `Location`。`HttpResponse` 包含 `status`、`headers`、`bodyText`、`bodyBytes`、`url`，并提供 `location()` 和 `isRedirect()`。

## 配置与网络环境

`NetworkEnv.ensureInitialized()` 在第一次 `BaseLogin.login()` 时自动执行，探测校内网络并填充 `Config.Urls.active`。如果直接调用依赖服务地址的低层 API，应先调用它。

```kotlin
import cn.bit101.bitlogin.NetworkEnv

NetworkEnv.ensureInitialized()
```

`Config` 包含 SSO URL、校内/WebVPN 服务 URL 和浏览器请求头。`Config.Urls.active` 是运行时可变 Map，通常不需要应用自行修改。

## 错误处理

- 服务登录失败通常抛出 `cn.bit101.bitlogin.login.LoginError`。
- 直接 SSO 调用还可能抛出 `CaptchaError`、`SmsVerificationError`、`ConfigurationError` 或 `SsoHttpException`。
- 教学中心数据无效时抛出 `JxzxehallDataError`。
- 业务 HTTP 请求或上游页面格式变化也可能抛出 `RuntimeException`、`IllegalStateException` 或网络 I/O 异常；调用方应记录安全的上下文并决定是否重试。
