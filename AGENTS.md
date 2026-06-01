# AGENTS.md

## 项目结构

- `pom.xml`：Spring Boot 3.5.14 后端工程配置，JDK 17。
- `src/main/java/xin/students/imageaioriginal/`：后端 Java 源码入口。
- `src/main/resources/application.yaml`：后端应用配置，包含 CLI Proxy 管理端地址和管理密钥。
- `src/test/java/xin/students/imageaioriginal/`：后端测试。
- `vue3/`：Vue 3 前端代码，本项目所有 Vue 代码统一放在这里。
- `.mvn/`、`mvnw`、`mvnw.cmd`：Maven Wrapper。

## 技术栈

- 后端：JDK 17、Spring Boot 3.5.14、MyBatis、MySQL 8、Redis。
- 前端：Vue 3、Vite、TypeScript、Element Plus。
- 额度接口来源：Cli-Proxy-API-Management-Center / CLI Proxy API Management API。
- 前端不要直接访问管理端，统一调用本项目后端 `/api/**` 接口。
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

前端默认通过 Vite 代理把 `/api` 转发到 Spring Boot：

```bash
cd vue3
npm run dev
```

## 开发规范

- 不新建无关项目，所有前端代码放在 `vue3/` 下。
- 不在前端代码中硬编码管理密钥、API Key、Token 等敏感信息。
- 管理端访问由后端封装，前端只访问本项目后端接口。
- 前端接口访问统一封装在 `vue3/src/services/`。
- 页面优先使用 Element Plus 组件，保持加载态、错误态、空态完整。
- 后端和前端改动保持小范围，不做无关业务和大规模重构。
- 新增配置示例使用 `.env.example`，真实 `.env.local` 只保留在本地。
- 每次新增、修改、删除或查询项目长期约定时，都要同步维护 `AGENTS.md`。
- 最终生图提示词按“上传图深析结果 → 主图/介绍图提示词默认内容 → 任务参数/规格 → 生成要求”的顺序拼接；不要把深析上传图的提示词本身拼进最终生图提示词。
- 任务详情不展示生图接口返回的 `revised_prompt`，避免和最终生图提示词混淆。
- 图片放大查看器要适配横屏笔记本：小图要放大到合适尺寸，大图不能超出可视区域；蒙版使用 `rgba(25, 25, 25, 0.88)`，点击非图片区域关闭。
- 目标模板分为主图和介绍图两类；主图目标模板只能拼入主图生图提示词和主图参考图，介绍图目标模板只能拼入介绍图生图提示词和介绍图参考图。
- 上传图片后的前端展示必须使用缩略图预览，不展示 Element Plus 默认文件名列表；长文本“查看全文”弹窗使用 Markdown 渲染。
- 目标模板图片分析提示词必须可在前端默认设置里修改并保存到数据库，不能硬编码在目标模板分析逻辑中。

## Git 提交要求

- 每次完成任务后执行。
- commit 时用中文。

```bash
git status
git add .
git commit -m "feat: add codex quota dashboard"
```

- 提交前确认没有把 `node_modules/`、`dist/`、`.env.local` 提交到仓库；除用户明确要求写入后端配置的管理密钥外，不提交其他敏感信息。
