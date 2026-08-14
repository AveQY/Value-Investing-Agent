# AI 价投分析智能体 API 使用文档

## 概述

AI 价投分析智能体 API 是一个基于 Spring Boot 和 AI 技术构建的智能投资顾问系统，提供基金和股票投资相关的智能咨询服务。

- **基础路径**: `/api`
- **文档地址**: `http://localhost:8123/api/swagger-ui.html`
- **API 文档**: `http://localhost:8123/api/v3/api-docs`

## 认证

API 支持可选的 API Key 认证（默认关闭）。

**请求头**:
```
X-API-KEY: your-api-key
```

## 接口列表

### 1. 普通聊天

发送普通聊天消息，获取 AI 投资顾问的回复。

**请求**:
```http
POST /api/fund/chat
Content-Type: application/json

{
  "chatId": "chat-001",
  "message": "分析一下茅台的长期价值"
}
```

**参数说明**:
| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| chatId | string | 是 | 会话 ID，仅允许字母、数字、下划线和短横线，最大长度 64 |
| message | string | 是 | 用户输入内容，最大长度 4000 |
| userName | string | 否 | 用户名，仅在报告生成接口中使用 |

**响应**:
```json
{
  "chatId": "chat-001",
  "mode": "CHAT",
  "content": "茅台具有较强品牌护城河...",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### 2. 生成投资报告

生成结构化的投资分析报告。

**请求**:
```http
POST /api/fund/chat/report
Content-Type: application/json

{
  "chatId": "chat-002",
  "message": "请生成宁德时代报告",
  "userName": "李雷"
}
```

**响应**:
```json
{
  "chatId": "chat-002",
  "title": "李雷的数据报告",
  "suggestions": [
    "关注估值",
    "分批建仓",
    "长期持有"
  ],
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### 3. RAG 增强对话

基于本地知识库的 RAG（检索增强生成）对话模式。

**请求**:
```http
POST /api/fund/chat/rag
Content-Type: application/json

{
  "chatId": "chat-003",
  "message": "为什么挂单没成交"
}
```

**响应**:
```json
{
  "chatId": "chat-003",
  "mode": "RAG",
  "content": "根据本地知识库，挂单未成交可能是因为...",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**说明**: RAG 模式会优先从本地知识库检索相关信息，结合 AI 模型生成回答。

### 4. 工具增强对话

启用工具调用功能的对话模式，AI 可以使用各种工具（文件操作、资源下载、终端执行、PDF 生成、网页搜索等）。

**请求**:
```http
POST /api/fund/chat/tools
Content-Type: application/json

{
  "chatId": "chat-004",
  "message": "帮我生成一份投资报告并保存为 PDF"
}
```

**响应**:
```json
{
  "chatId": "chat-004",
  "mode": "TOOLS",
  "content": "已调用 PDF 生成工具，报告已保存至...",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**可用工具**:
- 文件操作工具
- PDF 生成工具
- 资源下载工具
- 终端操作工具
- 网页搜索工具

### 5. 价值分析模式

按“生意 / 人 / 价格”三段组织分析，优先补充缺失信息，再结合本地知识库与实时工具结果输出固定结构。

**请求**:
```http
POST /api/fund/chat/value
Content-Type: application/json

{
  "chatId": "chat-005",
  "message": "请按价值投资框架分析贵州茅台"
}
```

**响应**:
```json
{
  "chatId": "chat-005",
  "mode": "VALUE",
  "content": "## 商业模式\n...\n## 护城河\n...\n## 管理层\n...\n## 能力圈\n...\n## 安全边际\n...\n## 风险\n...\n## 结论\n...\n## 还缺什么数据\n...",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**说明**:
- 内部由 `ValueAnalysisService` 执行
- 先拆分“生意 / 人 / 价格”关注点
- 再查询实时价格、公告、新闻等公开信息
- 最终固定输出 `商业模式 / 护城河 / 管理层 / 能力圈 / 安全边际 / 风险 / 结论 / 还缺什么数据`

### 6. 获取聊天历史

获取指定会话的历史消息记录。

**请求**:
```http
GET /api/fund/history/{chatId}
```

**响应**:
```json
{
  "chatId": "chat-001",
  "messageCount": 4,
  "messages": [
    {
      "role": "USER",
      "content": "分析一下茅台的长期价值"
    },
    {
      "role": "ASSISTANT",
      "content": "茅台具有较强品牌护城河..."
    }
  ],
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### 7. 删除聊天历史

删除指定会话的历史记录。

**请求**:
```http
DELETE /api/fund/history/{chatId}
```

**响应**:
```json
{
  "chatId": "chat-001",
  "message": "聊天历史已删除",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

## 错误处理

### 错误响应格式

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "请求参数校验失败",
  "path": "/api/fund/chat",
  "details": {
    "chatId": "chatId 只能包含字母、数字、下划线和短横线",
    "message": "message 不能为空"
  }
}
```

### 错误码说明

| HTTP 状态码 | 说明 | 场景 |
|------------|------|------|
| 200 | 成功 | 请求处理成功 |
| 400 | 请求参数错误 | 参数校验失败、格式错误 |
| 404 | 资源不存在 | 聊天历史不存在 |
| 500 | 服务器内部错误 | 系统异常 |

## 使用示例

### cURL 示例

**普通聊天**:
```bash
curl -X POST http://localhost:8123/api/fund/chat \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": "chat-001",
    "message": "分析一下茅台的长期价值"
  }'
```

**生成报告**:
```bash
curl -X POST http://localhost:8123/api/fund/chat/report \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": "chat-002",
    "message": "请生成宁德时代报告",
    "userName": "张三"
  }'
```

**获取历史**:
```bash
curl http://localhost:8123/api/fund/history/chat-001
```

## 注意事项

1. **chatId 格式**: 只能包含字母、数字、下划线和短横线，最大长度 64 个字符
2. **消息长度**: 单次消息最大长度 4000 字符
3. **会话保持**: 使用相同的 chatId 可以保持对话上下文
4. **历史记录**: 聊天历史默认存储在 `tmp/chat-memory` 目录
5. **RAG 知识库**: 基于本地 Markdown 文档（基金/股票投资常见问题）
6. **价值分析模式**: 默认优先做方法论检索，再结合实时股价与搜索结果补充事实依据

## 相关链接

- [Swagger UI](http://localhost:8123/api/swagger-ui.html)
- [API Docs (JSON)](http://localhost:8123/api/v3/api-docs)
