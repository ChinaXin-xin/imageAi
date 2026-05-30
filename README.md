# ImageAI

ImageAI 是一个基于 Spring Boot 3 和 Vue 3 的 Codex 额度监控工具。当前版本聚焦于通过本项目后端代理访问 CLI Proxy API Management API，集中展示 Codex 账号额度、图片生成余量以及服务器基础运行状态。

## 当前环境

- 操作系统：Windows 11（开发环境）
- JDK：17
- Spring Boot：3.5.14
- Node.js：20.11.1
- 前端框架：Vue 3
- 数据库：MySQL 8
- MySQL 连接：`root / 123456 / 33306`
- Redis：默认端口，无账号密码
- CLI Proxy 管理端：`http://43.160.220.91:8317`
- Git：已初始化

## 技术栈

后端：

- Java 17
- Spring Boot 3.5.14
- Spring Web
- MyBatis Spring Boot Starter
- MySQL Connector/J
- Lombok
- Maven Wrapper

前端：

- Vue 3
- Vite 5
- TypeScript
- Element Plus
- `@element-plus/icons-vue`

外部接口：

- CLI Proxy API Management API
- 管理接口前缀：`/v0/management`
- 鉴权方式：`Authorization: Bearer <管理密钥>`
- 前端不直接访问管理端，统一调用本项目后端 `/api/**` 接口。

## 已实现功能

- Codex 账号列表与状态展示
- 5 小时额度百分比展示
- 每周额度百分比展示
- 5 小时可生成图片数换算：`1% = 1 张`
- 每周可生成图片数换算：`1% = 8 张`
- 账号额度卡片、进度条、状态标签、刷新按钮
- ImageAI 左侧导航栏，支持折叠
- 系统概览：系统版本、CPU 占比、内存信息、磁盘信息
- 前端通过后端代理访问管理 API，避免管理密钥暴露到浏览器代码

## 代码结构

```text
imageAiOriginal/
├── AGENTS.md
├── README.md
├── RELEASE_DESCRIPTION.md
├── pom.xml
├── mvnw
├── mvnw.cmd
├── src/
│   ├── main/
│   │   ├── java/xin/students/imageaioriginal/
│   │   │   ├── ImageAiOriginalApplication.java
│   │   │   ├── config/
│   │   │   │   └── CliProxyProperties.java
│   │   │   ├── controller/
│   │   │   │   ├── CodexQuotaController.java
│   │   │   │   └── SystemOverviewController.java
│   │   │   ├── model/
│   │   │   │   ├── CodexQuotaAccount.java
│   │   │   │   ├── QuotaWindowView.java
│   │   │   │   └── SystemOverview.java
│   │   │   └── service/
│   │   │       ├── CodexQuotaService.java
│   │   │       └── SystemOverviewService.java
│   │   └── resources/
│   │       └── application.yaml
│   └── test/
│       └── java/xin/students/imageaioriginal/
│           └── ImageAiOriginalApplicationTests.java
└── vue3/
    ├── index.html
    ├── package.json
    ├── vite.config.ts
    ├── tsconfig.json
    └── src/
        ├── App.vue
        ├── main.ts
        ├── style.css
        ├── assets/
        │   └── imageai-icon.png
        ├── services/
        │   ├── codexQuotaApi.ts
        │   └── systemApi.ts
        └── types/
            └── quota.ts
```

## 后端接口

```text
GET /api/codex/quota/accounts
```

返回 Codex 账号额度数据。后端会读取管理端配置，通过 `/v0/management/auth-files` 和 `/v0/management/api-call` 获取真实额度。

```text
GET /api/system/overview
```

返回系统概览数据，包括应用版本、系统类型、系统版本、CPU、内存和磁盘信息。

## 配置说明

核心配置在 `src/main/resources/application.yaml`：

```yaml
spring:
  application:
    name: ImageAI
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:33306/image_ai_original?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: 123456

image-ai:
  cli-proxy:
    base-url: http://43.160.220.91:8317
    management-key: <管理密钥>
```

注意：管理密钥只应由后端读取，不要写入前端代码或 `.env` 文件。

## 运行方式

启动后端：

```bash
.\mvnw.cmd spring-boot:run
```

启动前端：

```bash
cd vue3
npm install
npm run dev
```

前端默认访问：

```text
http://127.0.0.1:5173
```

Vite 已配置 `/api` 代理到：

```text
http://127.0.0.1:8080
```

## 构建命令

前端构建：

```bash
cd vue3
npm run build
```

后端编译：

```bash
.\mvnw.cmd -DskipTests compile
```

后端测试：

```bash
.\mvnw.cmd test
```

如果出现 `不支持发行版本 17`，说明当前 `JAVA_HOME` 指向了 JDK 11 或更低版本，需要切换到 JDK 17。

## 开发规范

- Vue 代码统一放在 `vue3/` 目录下。
- 前端只访问本项目后端接口，不直接请求 CLI Proxy 管理端。
- 管理密钥、API Key、Token 等敏感信息不要写入前端代码。
- 页面样式保持商务后台风格，避免无关业务和大规模重构。
- 后端新增接口优先放在 `controller`、`service`、`model` 对应目录中。
- 提交前确认没有提交 `node_modules/`、`dist/`、`.env.local`、临时构建缓存。
