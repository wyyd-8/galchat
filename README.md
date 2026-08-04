# GalChat

GalChat 是一个面向角色聊天与互动故事的全栈项目。它不是只把用户消息转发给大模型，而是围绕“一个可持续演进的用户世界”做了状态管理：角色有好感度和个人提示词，聊天会形成长期记忆，世界事件会进入时间线，用户可以在关键分支前存档，也可以撤回上一轮对话并回滚由这轮对话带来的副作用。

> 当前代码基线的已完成模块、验证结果与实现边界，见[《GalChat 已完成开发模块说明》](docs/development-completion-summary.md)。
>
> 后端 REST、SSE、WebSocket 与 Python 辅助服务的完整调用说明，见[《GalChat 后端接口文档》](docs/backend-api.md)。

后端基于 Spring Boot + Spring AI，当前新版前端位于 `reka/`，基于 Vue 3 + Reka UI；`vue/` 保留为旧版界面。另有两个 Python 辅助服务用于输入完整性判断和检索结果重排。

## 项目特色

### 世界级存档 / 读档

GalChat 的存档不是简单记录一段聊天文本，而是为一个用户世界生成可恢复快照。快照会覆盖：

- 聊天历史边界：记录当前最大的聊天、好感、用户事件、世界事件和剧情事件 ID。
- 角色状态：保存角色头像、名称、最近聊天内容、好感值和用户自定义提示词。
- 话题边界：保存每个角色当前话题窗口、上一话题窗口和活跃剧情事件位置。
- 最近聊天轮次：保留每个角色最近 3 轮聊天，以及对应的思考记录、工具调用记录和好感日志。
- 世界事件与剧情事件：保存最后一条世界事件日志，以及当前进行中的故事事件和参与角色。

读档时，系统会删除存档点之后产生的聊天、思考、工具调用、好感日志、用户事件、世界事件和剧情事件，再恢复快照中的角色状态、最近轮次、话题边界、世界事件向量等派生数据。为了避免读档和剧情推进互相踩状态，存档与读档都会对相关角色加 Redisson 分布式锁。

接口位置：

- `GET /world-saves/{userWorldId}`：查询当前世界存档概览。
- `POST /world-saves/{userWorldId}`：保存当前世界进度，可附带备注。
- `POST /world-saves/{userWorldId}/load`：读取存档并回滚当前世界。

前端在世界总览页提供“存档/读档”面板，读档前会展示存档时间、备注和角色好感快照。

### 对话撤回与副作用回滚

撤回功能针对“上一轮用户消息”设计，而不是只删 UI 上最后一条气泡。一次撤回会同步处理：

- 将用户消息标记为已撤回，并清空原始内容。
- 删除这条用户消息触发的助手回复。
- 删除 DeepSeek 思考内容、工具调用记录和自动检索提示。
- 反向扣回本轮工具调用产生的角色好感变化。
- 清理相关 Redis 快捷缓存，包括 stepNo、角色最近回复和提示词缓存。
- 如果话题边界引用了被撤回的消息，会同步清理边界。
- 刷新角色的最近聊天时间和最近聊天内容。

为了防止连续回滚造成记忆窗口混乱，当前限制最多连续撤回 3 条用户消息。撤回过程同样会尝试获取角色会话锁，如果剧情切换或结束正在进行，会拒绝撤回。

接口位置：

- `POST /history/withdraw?userworldid={id}&characterid={id}`

### 话题感知长期记忆

项目实现了自定义的 `TopicAwareMessageChatMemoryAdvisor`。每次对话前，它会先保存用户消息，再根据话题边界决定当前上下文窗口，并自动拼接：

- 当前话题窗口内的聊天历史。
- 本轮消息和上一轮上下文构造出的检索查询。
- 来自世界详情、聊天历史和世界事件的向量召回结果。
- 可选的角色首轮特殊提示词。

这样可以让模型既保持当前对话的连贯性，又能在需要时引用更早的剧情、设定或世界事件。检索结果来自 PostgreSQL + pgvector，并可通过本地 reranker 服务重排。

### 互动故事事件

世界中可以开启、推进和结束故事事件。一个故事事件会绑定多个角色，并影响这些角色的聊天话题边界。

- 开启事件：根据主题、开场和世界详情生成事件标题、当前场景和开场文本。
- 推进事件：根据用户填写的转场描述生成新的场景推进，并写入参与角色的聊天历史。
- 结束事件：汇总事件过程，生成结局摘要，写入世界事件日志，并将世界事件向量化，供后续聊天检索。

故事事件操作会锁定参与角色，避免角色正在聊天时剧情事件被并发改写。

### 角色好感度工具

模型可以通过 Spring AI Tool 调整当前角色对用户的好感度。好感变化会绑定到具体用户消息，因此：

- 聊天时可以根据用户表达自动加减好感。
- 撤回消息时可以反向回滚好感变化。
- 存档时会保存每个角色的好感快照，读档时恢复。
- 世界总览页可以展示角色好感概况和平均好感。

好感系统支持不同难度系数，例如更容易增长或更难增长。

### 世界模板导入 / 导出

用户可以把自己的世界模板导出为 JSON，也可以导入外部世界包。归档内容包括世界基本信息、世界详情和角色模板。导入时会重新创建世界模板、详情和角色，并重新写入世界详情向量。

## 功能概览

- 用户注册、登录、邮箱验证码、资料维护和密码修改。
- 世界模板、用户世界、世界详情、角色模板和角色绑定管理。
- 基于 DeepSeek 的角色回复生成，支持 SSE 流式输出。
- WebSocket 实时聊天连接，支持按用户世界建立会话。
- PostgreSQL + pgvector 向量检索，覆盖世界详情、聊天历史和世界事件。
- 话题边界识别、长期记忆、自动检索、rerank 和聊天历史分页。
- 世界级存档/读档，以及聊天撤回和副作用回滚。
- 互动故事事件的开启、推进、结束、摘要和归档。
- Redis 队列、延迟任务、缓存和 Redisson 分布式锁。
- 本地 Python BERT 服务用于输入完整性判断，本地 reranker 服务用于召回结果重排。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21, Spring Boot 4.0.5, Spring AI 2.0.0-M4, MyBatis-Plus |
| AI | DeepSeek Chat, Ollama Embedding, Spring AI Tool Calling |
| 数据 | PostgreSQL, pgvector, Redis, Redisson |
| 前端 | Vue 3, TypeScript, Vite, Reka UI |
| Python 辅助服务 | FastAPI, PyTorch, Transformers, jieba |
| 文件与通知 | Aliyun OSS, Aliyun Direct Mail |

## 项目结构

```text
.
|-- pom.xml
|-- README.md
|-- src
|   |-- main
|   |   |-- java/com/me/galchat
|   |   |   |-- controller/      # REST API
|   |   |   |-- service/         # 核心业务：聊天、存档、剧情、用户世界等
|   |   |   |-- memory/          # 聊天记忆、话题边界、消息聚合
|   |   |   |-- vector/          # 世界详情、聊天历史、世界事件向量检索
|   |   |   |-- tool/            # Spring AI 工具调用，如好感度更新
|   |   |   |-- mapper/          # MyBatis Mapper
|   |   |   |-- domain/          # PO / DTO / VO
|   |   |   |-- websocket/       # WebSocket 入口
|   |   |   |-- consumer/        # Redis 队列消费者
|   |   |   |-- task/            # 定时任务
|   |   |   `-- config/          # Web、Redis、AI、向量库等配置
|   |   `-- resources
|   |       |-- application.yaml
|   |       `-- mapper/
|   `-- test
|       `-- java/com/me/galchat/init/console.sql
|-- reka                        # 当前 Vue 3 新版前端
|-- vue                         # 旧版 Element Plus 前端
`-- python
    |-- bert.py                 # 输入完整性判断服务，默认 localhost:8081
    `-- reranker_server.py      # rerank 服务，默认 localhost:8082
```

## 环境要求

- JDK 21
- Node.js 20.19+ 或 22.12+
- PostgreSQL，并启用 `pgvector` 扩展
- Redis
- Ollama，并准备 1024 维 embedding 模型，例如 `bge-m3`
- DeepSeek API Key
- Python 3.10+，仅在启用本地 BERT / reranker 服务时需要
- Aliyun OSS / Aliyun Direct Mail 配置，仅在使用图片上传、邮箱验证码等能力时需要

默认配置位于 `src/main/resources/application.yaml`。首次运行前请按自己的环境修改数据库、Redis、DeepSeek、Ollama、OSS 和邮件配置。生产环境请使用环境变量或外部配置覆盖敏感信息，不要提交真实密钥。

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
ollama pull bge-m3
```

当前向量配置固定为 1024 维。如果更换 embedding 模型，需要同步确认模型维度与 `VectorConfiguration` 中的 `dimensions(1024)` 保持一致。

### 3. 启动 Python 辅助服务

如果只想先跑通主流程，可以暂时不启动这两个服务；Java 侧在调用失败时会降级处理。完整体验建议启动。

安装依赖：

```bash
pip install fastapi uvicorn torch "transformers>=4.36.0" pydantic jieba
```

输入完整性判断服务：

```bash
python python/bert.py
```

reranker 服务：

```bash
python python/reranker_server.py
```

默认 reranker 模型为 `Alibaba-NLP/gte-multilingual-reranker-base`。如需使用本地模型目录：

```bash
RERANKER_MODEL_PATH=/path/to/gte-multilingual-reranker-base python python/reranker_server.py
```

### 4. 启动后端

macOS / Linux：

```bash
./mvnw spring-boot:run
```

Windows：

```powershell
.\mvnw.cmd spring-boot:run
```

后端默认地址：

- HTTP API: `http://localhost:8080`
- WebSocket: `ws://localhost:8080/ws/{sid}`
- Actuator Health: `http://localhost:8080/actuator/health`

### 5. 启动前端

```bash
cd reka
npm install
npm run dev
```

Vite 默认运行在 `http://localhost:5173`。开发环境中，`reka/vite.config.ts` 会将 `/api` 代理到 `http://localhost:8080`，将 `/ws` 代理到 `ws://localhost:8080`。

## 主要接口

除登录、注册和注册邮箱验证码接口外，HTTP 接口默认需要在请求头携带 `token`。

| 模块 | 路径 |
| --- | --- |
| 用户 | `POST /user/login`, `POST /user/register`, `POST /user/register/email-code`, `GET/PUT /user/info`, `PUT /user/password` |
| 世界 | `/world/**` |
| 世界存档 | `GET /world-saves/{userWorldId}`, `POST /world-saves/{userWorldId}`, `POST /world-saves/{userWorldId}/load` |
| 角色 | `/character/**` |
| 聊天 | `POST /ai/chat` |
| 聊天历史 | `GET /history`, `POST /history/withdraw` |
| 世界事件 | `/worldevent/story/**` |
| 图片上传 | `POST /upload` |
| WebSocket | `/ws/{sid}` |

## 常用命令

后端测试：

```bash
./mvnw test
```

后端打包：

```bash
./mvnw clean package
```

如果本地没有准备 PostgreSQL、Redis、Ollama、DeepSeek 等测试依赖，可以先跳过测试：

```bash
./mvnw clean package -DskipTests
```

前端类型检查和构建：

```bash
cd reka
npm run type-check
npm run build
```

## 开发注意事项

- `application.yaml` 当前偏向本地开发配置，生产环境请使用外部配置管理数据库密码、DeepSeek Key、OSS Key 和 JWT 相关密钥。
- PostgreSQL 必须启用 `pgvector`，否则向量表和检索能力无法正常工作。
- Redis 承担缓存、队列、延迟任务和分布式锁能力，开发与部署时需要保持可用。
- Java 服务当前直接调用本机 `http://localhost:8081` 和 `http://localhost:8082`。如果 Python 服务部署在其他机器，需要同步调整 Java 侧配置或代码。
