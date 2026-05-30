# Describe This Release

## ImageAI Codex Quota Dashboard

This release introduces the first usable ImageAI dashboard for monitoring Codex account quotas through the CLI Proxy API Management API. It adds a Spring Boot backend proxy layer so the management key stays on the server side, while the Vue 3 frontend displays account quota status in a clean business-oriented dashboard.

### Highlights

- Added Codex quota monitoring page under the ImageAI web UI.
- Added backend proxy access to CLI Proxy Management API.
- Added Codex account cards with account status, 5-hour quota, weekly quota, image generation estimates, reset time, and last refresh time.
- Added quota conversion rules:
  - 5-hour quota: `1% = 1 image`
  - Weekly quota: `1% = 8 images`
- Added system overview cards for OS version, CPU usage, memory usage, and disk usage.
- Added a collapsible ChatGPT-style sidebar with ImageAI branding.
- Added Element Plus based UI states for loading, errors, empty results, refresh actions, tags, cards, and progress bars.
- Added generated ImageAI app icon asset.
- Added Vite `/api` proxy for local frontend development.

### Backend

- Added `GET /api/codex/quota/accounts`.
- Added `GET /api/system/overview`.
- Added CLI Proxy configuration binding through `image-ai.cli-proxy`.
- Kept CLI Proxy management credentials out of frontend code.

### Frontend

- Added Vue 3 + TypeScript + Element Plus dashboard in `vue3/`.
- Added service modules for Codex quota and system overview APIs.
- Added responsive account grid with compact cards and thin scrollbars.
- Added static business-style progress bars without distracting animation.

### Environment

- JDK 17
- Spring Boot 3.5.14
- Vue 3
- Vite 5
- TypeScript
- Element Plus
- Node.js 20.11.1
- MySQL 8
- Redis default port

### Notes

- Restart the Spring Boot backend after pulling this release so the new system overview endpoint is available.
- If Maven reports `unsupported release version 17`, switch `JAVA_HOME` to JDK 17.
- The frontend does not use mock data by default.
