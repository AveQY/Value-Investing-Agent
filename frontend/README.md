# AI Agent Fund Frontend

基于 Vue 3 + Vite + Axios 的前端工作台，包含：

- 应用主页：切换不同应用入口
- AI 价值投资顾问页：自动生成 `chatId`、对话历史恢复、普通对话 / RAG / 工具增强模式切换
- 结构化报告按钮：对接 `/api/fund/chat/report`

## 启动

```bash
npm install
npm run dev
```

默认通过 Vite 代理请求 `http://localhost:8123/api`。

## 环境变量

参考 [`.env.example`](./.env.example)：

- `VITE_API_BASE_URL`：接口基础路径，默认 `/api`
- `VITE_API_KEY`：可选，后端启用 API Key 时填入
- `VITE_ENABLE_SSE_CHAT`：是否启用 SSE 流式接口

## 关于 SSE

后端已提供 SSE 流式接口（`/fund/chat/sse`、`/fund/chat/rag/sse`、`/fund/chat/tools/sse`）：

- 当 `VITE_ENABLE_SSE_CHAT=true`（默认）时，前端优先使用流式接口
- 若 SSE 接口不可用，会自动回落到 REST 接口，并以增量渲染方式展示回复
