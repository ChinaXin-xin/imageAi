# AGENTS.md

## 项目结构

- `pom.xml`：Spring Boot 3.5.14 后端工程配置，JDK 17。
- `src/main/java/xin/students/imageaioriginal/`：后端 Java 源码入口。
- `src/main/resources/application.yaml`：后端应用配置。
- `src/test/java/xin/students/imageaioriginal/`：后端测试。
- `vue3/`：Vue 3 前端代码，本项目所有 Vue 代码统一放在这里。
- `.mvn/`、`mvnw`、`mvnw.cmd`：Maven Wrapper。

## 技术栈

- 后端：JDK 17、Spring Boot 3.5.14、MyBatis、MySQL 8、Redis。
- 前端：Vue 3、Vite、TypeScript、Element Plus。
- 额度接口来源：Cli-Proxy-API-Management-Center / CLI Proxy API Management API。
- 管理 API：`/v0/management`，通过 `Authorization: Bearer <管理密钥>` 鉴权。

## 运行命令

后端：

```bash
./mvnw spring-boot:run
```

Windows：

```bash
mvnw.cmd spring-boot:run
```

前端：

```bash
cd vue3
npm install
npm run dev
npm run build
```

本地前端环境变量放在 `vue3/.env.local`，不要提交真实管理密钥：

```bash
VITE_CLI_PROXY_BASE_URL=http://43.160.220.91:8317
VITE_CLI_PROXY_MGMT_KEY=<管理密钥>
```

## 开发规范

- 不新建无关项目，所有前端代码放在 `vue3/` 下。
- 不在页面代码中硬编码管理密钥、API Key、Token 等敏感信息。
- 前端接口访问统一封装在 `vue3/src/services/`。
- 页面优先使用 Element Plus 组件，保持加载态、错误态、空态完整。
- 后端和前端改动保持小范围，不做无关业务和大规模重构。
- 新增配置示例使用 `.env.example`，真实 `.env.local` 只保留在本地。

## Git 提交要求

- 每次完成任务后执行：

```bash
git status
git add .
git commit -m "feat: add codex quota dashboard"
```

- 提交前确认没有把 `node_modules/`、`dist/`、`.env.local` 或真实密钥提交到仓库。
