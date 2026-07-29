# AGENTS.md

Repo-specific guidance for OpenCode sessions working on `bit-login-kt`. Trust executable configuration and source over `README.md`: the README is stale about Ktor version, test task names, CORS, and the removed synchronous session cache/retry flow.

## Repository map

- Two-module Gradle/Kotlin repo; the root pins Kotlin compilation to JVM 17.
- `bit-login/` is a Kotlin Multiplatform SDK. Targets are Android (minSdk 26, compileSdk 36) and JVM; public facade: `cn.bit101.bitlogin.BitLogin`.
- `bit-login-server/` is a Ktor 3 JVM application depending on `:bit-login`; entrypoint: `cn.bit101.bitlogin.server.ApplicationKt`.
- The sibling repo `../bit-login` is the Python reference. REST, SSO, crypto, parser, and auth persistence behavior should match `../bit-login/server/`; defer to its executable implementation when behavior is ambiguous.

## KMP source sets

`kotlin.mpp.applyDefaultHierarchyTemplate=false` is intentional. `bit-login/build.gradle.kts` manually wires:

```text
commonMain -> jvmShared -> androidMain
                        -> jvmMain
```

- `commonMain` must remain free of `java.*`, `javax.*`, and JVM-only Ktor engine imports.
- `jvmShared` contains JVM-family `actual` implementations shared by Android and server JVM: JCA crypto, Jsoup parsers, `java.time`, DNS/URI helpers, and the OkHttp engine factory.
- Put a new JVM-family implementation in `jvmShared` and expose an `expect` API from `commonMain`; do not duplicate it in both target leaves.
- `androidMain` currently contains only `AndroidManifest.xml`; `jvmMain` is empty. JVM-only JUnit tests live in `jvmTest`.
- AGP 9.x requires `com.android.kotlin.multiplatform.library`; do not replace it with `com.android.library` or add a top-level `android {}` block.

## Commands

```bash
./gradlew build                              # only full verification gate; all modules/targets/tests
./gradlew :bit-login:jvmTest                 # SDK JVM tests
./gradlew :bit-login:jvmTest --tests "cn.bit101.bitlogin.util.WebVpnUrlTest"
./gradlew :bit-login:assemble                # common/JVM/Android artifacts, no server tests
./gradlew :bit-login-server:test             # server tests only
./gradlew :bit-login-server:run              # 0.0.0.0:16384 by default
./gradlew :bit-login-server:installDist       # production distribution
```

- There is no Detekt, ktlint, formatter, or separate typecheck gate.
- Android tasks require a local Android SDK path in ignored `local.properties` as `sdk.dir=...`.
- Run focused tests with `--tests "fully.qualified.ClassName"` or `--tests "fully.qualified.ClassName.methodName"`.

## Testing

- Tests use JUnit 5. Server tests use Ktor `testApplication { application { mainModule(...) } }`.
- There is no mock framework; use hand-written fakes and Python-produced golden vectors. Generate new parity vectors from `../bit-login` before porting crypto, URL, parser, or SSO behavior.
- `:bit-login-server:test` sets `BASE_URL=https://test.example`; tests must not depend on the production default.
- Avoid live network tests. `NetEnvTest` checks DNS semantics only.

## Behavioral constraints

- `http/HttpClient.kt` intentionally emulates `requests.Session`: persistent cookies, mutable default headers, per-call redirects, and the `postInterceptor` used by `CxcyLogin`. Do not bypass it with raw Ktor clients.
- Both targets use OkHttp through `createPlatformHttpClient` in `jvmShared`. CIO previously failed on live CAS chunked 302 responses. Redirect disabling requires `HttpClientConfig.followRedirects = false`; merely omitting `HttpRedirect` does not disable Ktor redirects.
- `TrackingCookieStorage` exposes a Python `get_dict()`-style snapshot while enforcing request matching. Ktor `GMTDate.timestamp` is milliseconds; `CookieDetail.expires` is epoch seconds. Do not multiply stored expiry by 1000 again.
- `BitSsoClient.request` raises on 4xx/5xx. Only `/cas/login` POST responses with 400/401/403 bypass that raise so page parsing can return the Python-compatible login rejection message.
- `SsoLogin` overwrites browser default headers even on an injected session; Python does the same and CAS requires the User-Agent behavior.
- Use `PythonUrlEncoding.urlJoin` where Python uses `urllib.parse.urljoin`. `URI.resolve` differs for query-only references such as `?x=1`.
- Captcha OCR is deliberately absent. Captcha flows, including phone-primary `loginSms`, require an injected `CaptchaSolver`; never substitute an empty result.

## Server auth and errors

- Data routes resolve sessions through `AuthServiceExecutor`. Bearer mode validates token plus `challenge_id`; password mode starts `AuthWorker`, waits one second, then returns HTTP 202 with a challenge snapshot and `access_token` if still pending. Do not reintroduce the old synchronous cache or retry-once layer.
- `ChallengeStore` suspend database operations explicitly switch to `Dispatchers.IO`; preserve those boundaries. It uses WAL for multi-worker access and creates the DB directory/files with owner-only permissions where POSIX permissions are available.
- `ChallengeStore.detectAndMigrateSchema` intentionally drops all three auth tables for the legacy encrypted schema; it also clears orphaned challenges and adds `subject` to the compatible old schema. Preserve parity with Python `server/auth.py`.
- Registration tokens are Ed25519 JWTs using JDK 17 `KeyFactory`/`Signature`; no external crypto dependency is needed.
- `HttpException.jsonBody` carries structured details for responses such as 202 snapshots and nested 422 errors. `StatusPages` wraps it as `{"detail": ...}`; malformed JSON/body conversion maps to 422, not 500.
- CORS currently uses Ktor `CORS { anyHost() }`; `AppConfig.allowedCorsOrigins` is not applied. This intentionally differs from the Python whitelist and contradicts the stale README.
- `safeError` exposes only the top-level message/class fallback with token redaction. Do not walk causes or include stack/file/line data in public challenge snapshots.

## Versioning

Keep `version` in `gradle.properties` and `BitLogin.VERSION` in `bit-login/src/commonMain/.../BitLogin.kt` identical; both are currently `4.0.0`.
