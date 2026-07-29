# 服务端并发性能测试计划

为 `bit-login-server` 建立进程内并发性能测试，验证阶段 0–3 优化效果（共享连接池、ChallengeStore 单连接复用等）并防止并发回归。

## 已确认决策

| 决策项 | 选择 | 说明 |
|---|---|---|
| 测试形态 | 进程内 JUnit 5 | 本地假上游 `com.sun.net.httpserver`，符合 no-live-network 约束，可进 CI |
| 结果断言 | 打印指标 + 宽松断言 | 输出 ops/s、p50/p95/p99；断言只查正确性与宽松上限（如总时长 < 30s），避免 CI 抖动 |
| 构建接入 | 独立 `perfTest` task | 按 JUnit Tag `perf` 过滤；默认 `test`/`build` 排除，不改变现有验收 gate |

## 背景与约束

- 热路径：① `ChallengeStore`（阶段 2 后 ReentrantLock 串行化单连接，250ms 轮询）；② Bearer 数据路径（每请求 `getSession` + `SessionSerializer.restore` + 共享连接池出网）；③ `waitUntilActionable` 轮询风暴。
- 禁止 live network：数据路由上游 URL 硬编码在 SDK 内无法重定向 → 数据路径压测用组件级 + 本地假上游；路由级只压 auth 轮询路径（绕开 AuthWorker 的真实 SSO 登录）。
- 无 mock 框架：手写 fake；复用 `AuthRoutesTest` 的 `mainModule(config().copy(authDbPath = ...))` 模式与 `ChallengeStoreTest` 的 `@TempDir` + `runBlocking` 模式。
- 路由细节：状态轮询 `GET /api/auth/{challengeId}` 用 `X-Challenge-Token` 头鉴权。

## 实施清单

### 1. 构建接线（`bit-login-server/build.gradle.kts`）

- [x] `tasks.test`：现有 `useJUnitPlatform()` 改为 `useJUnitPlatform { excludeTags("perf") }`。
- [x] 新增 `tasks.register<Test>("perfTest")`：`useJUnitPlatform { includeTags("perf") }`，`testClassesDirs`/`classpath` 复用 test sourceset 输出，`systemProperty("BASE_URL", "https://test.example")`，`testLogging.showStandardStreams = true`（控制台输出报告）。不挂入 `check`，`./gradlew build` 不受影响。

### 2. 测试工具 `src/test/kotlin/cn/bit101/bitlogin/server/perf/PerfHarness.kt`（新）

- [x] `LatencyRecorder`：记录每次操作耗时（ns），统计 count / ops/s / p50 / p95 / p99 / max，输出单行格式化报告。
- [x] `runLoad(concurrency, opsPerWorker, block)`：`coroutineScope` + N 个 worker（`Dispatchers.IO`）各循环执行 `opsPerWorker` 次 `block` 并计时，聚合返回报告。

### 3. `perf/ChallengeStorePerfTest.kt`（新，`@Tag("perf")`）

- [x] 场景 A 混合负载：并发度 C ∈ {1, 8, 32, 64}；预创建 64 个 authenticated challenge 作读池；加权操作 = 70% `snapshot` 读 / 20% 写全生命周期（create→storeService→complete）/ 10% `cleanup()`；每级固定 ops 数，总时长 < 30s。断言：无意外异常、写路径 handle 最终 `authenticated`、错误 token 一律拒绝；打印每级报告。验证 ReentrantLock 争用下无死锁、无数据损坏。
- [x] 场景 B 轮询风暴：32 并发 `waitUntilActionable`（各自独立 challenge、2s 超时）+ completer 逐个 complete；断言全部及时返回、墙钟 < 10s；模拟多客户端挂 1s 轮询的 DB 压力。

### 4. `perf/BearerResolutionPerfTest.kt`（新，`@Tag("perf")`）

- [x] 预创建 32 个 authenticated challenge（`storeService` 存带 cookie/header 的 HttpClient 会话）。
- [x] 本地假上游 `HttpServer` 返回小 JSON，按远端端口统计 TCP 连接数。
- [x] 并发 C ∈ {1, 16, 64}：每轮 `getSession`（authenticate + payload SELECT + `SessionSerializer.restore`）→ 假上游 GET。**重要发现**：restored client 不能 `close()`——Ktor engine close 会 `evictAll()` 共享池空闲连接，杀死 keep-alive 复用；生产（`AuthServiceExecutor`）本就不 close，测试匹配生产语义。断言：全部 200、cookie 正确还原、**连接数 ≪ 请求数**（实测 1092 请求 / 64–118 连接）；打印端到端 p50/p95/p99。

### 5. `perf/AuthRoutePollingPerfTest.kt`（新，`@Tag("perf")`）

- [x] 复用 `AuthRoutesTest` 模式：`testApplication { application { mainModule(config().copy(authDbPath = tempDir...) ) } }`；**先**用同一 dbPath 自建 `ChallengeStore` 预置 authenticated challenges（WAL 多连接语义下 app 内 store 可见，绕开 AuthWorker 的 live network）。
- [x] 64 并发 GET `/api/auth/{id}`（`X-Challenge-Token` 头）轮询 + 32 并发 422 非法 start；断言全部 200、snapshot 字段完整（challenge_id/status/requested_services/ready_services/expires_in）、无 500；打印路由级延迟。**注意**：需先跑 warmup burst——首轮请求承担管道初始化成本（p95 906ms → 77ms），否则会误报性能问题。

### 6. 基线对比（可选，手动，不进 CI）

- [ ] `git worktree add` 到优化前提交（`d3d4acc^`），拷入 perf 测试文件，跑同一 `perfTest`，对比 ChallengeStore/Bearer 路径数字，量化阶段 1/2 优化收益。

## 验证

- [x] `./gradlew :bit-login-server:test` 绿（不含 perf 测试）。
- [x] `./gradlew :bit-login-server:perfTest` 绿并输出三个测试类的指标报告；总耗时 < 10s。
- [x] `./gradlew build` 全绿且不受 perf 测试影响。

## 实测基线数字（本机，供后续对比）

| 场景 | 吞吐 | p50 | p95 |
|---|---|---|---|
| store 混合 C=64 | ~8800 ops/s | 6.4ms | 19ms |
| 轮询风暴（32 pollers） | — | — | 263ms 墙钟 |
| bearer C=64 | ~2000 ops/s | 13.5ms | 119ms |
| bearer 连接复用 | 1092 请求 / 64–118 连接 | — | — |
| route 轮询 C=64（预热后） | ~2100 ops/s | 20ms | 78ms |

## 明确不做

- 不引入 JMH、k6/wrk 等新依赖（外部压测脚本如需另行手动编写）。
- 不设硬性性能阈值门槛（p99 < X ms 之类），避免 CI 因机器差异 flaky。
- 不压测数据路由的完整 HTTP 链路（上游 URL 硬编码无法重定向到假上游）。
- 不挂入 `check`/`build` 生命周期。
