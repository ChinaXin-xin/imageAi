/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CLI_PROXY_BASE_URL?: string;
  readonly VITE_CLI_PROXY_MGMT_KEY?: string;
  readonly VITE_USE_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
