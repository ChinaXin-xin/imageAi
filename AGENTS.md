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
- 最终生图提示词按“真实产品结构锁定/参考图优先级 → 上传图深析结果 → 手机膜/镜头膜结构生成规则 → 套装规格数量锁定 → 任务参数/规格 → 主图/介绍图画面要求 → 目标模板风格 → 负面约束/生成要求”的顺序拼接；不要把深析上传图的提示词本身拼进最终生图提示词。
- 手机膜、镜头膜、保护壳等精密配件生图必须先锁外轮廓、孔位数量、孔位位置、孔位大小差异和非对称结构，再应用目标模板和风格词；风格词不能覆盖上传实拍图结构。
- 最终生图提示词要保持短而明确，深析结果和目标模板风格进入生图前需要做长度控制，避免多参考图任务因提示词过长导致生图接口超时。
- 任务详情不展示生图接口返回的 `revised_prompt`，避免和最终生图提示词混淆。
- 图片放大查看器要适配横屏笔记本：小图要放大到合适尺寸，大图不能超出可视区域；蒙版使用 `rgba(25, 25, 25, 0.88)`，点击非图片区域关闭。
- 目标模板分为主图和介绍图两类；主图目标模板只能拼入主图生图提示词，介绍图目标模板只能拼入介绍图生图提示词。
- 目标模板图片只用于前置风格分析，生图时只拼接对应类型的目标模板风格文字，不把目标模板原图作为生图参考图传给模型。
- 最终生图提示词里不要展示目标模板文件名，也不要写“目标模板图片不传给模型”之类的实现说明，只保留对应类型的目标模板风格分析和约束。
- 上传图片后的前端展示必须使用缩略图预览，不展示 Element Plus 默认文件名列表；长文本“查看全文”弹窗使用 Markdown 渲染。
- 生图接口需要携带上传原图或额外配件参考图时，后端先将参考图等比压缩为适合 API 传输的图片，避免原始大图导致请求超时。
- 生图接口响应不能假定一定是 `application/json`；如果返回图片二进制或 `application/octet-stream`，后端要转成 base64 结果保存。
- 生图任务执行支持并发，需通过 `image-ai.image-generation.max-task-concurrency`、`max-images-per-task`、`max-global-image-concurrency` 控制任务并发、单任务图片并发和全局图片并发；不要使用无限制线程池直接打满外部 API。
- 暂停或删除任务时必须取消该任务正在进行的所有本地生图请求，并确保后续返回结果不会写入已暂停或已删除任务。
- “套装规格与卖点”中的套餐规格必须从数据库中的“额外配件”库选择，配件包含名称和图片；任务保存所选配件 ID、名称与数量，数量最低为 1。
- 额外配件用于套餐规格时，生图必须把已选择配件的原图作为参考图传入，并在最终提示词里要求按数量精确出现，未选择的配件不要出现。
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

