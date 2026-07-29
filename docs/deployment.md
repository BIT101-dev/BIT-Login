# 服务端部署文档

## 构建

```bash
./gradlew :bit-login-server:installDist
```

产物位于 `bit-login-server/build/install/bit-login-server/`，目录结构：

```
bit-login-server/
├── bin/
│   ├── bit-login-server        # Unix 启动脚本
│   └── bit-login-server.bat    # Windows 启动脚本
└── lib/                        # 所有依赖 JAR
```

打包分发：

```bash
tar -czf bit-login-server.tar.gz -C bit-login-server/build/install bit-login-server
```

## 环境要求

- JDK 17+（推荐 Eclipse Temurin 17 或 JetBrains Runtime 17）
- SQLite 3.x（由 JDBC 驱动内置，无需额外安装）
- POSIX 系统：`/tmp` 目录或配置的 `AUTH_DB_PATH` 父目录可写

## 运行

### 前台

```bash
JAVA_HOME=/path/to/jdk17 ./bin/bit-login-server
```

日志输出到 stdout，格式见 `resources/logback.xml`。

### 后台

```bash
nohup ./bin/bit-login-server > server.log 2>&1 &
```

### systemd 服务

`/etc/systemd/system/bit-login-server.service`：

```ini
[Unit]
Description=BIT Login Services API
After=network.target

[Service]
Type=simple
User=bitlogin
WorkingDirectory=/opt/bit-login-server
ExecStart=/opt/bit-login-server/bin/bit-login-server
Restart=on-failure
RestartSec=5
Environment=HOST=127.0.0.1
Environment=PORT=16384
Environment=BASE_URL=https://your-domain.example
Environment=AUTH_DB_PATH=/var/lib/bit-login/auth.db
Environment=REGISTRATION_JWT_PRIVATE_KEY_FILE=/etc/bit-login/ed25519-key.pem
Environment=REGISTRATION_JWT_ALLOWED_AUDIENCES=app1,app2

[Install]
WantedBy=multi-user.target
```

初始化目录：

```bash
mkdir -p /opt/bit-login-server /var/lib/bit-login /etc/bit-login
useradd --system --no-create-home --shell /usr/sbin/nologin bitlogin
chown -R bitlogin:bitlogin /opt/bit-login-server /var/lib/bit-login /etc/bit-login
chmod 750 /var/lib/bit-login /etc/bit-login
```

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HOST` | `0.0.0.0` | 监听地址。内网部署建议 `127.0.0.1` 配合反向代理。 |
| `PORT` | `16384` | 监听端口。 |
| `HTTP_CONNECT_TIMEOUT` | `5` | 上游 HTTP 连接超时（秒）。 |
| `HTTP_READ_TIMEOUT` | `25` | 上游 HTTP 响应读取超时（秒）。CAS 登录可能较长，建议不小于 20。 |
| `BASE_URL` | `https://login.bit101.flwfdd.xyz` | ICS 日历文件外链基础 URL。应设为用户可访问的 HTTPS 地址。 |
| `AUTH_DB_PATH` | `/tmp/bit-login/auth.db` | SQLite 数据库路径。用于持久化 challenge 状态、SMS 码和服务会话。数据库目录和文件会以 `0700`/`0600` 权限创建。 |
| `AUTH_CHALLENGE_TTL` | `300` | challenge 等待状态有效期（秒）。到达后状态变为 `expired`。 |
| `AUTH_SESSION_TTL` | `1800` | 认证成功后会话有效期（秒）。期间可通过 Bearer token 复用。 |
| `REGISTRATION_JWT_PRIVATE_KEY_FILE` | 空 | Ed25519 私钥路径（PKCS#8 PEM 格式）。不配置时注册 JWT 接口返回 503。 |
| `REGISTRATION_JWT_ALLOWED_AUDIENCES` | 空 | JWT audience 白名单，逗号分隔。不配置时注册 JWT 不可用。 |
| `REGISTRATION_JWT_TTL` | `300` | 注册 JWT 有效期（秒）。 |
| `REGISTRATION_JWT_ISSUER` | `bit-login` | JWT issuer 声明。 |
| `REGISTRATION_JWT_KEY_ID` | `registration-1` | JWT key ID（kid）。更换密钥时更新此值可使客户端区分新旧密钥。 |

空字符串与缺失等价，使用默认值。

## 数据库

使用 SQLite WAL 模式，默认路径 `/tmp/bit-login/auth.db`。

- 数据库目录和文件以 `0700`/`0600` 权限创建（POSIX 系统）
- 连接设置 `busy_timeout=10000` 和 `synchronous=NORMAL`
- WAL 模式下多进程可同时读
- 启动时自动检测并迁移旧 schema（删除旧表重建）
- 后台每分钟清理过期行
- 重启丢失 `/tmp` 下数据；生产环境应持久化到非临时路径

### 表结构

- `auth_challenges` — challenge 状态、服务列表、过期时间
- `auth_sms_codes` — 短信验证码（单次消费）
- `auth_service_sessions` — 各服务登录会话序列化结果

## ICS 文件存储

课程表 ICS 文件生成到 `AUTH_DB_PATH` 同级目录（基于路径推导），默认 `/tmp/bit-login/`。

- 后台每 30 秒清理生成超过 30 分钟的文件
- 服务启动时清理残留 `.ics` 文件
- 通过 `GET /tmp/{uuid}.ics` 下载，仅允许 `.ics` 后缀
- 下载 URL 基于 `BASE_URL` 配置拼接

## 反向代理（Nginx）

建议通过 Nginx 终止 TLS 并转发到后端：

```nginx
server {
    listen 443 ssl;
    server_name your-domain.example;

    ssl_certificate     /etc/ssl/certs/your-domain.pem;
    ssl_certificate_key /etc/ssl/private/your-domain.key;

    # ICS 文件下载缓存
    location /tmp/ {
        proxy_pass http://127.0.0.1:16384/tmp/;
        proxy_cache_valid 200 5m;
        add_header Cache-Control "public, max-age=300";
    }

    location / {
        proxy_pass http://127.0.0.1:16384;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 30s;
    }
}
```

服务本身无 TLS 能力，生产环境必须通过反向代理提供 HTTPS。

## 注册 JWT（Ed25519）

签发注册 token 需要 Ed25519 PKCS#8 PEM 私钥。生成：

```bash
openssl genpkey -algorithm ed25519 -out /etc/bit-login/ed25519-key.pem
chmod 600 /etc/bit-login/ed25519-key.pem
```

私钥内容示例：

```
-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEIP...
-----END PRIVATE KEY-----
```

对应公钥应部署到验证端（如 BIT101 后端）用于 JWT 验签。

`REGISTRATION_JWT_KEY_ID` 默认为 `registration-1`。密钥轮换时更新此值，并在验证端同时信任新旧 kid。

## 日志

日志由 Logback 配置，默认输出到 stdout，格式：

```
HH:mm:ss.SSS LEVEL logger - message
```

库日志级别：

- `org.eclipse.jetty` — WARN
- `io.netty` — WARN
- `io.ktor` — INFO

自定义 `logback.xml` 可覆盖 classpath 配置：

```bash
java -Dlogback.configurationFile=/path/to/logback.xml -jar ...
```

## 安全说明

- CORS 当前使用 `anyHost()` 允许任意来源。`AppConfig.allowedCorsOrigins` 已定义但未被 CORS 插件引用。生产环境应加固 CORS 规则。
- SQLite 数据库文件和目录以 `0700`/`0600` 权限创建；WAL 和 SHM 附属文件同样在连接时加固。
- 错误响应脱敏：password、token、sms_code 等敏感字段在错误信息中替换为 `[redacted]`。
- 建议以专用系统用户运行，不共享其他服务用户。
- challenge 使用 SHA-256 哈希存储 token，不保存明文。

## 健康检查

```bash
curl http://localhost:16384/
# {"message":"BIT Login Services API is running"}
```

返回 `200` 表示服务正常。可用于容器探针或负载均衡检查：

### Docker HEALTHCHECK

```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:16384/ || exit 1
```

### Kubernetes probe

```yaml
livenessProbe:
  httpGet:
    path: /
    port: 16384
  initialDelaySeconds: 10
  periodSeconds: 30
readinessProbe:
  httpGet:
    path: /
    port: 16384
  initialDelaySeconds: 5
  periodSeconds: 10
```

## 架构要点

```
请求 → Nginx (HTTPS) → Ktor Netty → AuthWorker (后台协程) → CAS SSO
                                    → ChallengeStore (SQLite WAL)
                                    → IcsFileStore (临时文件)
```

- Netty 引擎，非阻塞 I/O
- 认证在后台协程异步执行，不阻塞请求线程
- 服务会话序列化为 JSON 存入 SQLite，支持通过 Bearer token 复用已认证会话
- ICS 文件为内存映射文件存储，服务重启后丢失
