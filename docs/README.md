# 接口文档

本目录记录 `bit-login-kt` 4.0.0 的对外接口。文档以当前 Kotlin 源码和 Ktor 路由为准。

| 文档 | 适用对象 | 内容 |
|---|---|---|
| [Kotlin SDK](kotlin-sdk.md) | Kotlin/JVM 应用 | `cn.bit101.bitlogin` 的登录、会话和业务查询接口 |
| [REST API](rest-api.md) | HTTP 客户端 | `bit-login-server` 的认证挑战、教务、教学中心和 ICS 接口 |

## 版本与兼容性

- Kotlin 包名：`cn.bit101.bitlogin`
- SDK 版本：`4.0.0`
- Java 运行时：JDK 17+
- 服务端：Ktor 2.x，默认地址 `http://0.0.0.0:16384`
- REST API 没有 URL 版本前缀；客户端应以本文档列出的请求字段和响应字段为准。

## 安全提示

- `password`、Cookie、`access_token`、注册 JWT 均为敏感数据。仅通过 HTTPS 传输，且不要记录到日志、浏览器存储或客户端错误报告。
- `access_token` 只能与对应的 `challenge_id` 搭配使用。挑战删除或过期后，关联会话立即失效。
- SDK 的登录器维护可变 Cookie 会话。不要在不受信任的协程或请求之间共享同一个登录器实例；每次登录通过 `BitLogin` 创建新实例。
