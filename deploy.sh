#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

required_variables=(
    DOC_AI_MANAGER_HOST DOC_AI_NODE1_HOST DOC_AI_NODE2_HOST DOC_AI_NODE3_HOST
    DOCAI_IMAGE_REGISTRY MYSQL_DATABASE MYSQL_USERNAME MYSQL_PASSWORD MYSQL_ROOT_PASSWORD
    REDIS_PASSWORD RABBITMQ_USERNAME RABBITMQ_PASSWORD RABBITMQ_DEFAULT_USER RABBITMQ_DEFAULT_PASS
    MINIO_ACCESS_KEY MINIO_SECRET_KEY MINIO_ROOT_USER MINIO_ROOT_PASSWORD
    NACOS_USERNAME NACOS_PASSWORD NACOS_PASSWORD_HASH NACOS_AUTH_TOKEN
    NACOS_AUTH_IDENTITY_KEY NACOS_AUTH_IDENTITY_VALUE JWT_SECRET
    DOC_AI_PASSWORD_HASH DOC_AI_ADMIN_EMAIL DOC_AI_USER_EMAIL DOC_AI_ADMIN_PHONE DOC_AI_USER_PHONE
)
for variable in "${required_variables[@]}"; do
    if [[ -z "${!variable:-}" ]]; then
        echo "缺少必要环境变量: ${variable}" >&2
        exit 1
    fi
done

if [[ ! "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]+$ ]] ||
   [[ ! "$MYSQL_USERNAME" =~ ^[A-Za-z0-9_]+$ ]] ||
   [[ ! "$NACOS_USERNAME" =~ ^[A-Za-z0-9_.-]+$ ]]; then
    echo "MYSQL_DATABASE、MYSQL_USERNAME 或 NACOS_USERNAME 包含不允许的字符" >&2
    exit 1
fi

DOCAI_IMAGE_TAG="${DOCAI_IMAGE_TAG:-1.0.0}"
export DOCAI_IMAGE_REGISTRY DOCAI_IMAGE_TAG

echo "=== DocAI 项目部署脚本 ==="

# 设置变量
MANAGER_IP="${DOC_AI_MANAGER_HOST}"

# 步骤 1: 构建项目
echo "步骤 1-2: 完整测试、打包、构建并推送镜像..."
PUSH_IMAGES=true ./build-docker.sh

# 步骤 3: 初始化 Docker Swarm 集群
echo "\n步骤 3: 初始化 Docker Swarm 集群..."

# 检查是否已初始化 Swarm
SWARM_STATUS=$(docker info --format '{{.Swarm.LocalNodeState}}')

if [ "$SWARM_STATUS" != "active" ]; then
    echo "初始化 Docker Swarm 集群..."
    docker swarm init --advertise-addr "$MANAGER_IP"
    
    echo "Swarm 集群初始化成功！"
    echo "请在其他节点上执行以下命令加入集群："
    docker swarm join-token worker
else
    echo "Swarm 集群已初始化，跳过此步骤"
fi

# 步骤 4: 创建网络和数据卷
echo "\n步骤 4: 创建网络和数据卷..."

# 创建网络
if [[ -z "$(docker network ls --filter name='^docai-network$' --format '{{.Name}}')" ]]; then
    docker network create --driver overlay --attachable docai-network
    echo "创建网络 docai-network 成功"
else
    echo "网络 docai-network 已存在，跳过此步骤"
fi

# 创建数据卷
for volume in mysql-data redis-data minio-data nacos-data; do
    if [[ -z "$(docker volume ls --filter "name=^${volume}$" --format '{{.Name}}')" ]]; then
        docker volume create "$volume"
        echo "创建数据卷 $volume 成功"
    else
        echo "数据卷 $volume 已存在，跳过此步骤"
    fi
done

# 步骤 5: 部署基础设施服务
echo "\n步骤 5: 部署基础设施服务..."
docker stack deploy --with-registry-auth -c docker-compose-infra.yml docai-infra

echo "基础设施服务部署成功！"
# 步骤 6: 数据库初始化
echo "\n步骤 6: 数据库初始化..."

# 等待 MySQL 容器出现且真正可接受连接，最长约 120 秒。
MYSQL_CONTAINER_ID=""
for attempt in $(seq 1 60); do
    MYSQL_CONTAINER_ID="$(docker ps --filter label=com.docker.swarm.service.name=docai-infra_mysql --format '{{.ID}}' | head -n 1)"
    if [[ -n "$MYSQL_CONTAINER_ID" ]] &&
       docker exec "$MYSQL_CONTAINER_ID" mysqladmin ping -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; then
        break
    fi
    MYSQL_CONTAINER_ID=""
    sleep 2
done

if [[ -z "$MYSQL_CONTAINER_ID" ]]; then
    echo "MySQL 在等待期限内未就绪，终止部署" >&2
    docker service ps docai-infra_mysql --no-trunc >&2 || true
    exit 1
fi

echo "MySQL 服务已就绪，执行数据库初始化..."

# 使用 root 初始化业务库，并为非 root 应用账号授权。
docker exec -i "$MYSQL_CONTAINER_ID" mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" << EOF
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USERNAME}'@'%';
USE \`${MYSQL_DATABASE}\`;

CREATE TABLE IF NOT EXISTS \`user\` (
  \`id\` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  \`username\` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
  \`password\` VARCHAR(100) NOT NULL COMMENT '密码（加密存储）',
  \`email\` VARCHAR(100) NOT NULL UNIQUE COMMENT '邮箱',
  \`phone\` VARCHAR(20) COMMENT '手机号',
  \`nickname\` VARCHAR(50) COMMENT '昵称',
  \`avatar\` VARCHAR(255) COMMENT '头像',
  \`gender\` TINYINT DEFAULT 0 COMMENT '性别：0-未知，1-男，2-女',
  \`status\` TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  \`role\` VARCHAR(20) DEFAULT 'user' COMMENT '角色：admin，user',
  \`create_time\` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  \`update_time\` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (\`id\`),
  INDEX \`idx_username\` (\`username\`),
  INDEX \`idx_email\` (\`email\`),
  INDEX \`idx_status\` (\`status\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

INSERT INTO \`user\` (\`username\`, \`password\`, \`email\`, \`phone\`, \`nickname\`, \`avatar\`, \`gender\`, \`status\`, \`role\`) VALUES
('admin', '${DOC_AI_PASSWORD_HASH}', '${DOC_AI_ADMIN_EMAIL}', '${DOC_AI_ADMIN_PHONE}', '管理员', NULL, 0, 1, 'admin'),
('user', '${DOC_AI_PASSWORD_HASH}', '${DOC_AI_USER_EMAIL}', '${DOC_AI_USER_PHONE}', '测试用户', NULL, 0, 1, 'user')
ON DUPLICATE KEY UPDATE password=VALUES(password), email=VALUES(email), phone=VALUES(phone), status=1;

UPDATE nacos_config.users
SET username='${NACOS_USERNAME}', password='${NACOS_PASSWORD_HASH}', enabled=TRUE
WHERE username='nacos' OR username='${NACOS_USERNAME}';
UPDATE nacos_config.roles
SET username='${NACOS_USERNAME}', role='ROLE_ADMIN'
WHERE username='nacos' OR username='${NACOS_USERNAME}';
FLUSH PRIVILEGES;
EOF

echo "数据库初始化成功！"

# 步骤 7: 部署微服务
echo "\n步骤 7: 部署微服务..."
docker stack deploy --with-registry-auth -c docker-compose-services.yml docai-services

echo "微服务部署成功！"
echo "等待 30 秒，让服务启动..."
sleep 30

# 步骤 8: 验证部署
echo "\n步骤 8: 验证部署..."

# 检查服务状态
echo "\n检查基础设施服务状态："
docker service ls | grep docai-infra

echo "\n检查微服务状态："
docker service ls | grep docai-services

# 显示访问地址
echo "\n=== 部署完成 ==="
echo "访问地址："
echo "- API 网关：http://$MANAGER_IP:8080"
echo "基础设施和内部微服务没有发布宿主机端口；管理操作请使用受控跳板机或临时 SSH 隧道。"

echo "\n部署脚本执行完成！"
