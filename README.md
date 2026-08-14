# AI 价投分析智能体

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

基于 Spring Boot 和 AI 技术构建的智能投资顾问系统，专注于提供基金和股票投资相关的智能咨询服务。

## 项目简介

本项目是一个 AI 驱动的投资顾问系统，以巴菲特、芒格、段永平的价值投资理念为指导，结合 RAG（检索增强生成）技术和多种 AI 工具，为用户提供专业的投资建议和报告生成服务。

## 技术栈

- **基础框架**: Spring Boot 3.4.4
- **开发语言**: Java 17
- **AI 技术**: Spring AI 1.0.0-M6, LangChain4j 1.0.0-beta2
- **向量存储**: PgVector (PostgreSQL)
- **API 文档**: SpringDoc OpenAPI 3 + Knife4j
- **构建工具**: Maven

## 功能特性

### 核心功能
- 🤖 **智能对话**: 基于价值投资理念的投资咨询服务
- 🧭 **价值分析模式**: 按“生意 / 人 / 价格”三段补充信息并输出固定结构
- 📊 **报告生成**: 自动生成结构化投资分析报告
- 🔍 **RAG 增强**: 结合本地知识库提供更准确的回答
- 🛠️ **工具调用**: 支持实时股价查询、文件操作、资源下载、终端执行、PDF 生成、网页搜索等工具
- 💬 **历史管理**: 支持聊天历史的查询和删除

### 技术特性
- ✅ RESTful API 设计
- ✅ 参数校验和异常处理
- ✅ Swagger/OpenAPI 文档自动生成
- ✅ 可选的 API Key 认证
- ✅ 多种 AI 模型支持（OpenAI 兼容）
- ✅ 向量存储支持（PgVector）

## 快速开始

### 环境要求

- Java 17+
- Maven 3.6+
- PostgreSQL 14+ (可选，用于向量存储)

### 1. 克隆项目

```bash
git clone https://github.com/AveQY/Value-Investing-Agent.git
cd Value-Investing-Agent
```

### 2. 配置环境

项目使用 `application-local.yml` 作为本地开发配置（包含敏感信息，已被 `.gitignore` 忽略，不会提交到仓库）。

复制配置模板并按需修改：

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

需要配置的主要项：

- OpenAI 兼容接口的 API Key、Base URL 和模型
- PostgreSQL 数据库连接（可选，用于 PgVector 向量存储）

### 3. 启动 PostgreSQL (可选)

如果使用 PgVector 向量存储功能，需要启动 PostgreSQL：

```bash
docker run -d \
  --name postgres \
  -e POSTGRES_PASSWORD=123456 \
  -e POSTGRES_DB=ai-agent \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

### 4. 运行项目

```bash
./mvnw spring-boot:run
```

或使用 Maven Wrapper：

```bash
mvnw.cmd spring-boot:run  # Windows
./mvnw spring-boot:run     # Linux/Mac
```

### 5. 访问服务

- **API 地址**: http://localhost:8123/api
- **Swagger UI**: http://localhost:8123/api/swagger-ui.html
- **API 文档**: http://localhost:8123/api/v3/api-docs

## API 使用

### 普通聊天

```bash
curl -X POST http://localhost:8123/api/fund/chat \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": "chat-001",
    "message": "分析一下茅台的长期价值"
  }'
```

### 价值分析模式

```bash
curl -X POST http://localhost:8123/api/fund/chat/value \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": "chat-003",
    "message": "请按价值投资框架分析贵州茅台"
  }'
```

### 生成投资报告

```bash
curl -X POST http://localhost:8123/api/fund/chat/report \
  -H "Content-Type: application/json" \
  -d '{
    "chatId": "chat-002",
    "message": "请生成宁德时代报告",
    "userName": "张三"
  }'
```

更多 API 使用示例请参考 [API 文档](docs/API.md)。

## 项目结构

```
ai-agent/
├── src/
│   ├── main/
│   │   ├── java/com/aweqy/aiagent/
│   │   │   ├── advisor/          # 聊天顾问
│   │   │   ├── app/              # 核心应用逻辑
│   │   │   ├── chatmemory/       # 聊天记忆管理
│   │   │   ├── config/           # 配置类
│   │   │   ├── constant/         # 常量定义
│   │   │   ├── controller/       # API 控制器
│   │   │   ├── demo/             # 演示代码
│   │   │   ├── exception/        # 异常处理
│   │   │   ├── filter/           # 过滤器
│   │   │   ├── model/            # 数据模型
│   │   │   ├── rag/              # RAG 功能
│   │   │   ├── service/          # 服务层
│   │   │   ├── tools/            # 工具集成
│   │   │   └── AiAgentApplication.java
│   │   └── resources/
│   │       ├── document/         # 知识库文档
│   │       ├── sql/              # SQL 脚本
│   │       ├── application.yml
│   │       └── application-local.yml.example  # 本地配置模板
├── docs/                         # 文档
├── pom.xml                       # Maven 配置
└── README.md                     # 项目说明
```

## 核心模块

### FundApp
核心应用类，提供四种对话模式：
- `doChat`: 普通聊天
- `doChatWithReport`: 生成结构化报告
- `doChatWithRag`: RAG 增强对话
- `doChatWithTools`: 工具增强对话

### ValueAnalysisService
- `analyze`: 价值分析模式
- `analyzeStream`: 价值分析模式流式输出
- 先按“生意 / 人 / 价格”拆解用户问题，再结合本地价值投资知识和实时工具结果输出固定结构

### RAG 功能
- **文档加载**: 支持 Markdown 格式知识库
- **向量存储**: 可选 PgVector 或内存存储
- **本地检索**: 基于关键词的文档检索

### 工具系统
- 实时股价查询工具
- 文件操作工具
- PDF 生成工具
- 资源下载工具
- 终端操作工具
- 网页搜索工具

## 配置说明

### 应用配置 (application.yml)

```yaml
server:
  port: 8123
  servlet:
    context-path: /api

spring:
  ai:
    openai:
      api-key: your-api-key
      base-url: https://api.edgefn.net/v1
```

### 本地配置 (application-local.yml)

本地开发配置，包含敏感信息，已添加到 `.gitignore`。

## 开发指南

### 添加新的 AI 工具

1. 在 `tools` 包中创建工具类
2. 实现 `ToolCallback` 接口
3. 在 `ToolRegistration` 中注册工具

### 扩展 RAG 知识库

1. 在 `resources/document` 目录添加 Markdown 文件
2. 重启应用自动加载

### 自定义系统提示词

修改 `FundApp` 类中的 `SYSTEM_PROMPT` 常量。

## 部署

### Docker 部署 (待完成)

```bash
# 构建镜像
docker build -t ai-agent .

# 运行容器
docker run -d -p 8123:8123 ai-agent
```

### 生产环境注意事项

1. 启用 API Key 认证 (`app.api.security.enabled: true`)
2. 配置真实的 API Key
3. 使用生产级数据库
4. 配置日志级别为 WARN 或 ERROR
5. 启用 HTTPS

## 技术亮点

- **模块化设计**: 清晰的代码结构和模块划分
- **多种 AI 集成**: 支持 Spring AI、LangChain4j、OpenAI SDK
- **灵活的 RAG**: 支持多种向量存储方案
- **标准化 API**: RESTful 设计，完整的 Swagger 文档

## 相关文档

- [API 使用文档](docs/API.md)
- [Swagger UI](http://localhost:8123/api/swagger-ui.html) (本地运行后访问)

## 贡献指南

欢迎提交 Issue 和 Pull Request。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 联系方式

如有问题或建议，欢迎联系项目维护者。

---

**注意**: 本项目仅供学习和研究使用，不构成投资建议。投资有风险，入市需谨慎。
