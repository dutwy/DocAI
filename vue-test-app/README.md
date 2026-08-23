# DocAI Web

DocAI 的 Vue 3 前端，提供登录注册、文档编辑、版本管理、AI Agent 工作台、RAG 索引和协同编辑界面。

## 环境要求

- Node.js 22.12+
- npm 10+
- 默认开发 API 网关：`http://localhost:8080`

如需修改后端地址，请设置根 `.env` 中的 `VITE_API_TARGET`，不要把真实环境文件提交到仓库。

## 开发

```bash
npm ci
npm run dev
```

开发服务器默认运行在 `http://localhost:5173`。

## 生产构建

```bash
npm audit --omit=dev --audit-level=high
npm run build
npm run preview
```

构建输出位于 `dist/`，该目录不纳入版本控制。所有业务请求应通过 DocAI Gateway 访问。
