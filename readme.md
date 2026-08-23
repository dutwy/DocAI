# DocAI

DocAI 是一个基于 Spring Boot 3 / Spring Cloud、Vue 3 和 Docker Swarm 的文档智能处理系统。系统以 Gateway 为唯一默认入口，提供用户、文件、AI Agent/RAG、文档编辑与协同能力。

## 模块

| 模块 | 应用名 | 内部端口 | 职责 |
|---|---|---:|---|
| `common` | - | - | API 返回/异常契约、JWT、安全基础、请求关联 ID、共享 Redis 与 OpenAPI 配置 |
| `gateway-service` | `gateway` | 8080 | 路由、JWT 边界校验、可信身份头注入、限流、跨域、请求日志 |
| `user-service` | `user` | 8081 | 注册、登录、刷新/注销令牌、用户查询 |
| `file-service` | `file` | 8082 | 流式上传下载、分片会话、元数据与对象存储 |
| `ai-service` | `ai-service` | 8083 | 模型调用、Agent、RAG、异步任务和 AIOps 业务能力 |
| `document-service` | `document` | 8084 | 文档、版本、批注、评论、协同、ACL 和状态机 |
| `vue-test-app` | - | 5173（开发） | Vue 3 前端 |

生产部署只发布 Gateway；其余服务和 MySQL、Redis、RabbitMQ、MinIO、Nacos、Prometheus/Grafana 仅在 `docai-network` 内可达。

## 核心契约

所有 JSON API 使用统一响应：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1787486400000,
  "traceId": "8b83c776890b4bc58ed647c98d311e67"
}
```

- 业务失败使用与语义匹配的 HTTP 4xx/5xx，同时保留稳定的 `code`。
- Servlet 服务、Gateway 鉴权错误和 Controller 异常使用同一字段结构。
- 客户端可传 `X-Request-Id`；仅接受 8–128 位安全字符，否则服务生成新值。响应头和日志会返回同一 ID。
- Gateway 会移除外部传入的 `X-User-Id`、`X-Username`、`X-Role`、`X-Permission-Groups`，JWT 验证成功后再写入可信身份头。

## 入口与鉴权

Gateway 路由由 [`GatewayConfig.java`](gateway-service/src/main/java/com/javaee/gateway/config/GatewayConfig.java) 定义：

- `/api/users/**` → user
- `/api/files/**` → file
- `/api/ai/**`、`/api/skills/**`、`/ws/agent/**` → ai
- `/api/documents/**`、`/ws/collaborate/**` → document

公开 HTTP 接口只有用户注册、登录、刷新，CORS 预检和健康/Prometheus 端点；SockJS 握手公开，但身份在 STOMP `CONNECT` 帧校验。其他业务请求需要 `Authorization: Bearer <access-token>`。

## 文件传输

`file-service` 同时保留兼容 multipart 接口和受资源约束的流式/分片协议：

- `PUT /api/files/upload-stream`：原始请求体流式上传。
- `GET /api/files/download/{fileId}`：流式下载。
- `POST /api/files/upload-sessions`：创建分片会话。
- `POST /api/files/upload-chunk`：上传分片。
- `POST /api/files/merge-chunk`：合并会话。
- `DELETE /api/files/upload-sessions/{sessionId}`：取消会话。

文件大小、分片大小/数量、用户活跃会话数、并发流数、锁超时和会话清理由 `file.transfer.*` 统一限制。默认值见 [`application.yml`](file-service/src/main/resources/application.yml)。

## 文档一致性

文档表由 Flyway 管理：

- `V1__create_document_schema.sql` 创建文档、版本、协作者、批注和评论表。
- `V2__add_document_optimistic_lock.sql` 增加独立 `lock_version` 与索引。

客户端更新正文或状态时携带最近读取的 `lockVersion`；并发覆盖返回 HTTP 409。文档状态为 `DRAFT / ACTIVE / ARCHIVED / DELETED`，合法事件为 `ACTIVATE / ARCHIVE / RESTORE / DELETE`，转换规则集中在 `DocumentStateMachine`，归档/删除状态不可直接编辑。

旧数据库通过 `baseline-on-migrate=true`、baseline 版本 0 接入；不要手工修改 Flyway 已管理的表。

## 配置

1. 复制 `.env.example` 为 `.env`，填写所有空的必需变量。
2. `JWT_SECRET` 必须是至少 32 字节的随机值；轮换期间可临时设置 `JWT_PREVIOUS_SECRET`。
3. 浏览器来源使用 `GATEWAY_ALLOWED_ORIGIN_PATTERNS`，WebSocket 来源使用 `WEBSOCKET_ALLOWED_ORIGIN_PATTERNS`。
4. 日志级别使用 `LOG_LEVEL_ROOT`、`LOG_LEVEL_APP`；本地 profile 只覆盖数据库、Redis、RabbitMQ、MinIO 和服务直连地址。
5. Nacos 初始化结构来自根 `mysql-schema.sql`；DocAI 业务表不要混入该文件。

生产、部署和本地配置以仓库中的 POM、`application*.yml`、四份 `docker-compose-*.yml` 和 `.env.example` 为唯一事实来源，文档不复制这些文件的完整内容。

## 构建与测试

Windows：

```powershell
.\mvnw.cmd clean verify
cd vue-test-app
npm ci
npm run build
```

Linux/macOS：

```bash
./mvnw clean verify
cd vue-test-app
npm ci
npm run build
```

根 Maven 构建统一激活 `test` profile。测试 profile 使用 H2 并关闭外部发现、消息监听、真实 AI 请求与后台任务，根构建不依赖本机基础设施。禁止在交付脚本中加入 `-DskipTests`。

## 本地运行

先启动所需的 MySQL、Redis、RabbitMQ 和 MinIO，填写密钥，然后分别运行服务。例如：

```powershell
.\mvnw.cmd -pl user-service spring-boot:run "-Dspring-boot.run.profiles=local"
```

前端开发：

```powershell
cd vue-test-app
npm ci
npm run dev
```

详细使用方法见 [`INSTALLATION_AND_USAGE.md`](INSTALLATION_AND_USAGE.md)。

## 部署

Swarm 部署入口为 `deploy.sh`，它会先执行根 `clean verify`，再按统一 registry/tag 构建并推送五个服务镜像，完成数据库准备后部署基础设施、业务服务和监控栈。

```bash
cp .env.example .env
# 编辑 .env
chmod +x deploy.sh
./deploy.sh
```

详细安全边界、节点规划、凭据和故障处理见 [`DEPLOY.md`](DEPLOY.md)。镜像构建逻辑见 `build-docker.sh`。

## 日志与监控

- 所有服务继承 Actuator 和 Prometheus registry，只暴露 `health`、`info`、`prometheus`。
- Prometheus 通过 [`prometheus.yml`](prometheus.yml) 抓取各服务真实内部端口的 `/actuator/prometheus`。
- 监控 Compose 使用 Swarm config 将该文件挂载到 Prometheus，不使用空配置卷。
- 控制台日志统一包含时间、级别、应用名、`traceId`、线程和 logger；MyBatis 不再绕过 SLF4J 直接输出 SQL。
- AI 的 `/api/ai/aiops/**` 是业务级故障检测接口，不替代 Micrometer/Actuator 基础监控。

## 进一步文档

- [`ai-service/README.md`](ai-service/README.md)：Agent、RAG、模型与 AIOps。
- [`file-service/README.md`](file-service/README.md)：文件传输、对象存储和资源限制。
- [`RABBITMQ_IMPLEMENTATION.md`](RABBITMQ_IMPLEMENTATION.md)：事件交换机、路由键与验证方法。
- [`CONTRIBUTING.md`](CONTRIBUTING.md)：开发环境、验证命令与提交要求。
- [`SECURITY.md`](SECURITY.md)：安全支持范围与私密漏洞报告方式。
- Swagger UI/API docs 路径由各服务 `application.yml` 和共享 `SwaggerConfig` 定义；生产环境通常通过受控运维通道访问内部服务文档端点。

## 许可证

DocAI 使用 [`MIT License`](LICENSE) 发布。`ai-service` 内置的 PPT 模板保留其原始 MIT 许可证和作者声明。
