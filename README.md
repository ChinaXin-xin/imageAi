# ImageAI

ImageAI 是一个面向手机膜电商图片生产的本地 Web 工具，后端使用 Spring Boot，前端使用 Vue 3 + Element Plus。它把上传图深析、目标模板风格分析、配件库、套装规格和 Image 2 生图流程串在一起，帮助生成更接近实拍结构的 Amazon / 跨境电商主图和介绍图。

项目同时保留 Codex 额度监控能力，可以通过后端代理访问 CLI Proxy API Management API，查看账号额度、图片生成余量和服务器基础运行状态。

## 核心功能

- 上传实拍图、包装图、模板图、Logo 图、壁纸图，并使用 GPT 深析可见产品细节。
- 生成主图和介绍图任务，支持平台、尺寸、语言、机型、手机颜色、设计风格、布局模式等参数。
- 针对手机膜、镜头膜等精密配件，最终提示词优先锁定外轮廓、孔位数量、孔位位置、孔位大小差异和非对称结构。
- 维护主图/介绍图目标模板，模板图片只做前置风格分析，生图时只拼接对应类型的风格文字。
- 维护额外配件库，套餐规格必须从配件库选择，生图时自动传入对应配件参考图并按数量约束生成。
- 支持图片生成任务队列、暂停、继续、重试、删除、结果查看、最终提示词查看。
- 支持默认提示词设置，包括主图提示词、介绍图提示词、上传图深析提示词和目标模板分析提示词。
- 支持 Codex 账号额度和系统概览看板。

## 技术栈

后端：

- Java 17
- Spring Boot 3.5.14
- Spring Web
- MyBatis Spring Boot Starter
- MySQL 8
- Maven Wrapper

前端：

- Vue 3
- Vite 5
- TypeScript
- Element Plus
- `@element-plus/icons-vue`

外部接口：

- CLI Proxy API Management API
- GPT Chat Completions API
- Image 2 图片生成 / 图片编辑接口

## 项目结构

```text
imageAiOriginal/
├── AGENTS.md
├── README.md
├── pom.xml
├── mvnw
├── mvnw.cmd
├── src/
│   ├── main/
│   │   ├── java/xin/students/imageaioriginal/
│   │   │   ├── config/
│   │   │   ├── controller/
│   │   │   ├── model/
│   │   │   └── service/
│   │   └── resources/
│   │       └── application.yaml
│   └── test/
│       └── java/xin/students/imageaioriginal/
└── vue3/
    ├── index.html
    ├── package.json
    ├── vite.config.ts
    └── src/
        ├── App.vue
        ├── components/
        ├── services/
        ├── types/
        └── assets/
```

## 运行环境

- JDK 17
- Node.js 20+
- MySQL 8
- Maven Wrapper 已随项目提供

前端默认运行在：

```text
http://127.0.0.1:5173
```

后端默认运行在：

```text
http://127.0.0.1:8080
```

Vite 已配置 `/api` 代理到 Spring Boot 后端。

## 配置说明

核心配置位于 `src/main/resources/application.yaml`。建议本地通过环境变量覆盖敏感信息，不要把真实管理密钥、API Key、Token 提交到 GitHub。

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:33306/image_ai_original?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true
    username: root
    password: your_mysql_password

image-ai:
  cli-proxy:
    base-url: ${IMAGE_AI_CLI_PROXY_BASE_URL:http://127.0.0.1:8317}
    management-key: ${IMAGE_AI_CLI_PROXY_MANAGEMENT_KEY:}
  gpt:
    base-url: ${IMAGE_AI_GPT_BASE_URL:http://127.0.0.1:8317}
    api-key: ${IMAGE_AI_GPT_API_KEY:}
    model: ${IMAGE_AI_GPT_MODEL:gpt-5.5}
  image-generation:
    model: ${IMAGE_AI_IMAGE_MODEL:gpt-image-2}
    max-task-concurrency: ${IMAGE_AI_MAX_TASK_CONCURRENCY:4}
    max-images-per-task: ${IMAGE_AI_MAX_IMAGES_PER_TASK:3}
    max-global-image-concurrency: ${IMAGE_AI_MAX_GLOBAL_IMAGE_CONCURRENCY:6}
```

安全约定：

- 前端不要直接访问 CLI Proxy 管理端。
- 管理密钥和 API Key 只允许后端读取。
- 不提交 `node_modules/`、`dist/`、`.env.local`、日志和构建产物。

## 启动项目

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

打开：

```text
http://127.0.0.1:5173
```

## 构建与测试

后端测试：

```bash
.\mvnw.cmd test
```

后端编译：

```bash
.\mvnw.cmd -DskipTests compile
```

前端构建：

```bash
cd vue3
npm run build
```

前端预览：

```bash
cd vue3
npm run preview
```

## 后端接口概览

常用接口按功能分组：

```text
GET  /api/codex/quota/accounts      Codex 额度账号列表
GET  /api/system/overview           系统概览
GET  /api/settings/prompts          默认提示词设置
POST /api/settings/prompts          保存默认提示词设置
POST /api/tasks                     创建生图任务
GET  /api/tasks                     任务列表
GET  /api/tasks/{id}                任务详情
POST /api/tasks/{id}/retry          重试任务
POST /api/tasks/{id}/pause          暂停任务
POST /api/tasks/{id}/resume         继续任务
DELETE /api/tasks/{id}              删除任务
POST /api/analysis/upload           深析上传图
GET  /api/target-templates          目标模板列表
POST /api/target-templates          新增并分析目标模板
DELETE /api/target-templates/{id}   删除目标模板
GET  /api/extra-accessories         额外配件列表
POST /api/extra-accessories         新增额外配件
DELETE /api/extra-accessories/{id}  删除额外配件
```

## 生图提示词策略

项目的最终生图提示词不是简单堆风格词，而是按优先级拼接：

```text
真实产品结构锁定/参考图优先级
→ 上传图深析结果
→ 手机膜/镜头膜结构生成规则
→ 套装规格数量锁定
→ 任务参数/规格
→ 主图/介绍图画面要求
→ 目标模板风格
→ 负面约束/生成要求
```

对手机膜、镜头膜、保护壳等精密配件，生图时优先保留：

- 外轮廓和异形边缘
- 开孔数量
- 开孔位置
- 开孔大小差异
- 非对称结构
- 配件形状、颜色、材质和数量

目标模板只提供构图、光影、背景、质感和排版风格，不把模板商品作为当前商品，也不把目标模板原图传给生图模型。

当主图或介绍图数量大于 1 时，后端会先调用 GPT 生成 JSON 场景规划，再把对应场景描述拼入每一张图的生图提示词。返回格式类似：

```json
{
  "scenes": [
    {
      "index": 1,
      "sceneTitle": "深色科技平铺主图",
      "prompt": "深色碳纤维背景，45 度俯拍，冷蓝边缘光，突出镜头膜孔位和屏幕膜玻璃反射。"
    }
  ]
}
```

这样同一个任务里的多张图片会尽量拥有不同场景、构图、背景、光影、展示角度或卖点表达，同时仍然继承基础提示词里的真实结构锁定和套装数量约束。如果 GPT 场景规划返回异常，后端会使用内置备用场景，避免任务中断。

## 多参考图和超时处理

生图任务携带上传原图和配件参考图时，后端会先压缩参考图，减少请求体积。当前生图请求设置了硬超时，避免接口长时间不返回导致任务卡死。

生图支持并发执行：

- `IMAGE_AI_MAX_TASK_CONCURRENCY`：最多同时处理几个任务，默认 `4`。
- `IMAGE_AI_MAX_IMAGES_PER_TASK`：单个任务内最多同时生成几张图片，默认 `3`。
- `IMAGE_AI_MAX_GLOBAL_IMAGE_CONCURRENCY`：全局最多同时发起几个生图请求，默认 `6`。

暂停或删除任务时，后端会取消该任务正在进行的本地生图请求；如果远端接口后续才返回，已暂停或已删除的任务也不会再写入成功结果。

如果任务超时，可以优先排查：

- 上传参考图是否过多或过大
- 最终提示词是否过长
- Image 2 服务是否拥堵
- 网络代理或 CLI Proxy 是否响应异常

任务详情页会展示最终提示词，便于检查结构约束和套装数量是否正确。

## 开发规范

- Vue 代码统一放在 `vue3/` 目录下。
- 前端接口调用统一封装在 `vue3/src/services/`。
- 页面优先使用 Element Plus，保持加载态、错误态、空态完整。
- 后端和前端改动保持小范围，不做无关业务和大规模重构。
- 新增或修改长期项目约定时，同步维护 `AGENTS.md`。
- 真实 `.env.local` 只保留在本地，不提交仓库。

## 常见问题

如果后端提示“不支持发行版本 17”，说明当前 `JAVA_HOME` 指向了 JDK 11 或更低版本，需要切换到 JDK 17。

如果前端接口 404 或跨域异常，确认 Spring Boot 后端已启动，并检查 `vue3/vite.config.ts` 中 `/api` 代理目标是否是 `http://127.0.0.1:8080`。

如果生图结果把镜头膜孔位做成统一大小，优先检查上传图深析结果中是否明确写出了孔位数量、位置和大小差异，再检查最终提示词中的“结构锁定”和“负面约束”。
