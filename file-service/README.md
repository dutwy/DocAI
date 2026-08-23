# File Service 模块

## 功能概述

File Service 是 DocAI 项目的文件处理服务，提供文件上传、下载、存储管理等核心功能。

## 技术栈

- Spring Boot 3.1.8
- Spring Cloud 2023.0.3
- Spring Cloud Alibaba Nacos Discovery
- MyBatis-Plus 3.5.5
- MySQL 8.0+
- MinIO 8.5.2 (对象存储)
- Spring Security

## 核心功能

1. **文件上传**
   - 支持单文件上传
   - 支持多文件上传
   - 支持大文件分片上传
   - 支持文件类型校验
   - 支持文件大小限制

2. **文件下载**
   - 支持文件直接下载
   - 支持文件预览
   - 支持文件分片下载

3. **文件管理**
   - 支持文件删除
   - 支持文件重命名
   - 支持文件移动
   - 支持文件复制

4. **文件元数据**
   - 支持文件元数据查询
   - 支持文件列表查询
   - 支持文件搜索

5. **存储管理**
   - 支持本地存储
   - 支持 MinIO 存储
   - 支持存储策略配置

## 项目结构

```
file-service/
├── Dockerfile
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/javaee/fileservice/   # controller/service/storage/mapper/config
    │   └── resources/                     # application.yml、file.sql、mapper
    └── test/                              # 单元测试与 application-test.yml
```

## 配置说明

### 1. 主配置文件 (application.yml)

- 服务器端口：8082
- 数据库连接配置
- MinIO 配置
- Nacos 服务发现配置

### 2. 环境配置

生产默认配置位于 `application.yml`；测试隔离配置位于
`src/test/resources/application-test.yml`。本地地址通过环境变量覆盖，不存在
`application-dev.yml` 或 `application-prod.yml`。

## API 接口

### 1. 文件上传接口
- `POST /api/files/upload` - 单文件上传
- `PUT /api/files/upload-stream` - 原始请求体流式上传
- `POST /api/files/upload-multiple` - 多文件上传
- `POST /api/files/upload-sessions` - 创建受限分片会话
- `POST /api/files/upload-chunk` - 分片上传
- `POST /api/files/merge-chunk` - 分片合并
- `DELETE /api/files/upload-sessions/{sessionId}` - 取消分片会话

### 2. 文件下载接口
- `GET /api/files/download/{fileId}` - 文件下载
- `GET /api/files/preview/{fileId}` - 文件预览

### 3. 文件管理接口
- `DELETE /api/files/{fileId}` - 文件删除
- `PUT /api/files/{fileId}/rename` - 文件重命名
- `PUT /api/files/{fileId}/move` - 文件移动
- `PUT /api/files/{fileId}/copy` - 文件复制

### 4. 元数据接口
- `GET /api/files/metadata/{fileId}` - 获取文件元数据
- `GET /api/files/list` - 获取文件列表
- `GET /api/files/search` - 搜索文件

## 部署说明

### 1. 本地部署

```bash
# 从项目根目录启动服务
./mvnw -pl file-service spring-boot:run -Dspring-boot.run.profiles=local
```

### 2. Docker 部署

```bash
# 在项目根目录执行完整测试并构建所有服务镜像
PUSH_IMAGES=true ./build-docker.sh

# 文件服务不直接发布端口，由网关统一接入
docker stack deploy --with-registry-auth -c docker-compose-services.yml docai-services
```

### 3. Swarm 部署

统一使用根 `deploy.sh` 和四份 `docker-compose-*.yml`；不要另建一份聚合 Compose，
避免镜像标签、网络和凭据配置漂移。

## 开发指南

### 1. 环境准备

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- MinIO 服务
- Nacos 服务

### 2. 数据库初始化

首次环境按部署流程初始化 `src/main/resources/file.sql`；文档服务表由 Flyway 自动迁移，
不要把两者合并为手工总脚本。

### 3. 代码规范

- 遵循 Java 代码规范
- 使用 Lombok 简化代码
- 使用 MyBatis-Plus 操作数据库
- 使用 Swagger 生成 API 文档

## 注意事项

1. **存储配置**
   - 生产环境中应配置 MinIO 集群
   - 应配置适当的存储策略和生命周期管理

2. **安全配置**
   - 生产环境中应启用 HTTPS
   - 应配置适当的文件访问权限
   - 应配置文件类型白名单

3. **性能优化**
   - 应配置文件缓存
   - 应配置数据库连接池
   - 应配置合适的文件分片大小

4. **监控配置**
   - 应配置文件服务监控
   - 应配置存储使用情况监控
   - 应配置文件操作日志

## 许可证

MIT License
