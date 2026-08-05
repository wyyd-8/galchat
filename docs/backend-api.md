# GalChat 后端接口文档

> 文档基线：2026-08-04，以当前仓库的 Controller、DTO/VO、鉴权拦截器和 Python FastAPI 源码为准。
>
> 覆盖范围：69 个 Java REST 业务接口、1 个 WebSocket 入口、2 个 Python 辅助服务接口。Spring Boot Actuator 端点和 FastAPI 自动生成的 `/docs`、`/redoc`、`/openapi.json` 属于框架运维/文档端点，不计入业务接口。

## 1. 通用约定

### 1.1 服务地址

| 服务 | 默认地址 | 说明 |
| --- | --- | --- |
| Java 主服务 | `http://localhost:8080` | `application.yaml` 未覆盖 `server.port`，使用 Spring Boot 默认端口 |
| 输入完整性服务 | `http://localhost:8081` | `python/bert.py` |
| 检索重排服务 | `http://localhost:8082` | `python/reranker_server.py` |

前端开发环境通过 `/api` 代理 Java 主服务并移除 `/api` 前缀。因此浏览器中可能请求 `/api/user/info`，但后端原生路径是 `/user/info`。

### 1.2 鉴权

Java 主服务使用自定义请求头传递 JWT：

```http
token: <登录或注册接口返回的 token>
```

仅以下 3 个 Java 接口免登录：

- `POST /user/login`
- `POST /user/register`
- `POST /user/register/email-code`

其余 Java REST 接口都需要 `token`。令牌有效期为 12 小时。缺少、过期或非法令牌时返回 HTTP `401`，响应体可能为空。Python 辅助服务当前没有鉴权。

WebSocket 可通过握手请求头 `token` 或查询参数 `token` 传递 JWT；浏览器原生 WebSocket 不能自定义握手头，通常使用查询参数。

### 1.3 普通 JSON 响应

除世界导出接口、SSE 和 Python 服务外，Java REST 接口统一返回：

```json
{
  "code": 1,
  "msg": "success",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | integer | `1` 成功，`0` 失败 |
| `msg` | string | 成功时为 `success`，失败时为可读错误信息 |
| `data` | any | 查询/创建结果；无返回值的成功接口通常不含该字段 |

业务校验失败通常仍是 HTTP `200`，通过 `code = 0` 表示失败；客户端必须同时判断 HTTP 状态和 `code`。日期时间使用 ISO-8601 字符串，例如 `2026-08-04T15:30:00`。

### 1.4 SSE 响应

SSE 接口返回 `Content-Type: text/event-stream`，每个事件的 `data:` 是 JSON。调用端应增量消费事件，不能等待整个响应结束后再解析。流建立前的业务异常可能返回普通错误；流建立后的异常可能直接中断连接。

### 1.5 ID、分页和幂等

- 所有 `Long` ID 在 JSON 中表现为整数。JavaScript 客户端若未来可能超过 `Number.MAX_SAFE_INTEGER`，应改用字符串保存。
- 单聊和群聊历史都使用游标分页：把当前页最小消息 ID 作为下一页的 `id` 或 `beforeId`。
- 群聊/TRPG 的 `clientRequestId` 最长 100 字符。建议每次用户操作生成 UUID；相同会话内重复提交已处理的 ID 会被拒绝。

## 2. 用户与账户（7 个）

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `POST /user/login` | `UserAuthRequest` | `UserToken` | 邮箱密码登录；免登录 |
| `POST /user/register` | `UserAuthRequest` | `UserToken` | 注册并直接返回登录令牌；免登录 |
| `POST /user/register/email-code` | `{ "email": string }` | 无 | 发送注册验证码；免登录 |
| `GET /user/info` | 无 | `UserInfo` | 获取当前用户资料，不返回密码 |
| `PUT /user/info` | `UserProfileRequest` | 无 | 更新用户名、生日和骰子皮肤；请求中的 `email` 当前不会被更新 |
| `PUT /user/password` | `UserPasswordRequest` | 无 | 使用邮箱验证码修改密码 |
| `POST /user/password/email-code` | `{ "email": string }` | 无 | 给当前账户邮箱发送改密验证码 |

`UserAuthRequest`：

| 字段 | 类型 | 登录 | 注册 | 说明 |
| --- | --- | --- | --- | --- |
| `email` | string | 必填 | 必填 | 服务端会去除首尾空格并转小写 |
| `password` | string | 必填 | 必填 | 非空 |
| `verificationCode` | string | 不使用 | 必填 | 6 位数字邮箱验证码 |

`UserProfileRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 否 | 非空时更新 |
| `email` | string | 否 | DTO 中存在，但当前更新逻辑忽略该字段 |
| `birthday` | string | 否 | `YYYY-MM-DD` |
| `diceSkin` | string | 否 | 骰子皮肤标识，非空且最长 50 字符 |

`UserPasswordRequest` 包含 `email`、`newPassword`、`verificationCode`，三者均为改密所需字段，邮箱必须与当前账户一致。

`UserToken` 字段为 `token/id/username`。`UserInfo` 字段为 `id/username/email/birthday/diceSkin/createTime`，不会返回 `password`。

注册验证码目前仅允许形如 `8位学号@bjtu.edu.cn` 的校园邮箱，有效期 5 分钟；同一邮箱 1 分钟内不能重复发送。验证码连续校验失败过多时会冻结 30 分钟。

## 3. 世界模板、用户世界与归档（18 个）

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `GET /world/templates` | 无 | `WorldTemplate[]` | 可见模板及本人创建的模板；列表只返回 `id/name/image` |
| `GET /world/templates/{id}` | 路径 `id` | `WorldTemplate` | 查询可见模板或本人私有模板详情 |
| `POST /world/templates` | `WorldTemplateRequest` | 无 | 创建世界模板，当前用户成为作者 |
| `GET /world/templates/my/{userWorldId}` | 路径 `userWorldId` | `WorldTemplate` | 通过“我的用户世界”查询其源模板；仅模板作者可用 |
| `PUT /world/templates/my/{userWorldId}` | `WorldTemplateRequest` | 无 | 更新本人世界模板 |
| `GET /world/templates/my/{userWorldId}/export` | 无 | 原始 JSON 文件 | 导出世界模板、详情和角色模板；不使用 `Result` 包装 |
| `POST /world/import` | `WorldArchive` | `WorldArchiveImportResult` | 导入世界包并创建新的世界模板 |
| `GET /world/templates/{id}/usage` | 路径 `id` | `WorldTemplateUsage` | 作者查询模板关联世界数量及当前是否可删除 |
| `PUT /world/templates/{id}/replace?confirmLowMatch=false` | `WorldArchive` | `WorldArchiveReplaceResult` | 作者按角色名称替换模板；低匹配率时先返回确认要求而不写数据 |
| `DELETE /world/templates/{id}` | 路径 `id` | 无 | 作者删除零关联世界的模板，并清理详情、角色和详情向量 |
| `GET /world/templates/{worldId}/details` | 路径 `worldId` | `WorldDetail[]` | 查询世界详情条目 |
| `POST /world/templates/{worldId}/details` | `WorldDetailRequest` | 无 | 给本人模板新增详情并更新向量数据 |
| `DELETE /world/templates/{worldId}/{detailId}` | 路径 ID | 无 | 删除本人模板的详情条目并清理向量数据 |
| `POST /world` | `UserWorldRequest` | 无 | 从世界模板创建一个用户世界，`worldId` 必填 |
| `GET /world/{id}` | 路径 `id` | `UserWorld` | 获取当前用户拥有的用户世界详情 |
| `PUT /world/{id}` | `UserWorldUpdateRequest` | 无 | 更新用户世界配置 |
| `DELETE /world/{userWorldId}` | 路径 ID | 无 | 删除用户世界；仍有角色时拒绝删除 |
| `GET /world/user/{userId}` | 路径 `userId` | `UserWorld[]` | 按用户 ID 查询世界基础信息，仅返回 `id/name/image` |

`WorldTemplateRequest`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 世界名 |
| `image` | string | 封面 URL；非空时必须来自本项目指定 OSS 上传路径 |
| `author` | string | 展示作者名 |
| `background` | string | 世界背景/系统提示上下文 |
| `visible` | boolean | 是否公开；创建时省略等价于 `true` |

请求中的 `id`、`authorId` 不可信，创建/更新时由服务端决定或忽略。

`WorldDetailRequest` 包含 `about`（主题/标题）和 `details`（详细内容）；创建时 `details` 应为非空字符串且最长 2000 字。`id`、`worldId` 由路径和服务端确定。

`WorldTemplate` 响应字段为 `id/name/image/author/background/authorId/visible`。`UserWorld` 响应字段为 `id/userId/worldId/name/image/acitvePushStatus/dailyCompanionMode/favorSystemStatus/eotDetectionStatus/thinkStatus/addSpecialPrompt/myWorld`。

`UserWorldRequest`/`UserWorldUpdateRequest` 可用字段：

| 字段 | 类型 | 创建 | 更新 | 说明 |
| --- | --- | --- | --- | --- |
| `worldId` | integer | 必填 | 不使用 | 源世界模板 ID |
| `name` | string | 否 | 否 | 创建时省略则使用模板名 |
| `acitvePushStatus` | boolean | 否 | 否 | 主动推送开关；字段名按代码保留了 `acitve` 拼写 |
| `dailyCompanionMode` | boolean | 否 | 当前忽略 | 日常陪伴模式 |
| `favorSystemStatus` | string | 否 | 否 | 好感系统状态 |
| `eotDetectionStatus` | boolean | 否 | 否 | 输入结束检测开关 |
| `thinkStatus` | boolean | 否 | 当前忽略 | 思考模式；仅创建时写入 |
| `addSpecialPrompt` | boolean | 否 | 当前忽略 | 首轮特殊提示开关；仅创建时写入 |

`WorldArchive` 示例：

```json
{
  "formatVersion": 1,
  "world": {
    "name": "骑士学院",
    "image": "https://...",
    "author": "作者",
    "background": "世界背景",
    "visible": false
  },
  "details": [
    { "about": "学院", "details": "学院的详细设定" }
  ],
  "characters": [
    {
      "name": "艾琳",
      "image": "https://...",
      "background": "角色背景",
      "personality": "角色性格",
      "cocPlayStyle": "调查风格",
      "favorability": { "0-20": "疏离", "21-60": "熟悉" },
      "initFavor": 20
    }
  ]
}
```

当前只支持 `formatVersion = 1`；世界 `name/background`、每个详情的 `details` 和每个角色的 `name` 必须非空。导入结果字段为 `worldId`、`name`、`detailCount`、`characterCount`。导出响应带 `Content-Disposition: attachment; filename="galchat-world-{userWorldId}.json"`。

模板替换按裁剪后的角色名匹配，英文大小写不敏感；重名会被拒绝。匹配角色保留原角色模板 ID 并完整更新，上传文件中的额外角色会新增，缺失的旧角色保持不变。匹配率按“匹配旧角色数 / 原模板角色总数”计算；原模板无角色时为 100%。低于 50% 且未传 `confirmLowMatch=true` 时，响应的 `confirmationRequired=true`、`replaced=false`，不会修改任何数据。替换不会改写已有用户世界的名称和封面。

`WorldArchiveReplaceResult` 还会返回 `matchedCharacterCount/addedCharacterCount/unchangedCharacterCount/matchRate` 以及三类角色名称列表，供客户端展示确认信息。模板删除使用事务内条件删除；只要存在任意 `user_world_prefix.world_id` 关联就会拒绝。

## 4. 角色模板与用户角色（9 个）

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `POST /character/templates/{worldId}` | `CharacterTemplateRequest` | 无 | 给本人世界模板创建角色 |
| `GET /character/templates/{worldId}` | 无 | `CharacterTemplate[]` | 查询可访问世界的角色基础信息，只返回 `id/name/image` |
| `GET /character/templates/my/{userWorldId}/{characterId}` | 路径 ID | `CharacterTemplate` | 通过“我的用户世界”查询角色模板详情 |
| `PUT /character/templates/my/{userWorldId}/{characterId}` | `CharacterTemplateRequest` | 无 | 更新本人模板下的角色 |
| `PUT /character/my/{userWorldId}/{characterId}/favor` | `{ "favorValue": integer }` | 无 | 直接设置用户角色好感度，范围 `0..100` |
| `POST /character/{userWorldId}/{characterId}` | 路径 ID | 无 | 把角色模板加入用户世界 |
| `DELETE /character/{userWorldId}/{characterId}` | 路径 ID | 无 | 从用户世界移除角色；被活动群聊占用时可能被拒绝 |
| `GET /character/{userWorldId}` | 无 | `UserCharacter[]` | 查询用户世界内角色状态 |
| `PUT /character/{userWorldId}/{characterId}/prompt` | `UserCharacterPromptRequest` | 无 | 更新针对该角色的长期用户信息提示 |

`CharacterTemplateRequest`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 角色名 |
| `image` | string | 角色图 URL；非空时必须来自指定 OSS 路径 |
| `background` | string | 背景设定 |
| `personality` | string | 性格设定 |
| `cocPlayStyle` | string | CoC 扮演/调查风格 |
| `favorability` | object<string,string> | 好感区间到关系描述的映射 |
| `initFavor` | integer | 加入用户世界时的初始好感 |

`UserCharacterPromptRequest` 只有 `userInfoPrompt` 字段，可为字符串或 `null`。查询到的 `UserCharacter` 字段包括 `userWorldId`、`characterId`、`characterName`、`characterImage`、`lastChatTime`、`lastChatContent`、`favorValue`、`userInfoPrompt`。

## 5. 单聊、历史与 WebSocket 输入（3 个 REST + 1 个 WebSocket）

### 5.1 单聊 REST

| 方法与路径 | 请求 | 响应 | 说明 |
| --- | --- | --- | --- |
| `POST /ai/chat` | `DirectChatRequest` | SSE `ChatFlux` | 发起一次带思考流的角色单聊 |
| `GET /history` | 查询参数 | `Result<UserChatHistory[]>` | 游标查询单聊历史 |
| `POST /history/withdraw` | 查询参数 | `Result` | 撤回最近一轮并回滚关联副作用 |

`DirectChatRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `worldId` | integer | 是 | 世界模板 ID |
| `userWorldId` | integer | 是 | 用户世界 ID |
| `characterId` | integer | 是 | 角色模板 ID |
| `message` | string | 是 | 非空用户消息 |

DTO 中还存在 `type/isTyping/length/revision/triggerType`，但 REST 单聊不使用这些字段。

SSE 数据结构：

```json
{ "type": "thinking", "content": "思考增量" }
```

`type` 当前可能为：

- `thinking`：模型思考内容增量。
- `tool`：发生工具调用，`content` 通常为 `null`。
- `reponse`：最终回复增量。注意源码中的实际值就是拼写为 `reponse`，客户端不要按 `response` 判断。

同一 `userWorldId + characterId` 同时只允许一个单聊生成任务。

历史查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userworldid` | integer | 是 | 注意参数名为全小写 |
| `characterid` | integer | 是 | 注意参数名为全小写 |
| `id` | integer | 否 | 只查询 ID 小于该值的更早消息 |
| `size` | integer | 否 | 默认 20，必须大于 0；当前没有服务端最大值 |

返回列表按时间正序排列。`UserChatHistory` 字段为 `id/userWorldId/characterId/content/type/userMessageId/stepNo/timestamp`；`type` 常见值包括 `user`、`assistant`、`thinking`、`tool`、`withdrawn`，具体可见内容会受用户世界 `thinkStatus` 影响。

撤回会处理最近用户轮次，删除关联助手回复、思考、工具调用和自动检索信息，反向回滚好感变化并调整话题边界；最多连续撤回 3 条。生成中或剧情切换中会拒绝撤回。

### 5.2 WebSocket 输入

连接地址：

```text
ws://localhost:8080/ws/{sid}?userWorldId={userWorldId}&token={jwt}
```

`sid` 只是路径占位，服务端实际以容器生成的 Session ID 管理连接；`userWorldId` 必填且必须属于当前用户。鉴权或参数错误时以 `VIOLATED_POLICY` 关闭连接，原因可能为 `Missing token`、`Invalid token`、`Missing param`、`Invalid param` 或 `Wrong param`。

客户端消息：

```json
{
  "type": "fragment",
  "characterId": 12,
  "message": "追加的输入片段"
}
```

```json
{
  "type": "typing",
  "characterId": 12,
  "isTyping": false
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | string | 必填，`fragment` 或 `typing` |
| `characterId` | integer | 必填 |
| `message` | string | `fragment` 时必填；按片段追加 |
| `isTyping` | boolean | `typing` 时使用 |
| `worldId` | integer | 可省略，服务端从用户世界补齐 |

服务端行为：

- `fragment` 会把输入片段原子追加到 Redis，并把包含 `length/revision` 的状态同步给同一用户世界的其他设备。
- 停止输入 3 秒后触发保底回复；开启 `eotDetectionStatus` 时，BERT 判断输入完整后可在约 700ms 后提前触发。
- 开启 `thinkStatus` 时，WebSocket 自动回复路径被跳过，应改用 `POST /ai/chat`。
- 自动回复完成后，服务端向该用户世界的所有活跃连接推送一个 `UserChatHistory` JSON 对象。
- 非法 JSON、未知 `type` 或缺少字段只记录日志，当前不会向客户端返回结构化错误。

## 6. CoC 跑团模组（2 个）

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `GET /coc-modules` | 无 | `CocModule[]` | 查询当前可选模组，按 ID 倒序 |
| `GET /coc-modules/{id}` | 路径 ID | `CocModule` | 查询可选模组的玩家可见详情 |

`CocModule` 字段为 `id/name/author/era/introduction/investigatorCreation/coverUrl/playerCount/estimatedDuration/visible/createdAt/updatedAt`。这两个接口只返回 `visible = true` 的模组，不暴露事件真相、隐藏线索、KP 指引等私密模组上下文。创建 TRPG 会话时也只允许绑定当前可选模组。

## 7. 群聊与 TRPG 行动轮（18 个）

### 7.1 会话、消息和历史

| 方法与路径 | 请求 | `data`/响应 | 说明 |
| --- | --- | --- | --- |
| `POST /group-chat/conversations` | `GroupConversationCreateRequest` | `GroupConversation` | 创建普通群聊或 TRPG 会话 |
| `GET /group-chat/conversations` | `userWorldId`，可选 `status` | `GroupConversationVO[]` | 查询用户世界下的会话，ID 倒序 |
| `GET /group-chat/conversations/{conversationId}` | 路径 ID | `GroupConversationVO` | 查询会话详情 |
| `GET /group-chat/conversations/{conversationId}/context-window` | 路径 ID | `ContextWindowUsage` 或 `null` | 查询最近一次 TRPG 模型提示词的字符量；Redis 记录保留 7 天 |
| `POST /group-chat/conversations/{conversationId}/close` | 无 | `GroupConversation` | 生成总结并关闭活动会话；已关闭会话会被拒绝 |
| `POST /group-chat/conversations/{conversationId}/messages` | `GroupChatRequest` | SSE `GroupChatEvent` | 普通群聊发送消息；也由公共运行时处理相应模式 |
| `GET /group-chat/conversations/{conversationId}/messages` | 可选 `beforeId/size` | `GroupChatMessageVO[]` | 查询公开历史；默认 50，范围 1..200，返回时间正序 |
| `POST /group-chat/conversations/{conversationId}/withdraw` | 无 | 无 | 仅活动中的普通 `chat` 会话可撤回最近一整轮，最多连续 3 轮 |

`GroupConversationCreateRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userWorldId` | integer | 是 | 当前用户拥有的用户世界 |
| `moduleId` | integer | 条件必填 | `trpg` 必填，`chat` 必须省略 |
| `mode` | string | 否 | `chat` 或 `trpg`，默认 `chat` |
| `title` | string | 否 | 省略/空白时为 `群聊` |
| `characterIds` | integer[] | 是 | 已加入该用户世界的角色，去重后不能为空 |

`GroupConversationVO` 字段：`id/userWorldId/worldId/moduleId/activeReplyPlanId/mode/title/summary/status/version/createdAt/updatedAt/closedAt/lastChatContent/lastChatTime`。状态主要为 `active`、`closed`。

创建和关闭接口返回数据库会话对象 `GroupConversation`，字段与上面相同但不包含计算字段 `lastChatContent/lastChatTime`。

`ContextWindowUsage` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `characterCount` | integer | 最近一次完整模型提示词的 Java 字符数，不是精确 token 数 |
| `softLimit` | integer | 当前固定为 100000 |
| `ratio` | number | `characterCount / softLimit`，可能大于 1 |
| `updatedAt` | string | UTC Instant 字符串 |

`GroupChatRequest`：

```json
{
  "clientRequestId": "9f012e84-7bb6-4bc0-a44a-b8a0cb78cc75",
  "content": "我们进入地下室。"
}
```

`content` 必须非空；TRPG 用户行动接口还限制最长 4000 字符。`clientRequestId` 可省略，但强烈建议提供。

`GroupChatMessageVO` 字段：`id/conversationId/turnId/replyStepId/speakerType/speakerId/speakerName/messageKind/content/decisionContent/sequenceNo/status/createdAt`。`speakerType` 常见 `user/character/kp/narrator`；`messageKind` 常见 `dialogue/narration/dice_roll/material/combat_result`。

### 7.2 TRPG 行动轮

以下接口只适用于 `mode = trpg`：

| 方法与路径 | 请求 | 响应 | 说明 |
| --- | --- | --- | --- |
| `POST /group-chat/conversations/{conversationId}/turns/continue` | `{ "clientRequestId": string }` | SSE | 新建下一行动轮或恢复可继续的行动轮 |
| `GET /group-chat/conversations/{conversationId}/turns/current` | 无 | `Result<GroupCurrentTurnVO|null>` | 查询当前未终结行动轮和客户端需要的下一种输入 |
| `POST /group-chat/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/retry` | 无请求体 | SSE | 仅重试失败的行动轮/步骤 |
| `POST /group-chat/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/message` | `GroupChatRequest` | SSE | 为等待用户文本输入的步骤提交行动 |
| `POST /group-chat/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/selection` | `SceneSelectionRequest` | SSE | 为选景步骤提交选项编号 |
| `POST /group-chat/conversations/{conversationId}/turns/{turnId}/steps/{stepId}/end-exploration` | `{ "clientRequestId": string }` | SSE | 当前用户调查员声明结束本场景探索 |

`SceneSelectionRequest`：

```json
{
  "clientRequestId": "uuid",
  "optionNo": "2"
}
```

调用步骤接口前应先读取 `turns/current`，严格使用其 `turnId` 和 `stepId`。`GroupCurrentTurnVO`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `turnId` | integer | 当前行动轮 ID |
| `planId` | integer/null | 回复计划 ID |
| `planSource` | string/null | `USER/SCENE/COMBAT` |
| `planContextId` | integer/null | 场景或战斗上下文 ID |
| `status` | string | 如 `running/waiting_input/paused/waiting_dice/failed/blocked` |
| `stepId` | integer/null | 当前步骤 ID |
| `actionType` | string/null | 当前动作类型 |
| `inputType` | string/null | `message/selection/continue/dice` |
| `sceneName` | string/null | 当前分组/场景名 |
| `waitingForUser` | boolean | 是否在等待用户调查员输入 |
| `sceneOptions` | object<string,string> | 选景编号到地点名的映射 |

### 7.3 回复计划

公共回复计划修改接口只允许普通 `chat` 会话。TRPG 的 `SCENE/COMBAT` 计划由跑团生命周期内部维护，不能从这些公共接口创建或修改。

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `GET /group-chat/conversations/{conversationId}/reply-plan` | 无 | `GroupReplyPlanVO` 或 `null` | 获取活动计划 |
| `PUT /group-chat/conversations/{conversationId}/reply-plan` | `GroupReplyPlanRequest` | `GroupReplyPlanVO` | 覆盖活动计划 |
| `DELETE /group-chat/conversations/{conversationId}/reply-plan` | 无 | `GroupReplyPlanVO` 或 `null` | 结束当前计划；普通群聊会建立默认计划 |
| `POST /group-chat/conversations/{conversationId}/reply-plan/advance` | 无 | `GroupReplyPlanVO` | 推进到下一计划分组 |

请求示例：

```json
{
  "source": "USER",
  "contextId": null,
  "groups": [
    {
      "key": "default",
      "name": "群聊",
      "order": 1,
      "items": [
        {
          "order": 1,
          "actorType": "character",
          "actorId": 12,
          "subjectCharacterId": null
        }
      ]
    }
  ]
}
```

组 `key` 必须唯一，每组最多 12 项，总项数最多 200；同一组不能重复同一人物。普通群聊来源必须为 `USER`。响应在请求结构外增加计划 `id/nextPlanId/resumePlanId` 和每项 `id`。

### 7.4 GroupChatEvent

所有群聊/TRPG SSE 接口复用以下稀疏事件对象；未使用字段不输出：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `eventType` | string | 事件类型 |
| `conversationId/turnId/replyStepId/messageId/sequence` | integer | 关联 ID/序号 |
| `actionType` | string | 动作类型，如 `chat_reply`、`trpg_scene_action`、`trpg_combat_action` |
| `groupKey/groupName/groupOrder/itemOrder` | mixed | 计划分组与顺序 |
| `messageKind` | string | 消息种类 |
| `speaker` | object | `type/id/name/avatar` |
| `delta` | string | 思考、决策或正文增量 |
| `content` | string | 完整内容或结构化公开文本 |
| `diceRoll` | object | `KpDiceToolResult`，见骰子模型 |
| `sceneOptions` | object<string,string> | 选景选项 |
| `sceneChoice` | object | `optionNo/controllerName/investigatorName/locationName/randomized` |
| `autoSelected` | boolean | 是否自动选景 |
| `error` | string | 失败原因 |

当前事件类型包括：

```text
turn.accepted
reply.started
reasoning.delta
decision.delta
decision.completed
message.delta
dice_roll.created
material.created
scene_selection.options
scene_selection.choice
message.completed
reply.failed
turn.completed
turn.waiting_input
turn.paused
combat.started
combat.completed
```

典型消费顺序是 `turn.accepted` → 若干 `reply.started`/增量事件 → `message.completed` → `turn.completed`。遇到 `turn.waiting_input` 或 `turn.paused` 时应停止自动推进，查询 `turns/current` 并按 `inputType` 提交下一操作。

## 8. CoC 人物卡（5 个）

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `POST /character-cards` | `CharacterCardCreateRequest` | `CharacterCardVO` | 从文本导入人物卡；`runId` 必须是 TRPG 群聊 ID |
| `DELETE /character-cards/{id}` | 路径 ID | 无 | 删除人物卡及其技能、武器和背景资料 |
| `GET /character-cards/{id}` | 路径 ID | `CharacterCardVO` | 按人物卡 ID 查询 |
| `GET /character-cards?runId={id}&participantId={id}` | 查询参数 | `CharacterCardVO` | 按跑团和参与者查询；省略 `participantId` 查询玩家卡 |
| `POST /character-cards/{id}/luck` | 路径 ID | `DiceRollResultVO` | 首次绑定幸运值，公式固定为 `3D6 * 5`；不可重复投掷 |

创建请求：

```json
{
  "runId": 100,
  "participantId": 12,
  "characterText": "调查员, 记者, 女, 27岁\n出身北平, 现居上海\n时代: 1920s\nSTR 50 CON 55 SIZ 60 DEX 65 APP 60 INT 70 POW 55 EDU 70\n——技能——\n侦查 60%\n图书馆使用 70%"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `runId` | integer | 是 | TRPG 群聊 ID |
| `participantId` | integer | 否 | 角色模板 ID；有值生成 `BOT` 卡，无值生成当前玩家的 `PLAYER` 卡 |
| `characterText` | string | 是 | 固定格式人物卡文本 |

文本首行格式为 `姓名, 职业, 性别, N岁`，年龄范围 15..90；必须包含 STR/CON/SIZ/DEX/APP/INT/POW/EDU 八项属性，同一跑团人物卡名称不可重复。识别的分段标题为“战斗”“技能”“背景故事”“装备和道具”“资产”。技能行格式为 `技能名 N%`，武器可写为 `武器名 N%, 伤害 1D6`。

`CharacterCardVO`：

- `character`：人物主数据，包括身份、八项属性、`damageBonus/build/mov`、当前/最大 `hp/san/mp`、`luckCurrent`、护甲和伤病状态；内部 `quickNotes` 不会输出。
- `skills`：技能数组，字段 `id/characterId/skillDefId/displayName/category/specialization/baseValue/value/isCustom`。
- `weapons`：武器数组，字段 `id/characterId/name/skillName/damage/range/attacksPerRound/ammoCapacity/remainingAmmo/malfunction/isBroken/notes`。
- `profile`：背景资料，字段 `appearance/ideology/significantPeople/meaningfulLocations/treasuredPossessions/traits/injuriesAndScars/phobiasAndManias/equipmentText/assetsText/spendingLevel/cash/notes` 及 ID。

注意：当前人物卡控制器的接口只经过登录拦截，服务层没有统一调用跑团会话的用户归属校验；调用方不应把可猜测的人物卡 ID 或 `runId` 暴露给非受信客户端。

## 9. 骰子结果（3 个）

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `GET /dice-rolls/{id}` | 摘要 ID | `DiceRollSummaryVO` | 查询一组掷骰摘要 |
| `GET /dice-rolls/{id}/results` | 摘要 ID | `DiceRollDetailVO[]` | 查询该摘要下各结果/轮次 |
| `POST /dice-roll-results/{id}/roll` | 待投结果 ID | `DiceRollProgressVO` | 执行一次待投骰并返回进度及新生成的后续结果 |

`DiceRollSummaryVO` 字段：`id/conversationId/reason/totalResult/roundCount/status/createdAt/updatedAt`。

`DiceRollDetailVO` 字段：`id/summaryId/characterId/roundNo/displayOrder/displayType/reason/resultData/resolution/resolvedAt/createdAt/updatedAt`。

`resultData` (`DiceRollResultVO`)：

```json
{
  "formula": "1D100",
  "modules": [
    {
      "expression": "1D100",
      "diceCount": 1,
      "diceSides": 100,
      "modifier": null,
      "dice": [
        { "sides": 100, "value": 42, "role": "normal", "selected": true }
      ],
      "result": 42
    }
  ],
  "result": 42
}
```

`resolution` 包含 `type/sourceResultId/outcome/effect`，后三者的具体键由检定、伤害、理智或治疗规则决定。`DiceRollProgressVO` 包含 `summary`（更新后摘要）、`rolledResult`（本次结果）、`createdResults`（由规则生成的后续待投结果）。

查询时会从掷骰摘要关联到群聊并校验访问权；执行玩家掷骰时还要求关联群聊处于活动状态。已经结算的玩家骰位会幂等返回已有结果。

## 10. 存档与读档（6 个）

### 10.1 世界级存档

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `GET /world-saves/{userWorldId}` | 路径 ID | `UserWorldSaveOverviewVO` 或 `null` | 获取世界存档概览 |
| `POST /world-saves/{userWorldId}` | 可省略请求体；`{ "remark": string }` | `UserWorldSaveOverviewVO` | 覆盖/创建当前世界存档 |
| `POST /world-saves/{userWorldId}/load` | 无 | 无 | 读档并回滚存档点后的世界状态 |

概览字段为 `userWorldId/savedAt/remark/characterFavors[]`，其中角色好感项含 `characterId/characterName/favorValue`。当前世界存档格式版本为 6；`remark` 会去除首尾空格，空白备注保存为 `null`。

世界存档覆盖单聊/群聊边界、近期轮次、角色状态、好感、用户/世界事件与相关派生数据。存读档期间会锁住相关世界/会话；读档是破坏当前“存档点之后进度”的操作，前端必须二次确认。

### 10.2 TRPG 会话存档

| 方法与路径 | 请求 | `data` | 说明 |
| --- | --- | --- | --- |
| `GET /trpg-saves/{conversationId}` | 路径 ID | `TrpgSaveOverviewVO` 或 `null` | 获取 TRPG 存档概览 |
| `POST /trpg-saves/{conversationId}` | 可省略请求体；`{ "remark": string }` | `TrpgSaveOverviewVO` | 覆盖/创建当前 TRPG 存档 |
| `POST /trpg-saves/{conversationId}/load` | 无 | 无 | 恢复会话、人物卡、行动轮、骰子和 Redis 运行状态 |

`TrpgSaveOverviewVO` 字段：`id/conversationId/conversationTitle/remark/savedAt/formatVersion/activePlanSource/activeSceneId/investigators`。每个 `investigators` 项含 `characterId/name/hpCurrent/hpMax/sanCurrent/sanMax/mpCurrent/mpMax/unconscious/dying/dead`。

当前 TRPG 存档格式版本为 1；备注会去除首尾空格并最多保留前 200 个字符。

TRPG 读档同样会删除存档点之后产生的数据并恢复快照；执行前应停止当前流式行动轮并由用户确认。

## 11. 图片上传（1 个）

### `POST /upload`

请求类型：`multipart/form-data`，文件字段名必须为 `file`。

```bash
curl -X POST 'http://localhost:8080/upload' \
  -H 'token: <jwt>' \
  -F 'file=@avatar.png'
```

成功时 `data` 为 OSS 图片 URL 字符串。限制：

- 最大 4 MiB。
- 仅支持 JPG/JPEG、PNG、GIF、WEBP、BMP。
- 同时校验扩展名、MIME 类型和文件头签名，不能只修改扩展名伪装图片。
- 后续世界/角色图片字段只接受指定 OSS 上传路径下的 URL。

## 12. Python 辅助服务（2 个）

Python 接口直接返回 FastAPI 响应，不使用 Java `Result` 包装，也不需要 JWT。Java 主服务调用失败时会按各自业务逻辑降级。

### 12.1 输入完整性判断

`POST http://localhost:8081/predict`

请求：

```json
{
  "context": "上一条助手回复，可为空字符串",
  "text": "用户当前累计输入"
}
```

响应是原始 JSON 布尔值：

```json
true
```

`true` 表示输入看起来已完整，可提前触发自动回复；`false` 表示继续等待。模型输入最大长度为 128 token，并带有句末标点/连接词规则预判。

### 12.2 文档重排

`POST http://localhost:8082/rerank`

请求：

```json
{
  "query": "要检索的问题",
  "documents": ["候选文档 A", "候选文档 B"],
  "top_n": 3
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `query` | string | 是 | 查询文本；空字符串返回空结果 |
| `documents` | string[] | 是 | 候选文本；空数组返回空结果 |
| `top_n` | integer | 否 | 默认 3，最小 1；大于文档数时只返回全部文档 |

响应：

```json
{
  "results": [
    { "index": 1, "score": 0.91 },
    { "index": 0, "score": 0.42 }
  ]
}
```

`index` 是原 `documents` 数组下标，结果按 `score` 降序排列。分数是当前排序模型的原始相关性输出，不保证归一化到 `0..1`。

## 13. 调用示例

登录并查询用户信息：

```bash
curl -X POST 'http://localhost:8080/user/login' \
  -H 'Content-Type: application/json' \
  -d '{"email":"20260001@bjtu.edu.cn","password":"your-password"}'

curl 'http://localhost:8080/user/info' \
  -H 'token: <上一步 data.token>'
```

消费单聊 SSE：

```bash
curl -N -X POST 'http://localhost:8080/ai/chat' \
  -H 'token: <jwt>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"worldId":1,"userWorldId":10,"characterId":12,"message":"晚上好。"}'
```

查询下一页群聊历史：

```http
GET /group-chat/conversations/100/messages?beforeId=850&size=50
token: <jwt>
```

## 14. 维护说明

新增或修改接口时，应同步检查以下来源并更新本文档：

1. `src/main/java/com/me/galchat/controller/` 中的映射注解。
2. `src/main/java/com/me/galchat/domain/dto/`、`domain/vo/` 及直接作为请求/响应的 PO。
3. `WebConfig`、`TokenInterceptor`、`GlobalExceptionHandler` 的通用行为。
4. `WebSocketServer`、`python/bert.py`、`python/reranker_server.py`。
5. SSE 事件常量 `ChatConstant`、`GroupChatConstant`。

接口路径应以运行中的后端 Controller 为准。仓库内部分旧设计文档或前端遗留调用可能包含尚未实现、已移除或已改名的路径，不应据此判断当前后端接口存在。
