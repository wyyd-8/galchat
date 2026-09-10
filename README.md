# GalChat

GalChat 是一个面向角色聊天、多人互动和 CoC 跑团的全栈项目。用户可以创建或导入世界，与角色单独聊天、组织群聊，也可以选择模组，由 KP（主持人）和调查员共同推进跑团。聊天记忆、角色好感、行动轮、掷骰结果和存档共同构成可持续恢复的游戏状态。

后端基于 Java 21、Spring Boot 和 Spring AI；前端位于 `reka/`，使用 Vue 3、TypeScript、Reka UI 和 Three.js。`python/` 提供输入完整性判断、检索重排和角色卡 PDF 工具。

## 核心功能

### 角色单聊与长期记忆

- 支持 SSE 流式回复，以及按用户世界建立的 WebSocket 会话。
- `TopicAwareMessageChatMemoryAdvisor` 根据话题边界组织上下文，结合世界详情和历史聊天进行向量检索，可使用本地 reranker 重排结果。
- 支持角色提示词、好感度、思考内容和工具调用展示；模型产生的好感变化会绑定到对应用户消息。
- 可为单个角色选择用户配置的模型 API；未绑定时使用内置模型。
- 撤回上一轮用户消息时，会同步清理回复、思考、工具记录和检索提示，回滚相关好感变化，刷新话题边界与缓存。单聊最多连续撤回 3 条用户消息。

### 普通群聊

- 在同一用户世界中创建多角色会话，角色依次回复，后续角色可看到本轮前面角色的发言。
- 支持查看和调整发言计划、聊天历史分页、撤回、关闭与删除会话。
- 按参与者配置控制方式和模型 API，支持 AI 生成与手动代发。
- 生成过程通过 SSE 推送，并提供按 `clientRequestId` 重新订阅生成结果的接口。
- 群聊话题有独立的上下文和向量记忆。

### CoC 跑团

跑团使用独立的 `trpg` 会话模式，围绕模组、KP、调查员、场景和行动轮组织流程。

- **模组管理**：浏览、创建、编辑、导入和导出模组，管理地点、线索、展示材料与人物设定。
- **角色准备**：支持角色卡文本导入、自动生成和分步创建；提供可下载的人物卡表格模板与导入预检查。分步流程覆盖身份、属性掷骰、年龄调整、职业、技能、背景及装备。
- **同伴选择**：可选择多位 AI 同伴，也可独自开始；选人时可查看角色的同行档案、最近参团状态、已完成次数及分页完成记录。
- **场景探索**：支持场景选择、运行时子场景、调查员向 KP 提问、探索结束、场景总结及游戏内时间维护。
- **行动执行**：展示当前行动轮与步骤，支持继续、提交行动、手动发言和失败步骤重试。
- **规则与战斗**：提供技能、理智、近战、枪械及伤害等规则处理，记录掷骰明细，维护角色状态和战斗概览；前端使用 Three.js 展示骰子并支持皮肤切换。
- **跑团记忆**：结合模组信息、探索记录、上下文摘要与行动轮检索，为后续行动提供历史依据。子场景摘要分别记录可用线索与剧情经过，覆盖对应消息区间内的战斗结果和战后叙述。
- **结局与恢复**：通过独立总结行动轮生成完成报告与调查员后传，支持失败重试、手动存档，以及行动轮、场景和初始状态的自动存档回滚。

前端另有标记为“实验功能”的自动推进和下一轮方向修正：自动推进可在行动轮之间及非用户掷骰后倒计时继续；方向修正用于临时调整下一轮 AI 调查员的探索或战斗方向。

#### 人物卡导入

在角色卡绑定窗口选择导入，下载人物卡模板，用 Excel 或 WPS 完成表格中的“建卡”步骤，再从“txt输出”复制人物卡文本并粘贴到页面。当前入口接收文本，不直接上传或解析 `.xlsx` 文件。

第一行需要姓名、职业、性别和年龄，正文需要 STR、CON、SIZ、DEX、APP、INT、POW、EDU 八项属性；技能与背景可以选填。页面会预览已识别内容并提示缺项，检查后点击“导入并绑定人物卡”。模板文件位于 `reka/public/templates/`。

#### 完成报告与归档

KP 请求结束跑团后，系统先完成最后场景的公开叙述与摘要，再进入独立的总结行动轮，生成跑团概览和调查员后传。总结使用 KP 当前配置的模型；成功后保存报告并自动关闭、归档会话，失败时可通过行动轮重试。

完成报告包含跑团概览、共同旅程、调查员后传、掷骰回顾和战斗回顾五部分：可展开完整场景摘要，查看调查员结束时的状态及 HP / SAN 变化，按团队或个人统计公开且已结算的检定，并回顾已完成战斗的结算记录。

手动结束或跳过总结会直接关闭会话，不生成完成报告；同行档案中的“已完成次数”只统计已关闭且保存了完成报告的跑团。旧的已结束会话不会自动补生成报告。

### 存档与回档

| 类型 | 范围 | 接口前缀 |
| --- | --- | --- |
| 世界存档 | 角色状态、单聊历史边界和最近轮次、普通群聊的历史边界、最近轮次及发言计划 | `/world-saves/{userWorldId}` |
| 跑团存档 | 指定跑团的会话状态、角色卡、装备、战斗、行动轮、掷骰、完成报告、执行检查点及相关 Redis 状态 | `/trpg-saves/{conversationId}` |

读档会清理存档点之后的数据，并恢复快照和相关派生状态。世界存档保留每个角色最近 3 轮单聊的恢复数据；跑团通过独立接口恢复，不应把世界存档视为跑团全量备份。存读档过程使用分布式锁协调并发操作。

### 世界与模型管理

- 世界模板、世界详情、角色模板和用户世界分别管理，支持世界 JSON 导入 / 导出及模板替换。
- 用户可维护多个 OpenAI 兼容模型 API，配置 Base URL、模型名、API Key 和请求覆盖参数，并测试聊天、流式输出、工具调用等能力。
- API Key 以 AES-GCM 加密存储；自定义模型地址必须使用 HTTPS 并解析到公网地址。
- 用户功能包括注册、登录、邮箱验证码、资料维护、密码修改、图片上传及骰子皮肤设置。

## 技术栈

以下版本以仓库中的 `pom.xml` 和 `reka/package.json` 为准。

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.0.5、Spring AI 2.0.1、MyBatis-Plus |
| AI | DeepSeek、OpenAI 兼容 API、Ollama Embedding、Spring AI Tool Calling |
| 数据与并发 | PostgreSQL、pgvector、Redis、Redisson |
| 前端 | Vue 3.5、TypeScript 6、Vite 8、Reka UI 2、Three.js |
| Python 辅助服务 | FastAPI、PyTorch、Transformers、jieba |
| 文件与邮件 | Aliyun OSS、Aliyun Direct Mail |

## 项目结构

```text
.
├── pom.xml
├── mvnw / mvnw.cmd
├── src/main/java/com/me/galchat/
│   ├── controller/             # REST API 和 SSE 入口
│   ├── service/impl/
│   │   ├── character/          # 角色模板、角色卡、自动与分步创建
│   │   ├── chat/               # 单聊及本地 NLP 服务适配
│   │   ├── dice/               # 掷骰、CoC 检定与战斗规则
│   │   ├── group/              # 群聊、发言计划、生成流与执行恢复
│   │   ├── trpg/               # 模组、场景、行动轮、战斗与跑团存档
│   │   ├── user/               # 用户、历史、好感与用户事件
│   │   └── world/              # 世界模板、用户世界、世界归档与存档
│   ├── groupchat/              # 群聊 / 跑团运行策略、上下文、工具记录
│   ├── singlechat/             # 单聊客户端组装
│   ├── modelapi/               # 自定义模型配置、加密、探测与运行时
│   ├── memory/                 # 单聊记忆、话题窗口与消息聚合
│   ├── vector/                 # 世界详情、聊天、群聊话题、跑团行动检索
│   ├── tool/                   # 好感、KP、调查员等模型工具
│   ├── domain/                 # PO / DTO / VO
│   ├── mapper/                 # MyBatis Mapper
│   ├── websocket/              # WebSocket 入口
│   ├── consumer/               # 队列消费者
│   ├── task/                   # 定时任务
│   └── config/                 # Web、AI、Redis、向量库等配置
├── src/main/resources/
│   ├── application.yaml
│   └── mapper/                 # SQL 映射
├── src/test/java/com/me/galchat/
│   └── init/console.sql        # 空数据库的完整建表与技能种子脚本
├── reka/
│   ├── public/templates/      # 可下载的人物卡表格模板
│   ├── src/api/                # 请求客户端与接口类型
│   ├── src/components/         # 世界、单聊、群聊、跑团、角色卡等界面
│   ├── src/dice/               # 骰子展示与播放逻辑
│   └── test/                   # 前端测试；部分测试与源文件同目录
├── python/
│   ├── bert.py                # 输入完整性判断，localhost:8081
│   ├── reranker_server.py     # 检索重排，localhost:8082
│   ├── character_card_pdf.py  # 角色卡 PDF 服务（渲染、接口、启动）
│   └── character_card/        # 字体、模板与字体许可证
├── data/
│   ├── worlds/                # 世界 JSON 数据
│   └── modules/               # 模组 SQL / JSON 数据
├── dice/                      # 骰子 Blender 源文件
└── output/                    # 规则资料、转换结果及素材输出
```

## 环境与配置

- JDK 21；仓库提供 Maven Wrapper。
- Node.js 满足 `^20.19.0 || >=22.12.0`，用于前端开发和构建。
- PostgreSQL，并安装 pgvector 扩展；Redis。
- Ollama 和 1024 维 embedding 模型，当前配置为 `bge-m3`。
- 可用的 DeepSeek API 配置，用于内置聊天及辅助生成流程。
- Python 3.10+，仅在运行 Python 辅助服务或角色卡 PDF 工具时需要。
- 图片上传和邮箱验证码需要相应的 Aliyun OSS / Direct Mail 配置与凭据。

后端配置入口为 [application.yaml](src/main/resources/application.yaml)。按实际环境设置以下配置：

| 配置项 | 用途 |
| --- | --- |
| `spring.datasource.url` / `username` / `password` | PostgreSQL 连接 |
| `spring.data.redis.*` | Redis 连接 |
| `spring.ai.deepseek.base-url` / `api-key` / `chat.model` | 内置模型服务与模型名 |
| `spring.ai.ollama.base-url` / `embedding.model` | Ollama 地址与 embedding 模型 |
| `galchat.model-api.master-key` | 加密用户自定义模型 API Key 的主密钥 |
| `galchat.model-api.request-timeout` | 自定义模型请求超时，当前为 `120s` |
| `galchat.alioss.*` / `galchat.aliemail.*` | OSS 与邮件业务配置；OSS 使用环境变量凭据，邮件使用阿里云默认凭据链 |

主密钥必须是 **32 字节随机数据的 Base64 编码**。首次部署时可用 `openssl rand -base64 32` 生成，并通过外部配置持久保存；配置读取也支持 `GALCHAT_MODEL_API_MASTER_KEY` 作为回退值。已有加密数据需要同一把密钥才能解密。数据库连接、API Key 等敏感值请使用环境变量或外部配置覆盖。

## 快速启动

以下命令除前端步骤外，均在仓库根目录执行。

### 1. 初始化空数据库

创建 PostgreSQL 数据库后执行：

```bash
psql -h localhost -U <username> -d <database> -v ON_ERROR_STOP=1 \
  -f src/test/java/com/me/galchat/init/console.sql
```

`console.sql` 一次性建立当前全部 47 张业务表（含 `trpg_completion`）、业务索引和 CoC 技能定义种子数据，并安装 `vector` 扩展，无需额外维护脚本。该脚本面向空数据库，不是已有数据库的增量升级脚本。以下四张向量表及其索引由后端启动时通过 `VectorConfiguration` 的 Spring AI 自动初始化，使用 UUID 主键、1024 维向量及 HNSW 余弦索引：

- `world_detail_vector_store`
- `chat_history_vector_store`
- `group_topic_vector_store`
- `trpg_turn_vector_store`

如果需要示例跑团模组，可在建表后单独执行：

```bash
psql -h localhost -U <username> -d <database> -v ON_ERROR_STOP=1 \
  -f data/modules/15-01-amidst-the-ancient-trees.sql
```

该脚本导入《古树林中》，重复导入会报错。`data/modules/00-debug-combat-arena.sql` 用于战斗调试。世界 JSON 可通过前端导入。

《太阳与九英镑》提供[个人模组导入 JSON](data/modules/sun-and-nine-pounds.json) 和[系统默认模组 SQL](data/modules/sun-and-nine-pounds.sql)，两种方式任选其一。该模组按可回访的地点网络组织，保留场景原文并补充 AI 主持说明；封面与 10 份展示材料已填写上传地址。

模组与用户世界数据按需导入，不随建表自动创建。《古树林中》的当前导入 SQL 已包含七个时间场景及最终场景主持说明。历史武器修正和跑团重置属于旧数据维护，不参与空库初始化。已有数据库升级需备份后对照当前结构处理，不要重跑 `console.sql`。

### 2. 准备 Redis、Ollama 和后端配置

启动 Redis 与 Ollama，拉取 embedding 模型：

```bash
ollama pull bge-m3
```

按上一节设置数据库、Redis、DeepSeek 等配置。如果更换 embedding 模型，必须同时核对 `VectorConfiguration` 中的 `dimensions(1024)` 和已有向量表结构。

### 3. 启动后端

macOS / Linux：

```bash
./mvnw spring-boot:run
```

Windows：

```powershell
.\mvnw.cmd spring-boot:run
```

默认 HTTP 地址为 `http://localhost:8080`，WebSocket 路径为 `/ws/{sid}`。Actuator 健康检查路径为 `/actuator/health`；当前鉴权拦截器未豁免该路径，需要携带 `token`。

### 4. 启动前端

```bash
cd reka
npm ci
npm run dev
```

默认开发地址为 `http://localhost:5173`。`reka/vite.config.ts` 将 `/api` 请求转发到后端并移除 `/api` 前缀，将 `/ws` 转发到后端 WebSocket 服务。

登录后可导入或创建世界、添加角色并开始单聊或群聊；跑团需要选择模组、配置参与者和角色卡，再开始行动轮。

### 5. 可选：启动 Python 辅助服务

```bash
python -m pip install fastapi uvicorn torch "transformers>=4.36.0" pydantic jieba
```

输入完整性判断服务需要事先在 `python/bert_model/` 放置可加载的分类模型与 tokenizer；该模型不随 Git 仓库提供。

```bash
python python/bert.py
```

检索重排服务默认加载 `Alibaba-NLP/gte-multilingual-reranker-base`，首次启动可能需要下载模型：

```bash
python python/reranker_server.py
```

也可以指定本地模型目录：

```bash
RERANKER_MODEL_PATH=/path/to/gte-multilingual-reranker-base python python/reranker_server.py
```

reranker 还支持 `RERANKER_DEVICE`、`RERANKER_MAX_LENGTH`、`RERANKER_BATCH_SIZE` 和 `RERANKER_TORCH_DTYPE`。Java 当前直接调用本机 `8081` 和 `8082`；异机部署需要调整 Java 侧地址。

这两个服务调用失败时，BERT 判断会使用延迟任务兜底，reranker 会保留原始向量召回顺序，可先不启动它们来验证主流程。

### 可选：生成角色卡 PDF

在「跑团工具 → 人物卡」右上角导出 PDF。前端直接调用可选 Python 服务，服务不可用时隐藏按钮。全部代码位于 `python/character_card_pdf.py`，所需字体和模板位于同级 `character_card/` 文件夹。

```bash
python -m pip install Pillow reportlab fastapi uvicorn
python python/character_card_pdf.py
```

默认监听 `127.0.0.1:8083`，可通过 `--host`、`--port` 调整。在 `reka/.env.local` 中配置 `VITE_CHARACTER_CARD_PDF_URL=http://127.0.0.1:8083` 后重启前端。生产构建前须使用浏览器可访问的 HTTPS 服务地址或同域代理路径；跨域时通过 `CHARACTER_CARD_ALLOWED_ORIGINS` 设置允许的前端来源（逗号分隔，默认允许 `http://localhost:5173` 和 `http://127.0.0.1:5173`）。

`GET /health` 检查资源是否齐全；`POST /export` 接收人物卡 `card`、模板 `background`（`1920s` / `modern`）和字体 `fontIndex`（0 / 1 / 2），返回双页 PDF。前端提交当前查看的人物卡，不携带主系统 token 或 cookies；头像由浏览器转为内嵌图片，读取失败时提示并导出无头像版本。PDF 会省略部分长文本，最多绘制 10 行武器且不包含调查员笔记，用于打印而非完整数据备份。

## 主要接口

下表路径均为后端路径。通过 Vite 代理访问时加 `/api` 前缀。除登录、注册和注册邮箱验证码外，HTTP 请求默认需要携带 `token` 请求头；SSE 接口返回事件流，其余大部分接口返回 `Result` 包装的数据。

| 模块 | 主要路径 |
| --- | --- |
| 用户 | `/user/login`、`/user/register`、`/user/register/email-code`、`/user/info`、`/user/password`、`/user/password/email-code` |
| 世界与角色 | `/world/**`、`/character/**` |
| 单聊与历史 | `POST /ai/chat`、`GET /history`、`POST /history/withdraw` |
| 单聊模型绑定 | `PUT /character/{userWorldId}/{characterId}/model` |
| 世界存档 | `GET/POST /world-saves/{userWorldId}`、`POST /world-saves/{userWorldId}/load` |
| 模型 API | `GET/POST /model-apis`、`PUT/DELETE /model-apis/{id}`、`POST /model-apis/{id}/test` |
| 群聊会话 | `GET/POST /group-chat/conversations`、`GET/DELETE /group-chat/conversations/{conversationId}` |
| 关闭会话 | `POST /group-chat/conversations/{conversationId}/close`（手动关闭，不生成完成报告） |
| 同行档案 | `GET /group-chat/participant-history?userWorldId=…`、`GET /group-chat/participant-history/{characterId}/runs?userWorldId=…`（完成记录支持 `cursor`、`limit` 分页） |
| 跑团完成报告 | `GET /group-chat/conversations/{conversationId}/completion-report` |
| 群聊消息 | `GET/POST /group-chat/conversations/{conversationId}/messages`、`POST /group-chat/conversations/{conversationId}/withdraw` |
| 生成流恢复 | `GET /group-chat/conversations/{conversationId}/generations/{clientRequestId}` |
| 参与者与计划 | `/group-chat/conversations/{conversationId}/actor-runtimes`、`/group-chat/conversations/{conversationId}/reply-plan` |
| 模组 | `/coc-modules/**`，含 `/import` 和 `/{id}/export` |
| 角色卡 | `/character-cards/**`、`/character-card-creation/drafts/**` |
| 跑团执行 | `/group-chat/conversations/{conversationId}/turns/**` |
| 跑团状态 | `/group-chat/conversations/{conversationId}/context-window`、`/game-time`、`/combat-overview`（后两项使用相同会话前缀） |
| 掷骰 | `GET /dice-rolls/{id}`、`GET /dice-rolls/{id}/results`、`POST /dice-roll-results/{id}/roll` |
| 跑团存档 | `GET/POST /trpg-saves/{conversationId}`、`POST /trpg-saves/{conversationId}/load` |
| 跑团回滚 | `GET /trpg-saves/{conversationId}/rollback-status`；`POST` 同前缀下的 `/rollback-turn`、`/rollback-scene`、`/rollback-initial` |
| 图片与实时连接 | `POST /upload`、WebSocket `/ws/{sid}` |

完整请求字段与响应结构以 `src/main/java/com/me/galchat/controller/`、`domain/dto/`、`domain/vo/` 和 `reka/src/api/` 为准。

## 开发与验证

后端测试与打包：

```bash
./mvnw test
./mvnw clean package
```

仓库同时包含单元测试和依赖数据库、应用上下文或外部服务的集成测试，运行全部测试前需准备相应环境。仅需验证编译与打包时：

```bash
./mvnw clean package -DskipTests
```

前端测试、类型检查和构建：

```bash
cd reka
npm test
npm run type-check
npm run build
```

`npm run build` 已包含类型检查；前端测试直接使用 Node 的测试运行器执行 TypeScript 文件，需要使用支持直接运行 TypeScript 的 Node 版本。`npm run preview` 可预览构建产物；部署时需配置 `/api`、`/ws` 后端路由，并支持 WebSocket 和 SSE 长连接。
