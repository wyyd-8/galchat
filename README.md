# GalChat

GalChat 是一个面向角色聊天与互动故事的全栈项目。后端基于 Spring Boot，负责用户、世界、角色、剧情事件、聊天记忆、向量检索与 AI 回复编排；前端基于 Vue 3 + Vite，提供世界管理、角色管理、聊天与故事推进界面；`python/` 目录提供本地输入完整性判断和 rerank 辅助服务。

## 功能概览

- 用户注册、登录、邮箱验证码、资料维护与密码修改
- 世界模板、用户世界、世界详情的创建、编辑、导入和导出
- 角色模板、用户世界角色绑定、角色提示词和好感度相关能力
- 基于 DeepSeek 的角色回复生成，支持 SSE 流式输出
- WebSocket 实时聊天连接，支持按用户世界建立会话
- PostgreSQL + pgvector 向量检索，覆盖世界详情、聊天历史和世界事件
- 长期记忆、话题边界识别、聊天记录撤回和历史查询
- 互动故事事件的开启、推进、结束和归档
- Redis 队列、延迟任务、缓存和分布式锁
- 本地 Python BERT 服务用于输入完整性判断，本地 reranker 服务用于召回结果重排

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21, Spring Boot 4.0.5, Spring AI 2.0.0-M4, MyBatis-Plus |
| AI | DeepSeek Chat, Ollama Embedding |
| 数据 | PostgreSQL, pgvector, Redis, Redisson |
| 前端 | Vue 3, TypeScript, Vite, Element Plus |
| Python 辅助服务 | FastAPI, PyTorch, Transformers, jieba |
| 文件与通知 | Aliyun OSS, Aliyun Direct Mail |

## 项目结构

```text
.
|-- pom.xml                         # 后端 Maven 配置
|-- mvnw / mvnw.cmd                 # Maven Wrapper
|-- src
|   |-- main
|   |   |-- java/com/me/galchat
|   |   |   |-- GalchatApplication.java
|   |   |   |-- config/             # Web、Redis、WebSocket、AI、向量库等配置
|   |   |   |-- controller/         # REST API 控制器
|   |   |   |-- service/            # 业务服务接口与实现
|   |   |   |-- mapper/             # MyBatis Mapper
|   |   |   |-- domain/             # DTO、VO、PO 和统一响应
|   |   |   |-- websocket/          # WebSocket 入口
|   |   |   |-- consumer/           # Redis 队列消费者
|   |   |   |-- task/               # 定时任务
|   |   |   |-- vector/             # 向量写入与检索服务
|   |   |   |-- memory/             # 聊天记忆与话题边界
|   |   |   |-- tool/               # AI 工具调用
|   |   |   |-- interceptor/        # Token、DeepSeek 请求相关拦截器
|   |   |   |-- exception/          # 全局异常处理
|   |   |   `-- utils/              # JWT、OSS、上下文等工具
|   |   `-- resources
|   |       |-- application.yaml    # 本地默认配置
|   |       `-- mapper/             # MyBatis XML 映射
|   `-- test
|       `-- java/com/me/galchat/init/console.sql
|-- vue                              # 前端项目
|   |-- package.json
|   |-- vite.config.ts               # /api 与 /ws 开发代理
|   `-- src
`-- python
    |-- bert.py                      # 输入完整性判断服务，默认 localhost:8081
    `-- reranker_server.py           # rerank 服务，默认 localhost:8082
```

## 环境要求

- JDK 21
- Node.js 20.19+ 或 22.12+
- PostgreSQL，并启用 `pgvector` 扩展
- Redis
- Ollama，并准备 1024 维 embedding 模型
- Python 3.10+，仅在启用本地 BERT / reranker 服务时需要
- DeepSeek API Key
- Aliyun OSS / Aliyun Direct Mail 配置，仅在使用图片上传、邮箱验证码等能力时需要

默认配置位于 `src/main/resources/application.yaml`。其中包含本地开发用的数据库、Redis、DeepSeek、Ollama、OSS 和邮件配置。首次运行前请按自己的环境修改，生产环境请使用环境变量或外部配置覆盖敏感信息，不要提交真实密钥。

## 快速启动

### 1. 初始化数据库

创建 PostgreSQL 数据库后执行初始化脚本：

```bash
psql -h localhost -U <username> -d <database> -f src/test/java/com/me/galchat/init/console.sql
```

该脚本会创建业务表、索引以及 `vector` 扩展。后端启动后，Spring AI 的 `PgVectorStore` 还会初始化以下向量表：

- `world_detail_vector_store`
- `chat_history_vector_store`
- `world_event_vector_store`

### 2. 准备 Redis 和 Ollama

启动 Redis 后，准备默认 embedding 模型：

```bash
ollama pull qwen3-embedding:latest
```

当前向量配置固定为 1024 维。如果更换 embedding 模型，需要同步确认模型维度与 `VectorConfiguration` 中的 `dimensions(1024)` 保持一致。

### 3. 启动 Python 辅助服务

如果只想先跑通主流程，可以暂时不启动这两个服务；Java 侧在调用失败时会做降级处理。完整体验建议启动。

安装依赖：

```bash
pip install fastapi uvicorn torch transformers pydantic jieba
```

输入完整性判断服务，默认监听 `localhost:8081`：

```bash
python python/bert.py
```

该服务默认读取 `python/bert_model` 目录下的本地模型。

reranker 服务，默认监听 `localhost:8082`：

```bash
python python/reranker_server.py
```

默认模型为 `BAAI/bge-reranker-v2-m3`。如需使用本地模型目录：

```bash
RERANKER_MODEL_PATH=/path/to/bge-reranker-v2-m3 python python/reranker_server.py
```

Windows PowerShell 可使用：

```powershell
$env:RERANKER_MODEL_PATH="C:\path\to\bge-reranker-v2-m3"
python python/reranker_server.py
```

### 4. 启动后端

Windows：

```powershell
.\mvnw.cmd spring-boot:run
```

macOS / Linux：

```bash
./mvnw spring-boot:run
```

后端默认地址：

- HTTP API: `http://localhost:8080`
- WebSocket: `ws://localhost:8080/ws/{sid}`
- Actuator Health: `http://localhost:8080/actuator/health`

### 5. 启动前端

```bash
cd vue
npm install
npm run dev
```

Vite 默认运行在 `http://localhost:5173`。开发环境中，`vue/vite.config.ts` 会将：

- `/api` 代理到 `http://localhost:8080`
- `/ws` 代理到 `ws://localhost:8080`

前端也支持通过 `VITE_API_BASE_URL` 覆盖 API 根地址。

## 常用命令

后端测试：

```powershell
.\mvnw.cmd test
```

后端打包：

```powershell
.\mvnw.cmd clean package
```

如果本地没有准备 PostgreSQL、Redis、Ollama、DeepSeek 等测试依赖，可以先跳过测试：

```powershell
.\mvnw.cmd clean package -DskipTests
```

前端类型检查和构建：

```bash
cd vue
npm run type-check
npm run build
```

前端预览生产构建：

```bash
cd vue
npm run preview
```

## 主要接口

除登录、注册和注册邮箱验证码接口外，HTTP 接口默认需要在请求头携带 `token`。

| 模块 | 路径 |
| --- | --- |
| 用户 | `POST /user/login`, `POST /user/register`, `POST /user/register/email-code`, `GET/PUT /user/info`, `PUT /user/password` |
| 世界 | `/world/**` |
| 角色 | `/character/**` |
| 聊天 | `POST /ai/chat` |
| 聊天历史 | `GET /history`, `POST /history/withdraw` |
| 世界事件 | `/worldevent/story/**` |
| 图片上传 | `POST /upload` |
| WebSocket | `/ws/{sid}` |

## 配置说明

常用配置项如下，可直接修改 `application.yaml`，也可以用 Spring Boot 环境变量形式覆盖：

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres
SPRING_DATASOURCE_USERNAME=<postgres-user>
SPRING_DATASOURCE_PASSWORD=<postgres-password>

SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_DATABASE=0

SPRING_AI_DEEPSEEK_BASE_URL=https://api.deepseek.com
SPRING_AI_DEEPSEEK_API_KEY=<deepseek-api-key>
SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL=<deepseek-model>

SPRING_AI_OLLAMA_EMBEDDING_OPTIONS_MODEL=qwen3-embedding:latest

GALCHAT_ALIOSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
GALCHAT_ALIOSS_BUCKET_NAME=<bucket-name>
GALCHAT_ALIOSS_REGION=cn-beijing
ALIYUN_OSS_ACCESS_KEY_ID=<access-key-id>
ALIYUN_OSS_ACCESS_KEY_SECRET=<access-key-secret>
```

## 开发注意事项

- `application.yaml` 当前偏向本地开发配置，生产环境请使用外部配置管理数据库密码、DeepSeek Key、OSS Key 和 JWT 相关密钥。
- PostgreSQL 必须启用 `pgvector`，否则向量表和检索能力无法正常工作。
- Redis 承担缓存、队列、延迟任务和分布式锁能力，开发与部署时需要保持可用。
- Java 服务当前直接调用本机 `http://localhost:8081` 和 `http://localhost:8082`。如果 Python 服务部署在其他机器，需要同步调整 Java 侧配置或代码。
- 前端开发环境依赖 Vite 代理；生产部署时需要让前端静态资源能够访问后端 API 与 WebSocket 地址。
- 当前仓库没有提供 Dockerfile 或 docker-compose，部署流程以手动准备依赖、构建产物和启动服务为主。
