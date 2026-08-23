# 贡献指南

感谢参与 DocAI。提交改动前，请先创建独立分支，并保持一次提交只解决一个明确问题。

## 开发环境

- JDK 17
- Maven Wrapper（仓库已提供）
- Node.js 22.12+
- Docker 及 Docker Compose（验证部署配置时需要）

## 验证

后端：

```bash
./mvnw verify
```

前端：

```bash
cd vue-test-app
npm ci
npm audit --omit=dev --audit-level=high
npm run build
```

部署配置：

```bash
bash -n deploy.sh build-docker.sh
docker compose --env-file .env.example -f docker-compose-infra.yml config --quiet
docker compose --env-file .env.example -f docker-compose-services.yml config --quiet
docker compose --env-file .env.example -f docker-compose-gate-user.yml config --quiet
docker compose --env-file .env.example -f docker-compose-monitoring.yml config --quiet
```

## 提交要求

- 不提交真实 `.env`、密钥、令牌、用户数据或内部服务器地址；
- 新增功能或修复缺陷时补充相应测试；
- 保持 API、数据库迁移和部署文档同步；
- 不在构建或部署脚本中跳过测试；
- 提交 Pull Request 前确保 CI 全部通过。

安全问题请按照 [`SECURITY.md`](SECURITY.md) 私密报告。
