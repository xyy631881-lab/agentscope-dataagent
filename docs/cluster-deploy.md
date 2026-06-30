# DataAgent cluster deployment

This guide shows a minimal 3-replica deployment with shared workspace and
Redis coordination. The same shape works on Kubernetes (StatefulSet behind a
Service, PVC for the workspace, a Redis Deployment) — the docker-compose
sample below is the smallest reproducible example.

## 跨副本需要共享的内容

| 子系统 | 共享方式 | 原因 |
|---|---|---|
| 每用户工作区（`memory/`、`sessions/`、`tasks/`、`skills/` 等） | Redis 后端的 `RemoteFilesystem`（`dataagent.session.redis.enabled=true`） | 路由到 R2 的用户必须能看到 R1 对同一 `(userId, agentId)` 命名空间写入的文件。 |
| Sandbox 状态 / Session 快照 | Redis（同一标志） | 路由到 R2 的用户必须能恢复 R1 启动的 sandbox。 |
| Tool-event SSE | Redis Pub/Sub（启用 Redis 时自动生效） | R2 上的 SSE 订阅者必须能看到 R1 上运行 Agent 触发的 tool call。 |
| 用户通道绑定 | Redis hash（启用 Redis 时自动生效） | 在 R1 上设置的偏好应在 R2 上立即生效。 |
| JPA 表（`dataagent_user`、`dataagent_agent`、`dataagent_contribution`） | 共享 RDBMS（MySQL / PostgreSQL，通过 `jdbc` Profile） | H2 默认仅单节点；集群部署必须让所有副本指向同一外部数据库。 |
| `${dataagent.workspace}/.agentscope/shared/` | 共享文件系统（NFS / EFS） | OverlayFilesystem 的下层；所有副本必须读取同一组批准的贡献。任意副本的审批写入必须对所有副本可见。 |
| 内存日志 SSE | （不共享 —— 单副本视图） | 管理员的"实时日志"仅显示正在服务该连接的副本的事件。此为设计行为。 |

## docker-compose 示例

```yaml
# docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes"]
    volumes: ["redis-data:/data"]

  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set me}
      MYSQL_DATABASE: dataagent
      MYSQL_USER: dataagent
      MYSQL_PASSWORD: ${DATAAGENT_DB_PASSWORD:?set me}
    volumes: ["mysql-data:/var/lib/mysql"]

  # NFS 后端的共享工作区；在实际部署中这是 EFS / Filestore
  # / Azure Files 挂载点，而非本地绑定。
  workspace-init:
    image: alpine
    command: ["sh", "-c", "mkdir -p /workspace/.agentscope/shared && chown -R 1000:1000 /workspace"]
    volumes: ["dataagent-workspace:/workspace"]

  dataagent-1: &dataagent
    image: agentscope/dataagent:latest
    depends_on: [redis, mysql, workspace-init]
    environment:
      DATAAGENT_JWT_SECRET: ${DATAAGENT_JWT_SECRET:?set me}
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY:?set me}
      DATAAGENT_WORKSPACE: /workspace
      SPRING_PROFILES_ACTIVE: prod,jdbc
      DATAAGENT_DB_URL: jdbc:mysql://mysql:3306/dataagent?useSSL=false&serverTimezone=UTC
      DATAAGENT_DB_USER: dataagent
      DATAAGENT_DB_PASSWORD: ${DATAAGENT_DB_PASSWORD:?set me}
      DATAAGENT_JPA_DDL_AUTO: validate
      DATAAGENT_SESSION_REDIS_ENABLED: "true"
      DATAAGENT_SESSION_REDIS_HOST: redis
      DATAAGENT_SESSION_REDIS_PORT: "6379"
    volumes: ["dataagent-workspace:/workspace"]

  dataagent-2: { <<: *dataagent }
  dataagent-3: { <<: *dataagent }

  lb:
    image: nginx:alpine
    depends_on: [dataagent-1, dataagent-2, dataagent-3]
    ports: ["8080:80"]
    volumes: ["./nginx.conf:/etc/nginx/nginx.conf:ro"]

volumes:
  redis-data:
  mysql-data:
  dataagent-workspace:
    driver_opts:
      type: nfs
      o: "addr=nfs.internal,rw,nfsvers=4"
      device: ":/exports/agentscope"
```

最小 `nginx.conf`（轮询；不需要 session affinity，因为 session 状态在 Redis 中）：

```nginx
events {}
http {
  upstream dataagent_replicas {
    server dataagent-1:8080;
    server dataagent-2:8080;
    server dataagent-3:8080;
  }
  server { listen 80; location / { proxy_pass http://dataagent_replicas; } }
}
```

## 验证集群

1. **绑定** — 在 R1 上创建绑定，同一秒内在 R2 上获取：

   ```bash
   curl -X POST -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
     -d '{"channelId":"chatui","language":"zh-CN"}' \
     http://dataagent-1:8080/api/user/bindings
   curl -H "Authorization: Bearer $JWT" http://dataagent-2:8080/api/user/bindings
   # → 返回在 dataagent-1 上创建的绑定
   ```

2. **Session 连续性** — 第 1 轮发送到 R1，第 2 轮发送到 R2：

   ```bash
   curl -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"message":"Hi, my name is Alice"}' \
     http://dataagent-1:8080/api/chat/send
   curl -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"message":"What name did I just give you?"}' \
     http://dataagent-2:8080/api/chat/send
   # → R2 的回复引用了 "Alice"
   ```

3. **能力市场贡献传播** — 在 R1 上提交贡献，以管理员身份在 R2 上审批，
   确认 R3 上的对话在下一次 session 重置时能看到新技能：

   ```bash
   curl -X POST -H "$USER_AUTH" -H 'Content-Type: application/json' \
     -d '{"targetType":"skill","targetPath":"cohort-builder/SKILL.md","payload":"# Cohort builder\n..."}' \
     http://dataagent-1:8080/api/me/contributions
   # → { "id": 42, ... "status": "PENDING" }

   curl -X POST -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
     -d '{"note":"looks good"}' \
     http://dataagent-2:8080/api/admin/contributions/42/approve
   # → 200; 文件写入 /workspace/.agentscope/shared/skills/cohort-builder/SKILL.md
   # → 对 dataagent-3 可见，因为所有副本挂载了同一 NFS 卷
   ```

4. **跨副本 SSE** — 在 R2 上打开 SSE 流，从 R1 发送对话，观察 `TOOL_CALL` 事件的传播。

## 启动前置检查（配置错误会直接拒绝启动）

如果以下任何一项配置错误，启动会直接拒绝启动：

- `dataagent.jwt.secret` left at the dev placeholder in a non-`dev` profile.
- `dataagent.workspace` blank in a non-`dev` profile.
- `dataagent.session.redis.enabled=true` with `dataagent.workspace` pointing
  at an ephemeral path (`/tmp/`, `/var/tmp/`, `/private/tmp/`, `/dev/shm/`).
- `spring.jpa.hibernate.ddl-auto=update` 在集群中对 MySQL/PostgreSQL：使用 Flyway/Liquibase 固定 schema 并设置 `DATAAGENT_JPA_DDL_AUTO=validate`。

这些是有意为之：集群模式下静默配置错误会以难以事后恢复的方式破坏用户状态。

## 仍在单副本上保留的内容

- 管理员"实时日志"SSE（`/api/admin/runtime/logs`）—— 仅能看到管理员恰好连接到的副本上的事件。此为设计行为，非缺陷。
- 启动时打印的部署 banner 描述了此特定副本上哪些子系统支持集群感知 —— 在启用 Redis 后阅读一次，确认所有期望的子系统已就绪。
