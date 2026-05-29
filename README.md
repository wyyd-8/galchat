# GalChat

GalChat 是一个基于 Spring Boot 的角色聊天与互动故事后端服务。项目围绕“用户世界”“角色模板”“长期记忆”“剧情事件”和“主动关怀”组织业务，通过 DeepSeek 聊天模型生成角色回复，结合 PostgreSQL + pgvector 做背景、历史和世界事件检索，并使用 Redis/Redisson 处理输入合并、延时队列、会话状态和缓存。

## 功能概要

- 用户注册、登录、资料与密码管理。
- 世界模板、世界详情、用户世界实例的创建与维护。
- 角色模板导入、用户世界角色绑定、好感度记录与更新。
- WebSocket 实时聊天，支持分片输入、输入完成判断、异步生成回复。
- Spring AI + DeepSeek 角色回复，支持工具调用、长期记忆检索和好感度更新。
- PostgreSQL pgvector 向量检索，覆盖世界详情、聊天历史和世界事件。
- 互动故事事件的开启、推进、结束和总结入库。
- 用户事件识别与延迟主动关怀推送。
- 本地 Python 服务提供 BERT 输入完整性判断和文档 rerank 能力。

## 技术栈

- Java 21
- Spring Boot 4.0.5
- Spring AI 2.0.0-M4
- Maven Wrapper
- MyBatis-Plus
- PostgreSQL + pgvector
- Redis + Redisson
- DeepSeek Chat Model
- Ollama Embedding Model
- WebSocket
- Python FastAPI / PyTorch / Transformers
- Aliyun OSS

## 项目结构

```text
.
├── pom.xml                         # Maven 项目配置与 Java 依赖
├── mvnw / mvnw.cmd                 # Maven Wrapper
├── HELP.md                         # Spring Initializr 生成的参考文档
├── src
│   ├── main
│   │   ├── java/com/me/galchat
│   │   │   ├── GalchatApplication.java      # Spring Boot 启动入口
│   │   │   ├── config/                      # Web、Redis、WebSocket、AI、向量库等配置
│   │   │   ├── controller/                  # REST API 控制器
│   │   │   ├── service/                     # 业务服务接口与实现
│   │   │   ├── mapper/                      # MyBatis-Plus Mapper
│   │   │   ├── domain/                      # DTO、VO、PO 与统一响应对象
│   │   │   ├── websocket/                   # WebSocket 会话与消息处理
│   │   │   ├── consumer/                    # Redis 队列消费者
│   │   │   ├── task/                        # 定时任务
│   │   │   ├── vector/                      # 向量写入与检索服务
│   │   │   ├── memory/                      # 对话记忆与话题边界处理
│   │   │   ├── tool/                        # AI 工具调用
│   │   │   ├── interceptor/                 # Token 拦截与 DeepSeek 请求拦截
│   │   │   ├── exception/                   # 全局异常处理
│   │   │   ├── constant/                    # 常量定义
│   │   │   └── utils/                       # JWT、OSS、上下文等工具类
│   │   └── resources
│   │       ├── application.yaml             # 本地默认配置
│   │       └── mapper/                      # MyBatis XML 映射文件
│   └── test
│       ├── java/com/me/galchat/GalchatApplicationTests.java
│       └── java/com/me/galchat/init/console.sql  # 数据库初始化 SQL
└── python
    ├── bert.py                      # 输入完整性判断服务，默认端口 8081
    ├── reranker_server.py           # 本地 reranker 服务，默认端口 8082
    └── bert_model/                  # 本地 BERT 模型文件
```

## 主要接口

默认服务端口为 Spring Boot 默认的 `8080`。除 `/user/login` 和 `/user/register` 外，HTTP 接口默认需要在请求头携带 `token`。

| 模块 | 路径 |
| --- | --- |
| 用户 | `/user/login`, `/user/register`, `/user/info`, `/user/password` |
| 聊天 | `/ai/chat` |
| 聊天历史 | `/history` |
| 世界 | `/world/**` |
| 角色 | `/character/**` |
| 世界事件 | `/worldevent/**` |
| WebSocket | `/ws/{sid}` |

## 部署流程

### 1. 准备基础环境

安装以下运行依赖：

- JDK 21
- PostgreSQL，并安装 `pgvector` 扩展
- Redis
- Ollama，并拉取 embedding 模型：

```bash
ollama pull quentinz/bge-large-zh-v1.5
```

如需启用本地 BERT 完整性判断和 reranker，还需要 Python 3.10+，并安装依赖：

```bash
pip install fastapi uvicorn torch transformers pydantic jieba
```

### 2. 初始化数据库

创建 PostgreSQL 数据库后，执行初始化 SQL：

```bash
psql -h localhost -U <username> -d <database> -f src/test/java/com/me/galchat/init/console.sql
```

该脚本会创建 `vector` 扩展、业务表和索引。项目启动后，Spring AI 的 `PgVectorStore` 还会初始化以下向量表：

- `world_detail_vector_store`
- `chat_history_vector_store`
- `world_event_vector_store`

### 3. 配置运行参数

本地默认配置位于 `src/main/resources/application.yaml`。部署到服务器时建议使用环境变量或外部配置覆盖敏感信息，不要把真实密钥提交到仓库。

常用配置项：

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres
export SPRING_DATASOURCE_USERNAME=<postgres-user>
export SPRING_DATASOURCE_PASSWORD=<postgres-password>

export SPRING_DATA_REDIS_HOST=localhost
export SPRING_DATA_REDIS_PORT=6379
export SPRING_DATA_REDIS_DATABASE=0

export SPRING_AI_DEEPSEEK_BASE_URL=https://api.deepseek.com
export SPRING_AI_DEEPSEEK_API_KEY=<deepseek-api-key>
export SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL=<deepseek-model>

export SPRING_AI_OLLAMA_EMBEDDING_OPTIONS_MODEL=quentinz/bge-large-zh-v1.5

export GALCHAT_ALIOSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
export GALCHAT_ALIOSS_BUCKET_NAME=<bucket-name>
export GALCHAT_ALIOSS_REGION=cn-beijing
export ALIYUN_OSS_ACCESS_KEY_ID=<access-key-id>
export ALIYUN_OSS_ACCESS_KEY_SECRET=<access-key-secret>
```

### 4. 启动 Python 辅助服务

完整性判断服务默认监听 `localhost:8081`：

```bash
python python/bert.py
```

reranker 服务默认监听 `localhost:8082`：

```bash
python python/reranker_server.py
```

`reranker_server.py` 默认使用 `BAAI/bge-reranker-v2-m3`，可通过 `RERANKER_MODEL_PATH` 指向本地模型目录：

```bash
RERANKER_MODEL_PATH=/path/to/bge-reranker-v2-m3 python python/reranker_server.py
```

如果这两个服务未启动，Java 服务会降级处理：BERT 判断失败时使用 3 秒兜底任务，reranker 失败时返回向量召回的原始顺序。

### 5. 构建 Java 服务

```bash
./mvnw clean package
```

如果测试环境没有准备 PostgreSQL、Redis、Ollama、DeepSeek 等外部依赖，可以先跳过测试构建：

```bash
./mvnw clean package -DskipTests
```

### 6. 启动应用

```bash
java -jar target/galchat-0.0.1-SNAPSHOT.jar
```

服务启动后：

- HTTP API 地址：`http://localhost:8080`
- WebSocket 地址：`ws://localhost:8080/ws/{sid}`
- 健康检查可使用 Spring Boot Actuator 默认端点，例如 `http://localhost:8080/actuator/health`

## 本地开发

直接运行：

```bash
./mvnw spring-boot:run
```

运行测试：

```bash
./mvnw test
```

## 部署注意事项

- `application.yaml` 中的本地配置仅适合开发环境，生产环境请使用外部配置管理数据库密码、DeepSeek Key、OSS Key 和 JWT 密钥。
- PostgreSQL 必须启用 `pgvector`，否则向量库初始化会失败。
- Ollama embedding 模型维度需要与 `VectorConfiguration` 中的 `dimensions(1024)` 保持一致。
- Java 服务当前固定调用本机 `http://localhost:8081` 和 `http://localhost:8082`，如果 Python 服务部署在其他机器，需要同步调整 Java 侧地址配置或代码。
- Redis 承担队列、延迟任务、缓存和分布式锁能力，部署时需要保证 Redis 可用且数据淘汰策略不会误删关键队列。
- 已补充完整容器化部署文件，见下方“Docker 部署”。

## Docker 部署

项目现在提供了完整的容器编排，包含以下服务：

- `frontend`：Vue 前端，使用 Nginx 托管静态资源并反向代理 `/api`、`/ws`
- `backend`：Spring Boot 服务
- `bert-service`：输入完整性判断服务
- `reranker-service`：文档 rerank 服务
- `ollama`：Embedding 模型服务，启动时自动拉取模型
- `postgres`：PostgreSQL + pgvector
- `redis`：Redis

### 1. 准备环境变量

复制示例文件并按实际环境填写：

```bash
cp .env.docker.example .env
```

至少需要确认这些值：

- `POSTGRES_PASSWORD`
- `SPRING_AI_DEEPSEEK_API_KEY`
- `ALIYUN_OSS_ACCESS_KEY_ID`
- `ALIYUN_OSS_ACCESS_KEY_SECRET`

如需调整 embedding 模型，可修改：

```bash
OLLAMA_EMBEDDING_MODEL=qwen3-embedding:latest
SPRING_AI_OLLAMA_EMBEDDING_OPTIONS_DIMENSIONS=1024
```

### 2. 启动整套服务

```bash
docker compose up -d --build
```

首次启动会做几件事：

- `postgres` 自动执行 [`src/test/java/com/me/galchat/init/console.sql`](src/test/java/com/me/galchat/init/console.sql)
- `ollama` 自动拉取 `OLLAMA_EMBEDDING_MODEL` 指定的模型
- `reranker-service` 首次运行时会下载 `BAAI/bge-reranker-v2-m3`，下载时间取决于网络

### 3. 访问地址

- 前端：http://localhost
- 后端 API：http://localhost:8080
- WebSocket：ws://localhost/ws/{sid}
- Ollama：http://localhost:11434

### 4. 常用命令

查看日志：

```bash
docker compose logs -f backend
docker compose logs -f ollama
docker compose logs -f reranker-service
```

停止服务：

```bash
docker compose down
```

连同数据卷一起删除：

```bash
docker compose down -v
```

### 5. 部署说明

- 前端通过 [`docker/frontend/nginx.conf`](docker/frontend/nginx.conf) 代理后端接口，不需要额外配置 `VITE_API_BASE_URL`
- Spring Boot 现在支持通过环境变量覆盖数据库、Redis、Ollama、DeepSeek、OSS、Python 服务地址
- Java 侧原本写死的 `http://localhost:8081` 和 `http://localhost:8082` 已改为 `GALCHAT_BERT_URL`、`GALCHAT_RERANKER_URL`
- 两个 Python 服务默认监听 `0.0.0.0`，可直接被其他容器访问
- 当前 Compose 默认按 CPU 方式运行；如果服务器有 GPU，建议再按实际环境补充 Ollama / PyTorch 的 GPU 配置
