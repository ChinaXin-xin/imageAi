import type {
  ApiCallResult,
  AuthFileItem,
  AuthFilesResponse,
  CodexQuotaAccount,
  CodexRateLimitInfo,
  CodexUsagePayload,
  CodexUsageWindow,
  QuotaWindowView,
} from '../types/quota';

const MANAGEMENT_SUFFIX = '/v0/management';
const CODEX_USAGE_URL = 'https://chatgpt.com/backend-api/wham/usage';
const FIVE_HOUR_SECONDS = 18_000;
const WEEK_SECONDS = 604_800;

const CODEX_REQUEST_HEADERS = {
  Authorization: 'Bearer $TOKEN$',
  'Content-Type': 'application/json',
  'User-Agent': 'codex_cli_rs/0.76.0 (Debian 13.0.0; x86_64) WindowsTerminal',
};

function normalizeManagementBaseUrl(rawUrl: string): string {
  const trimmed = rawUrl.trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  const withProtocol = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  return withProtocol.endsWith(MANAGEMENT_SUFFIX)
    ? withProtocol
    : `${withProtocol}${MANAGEMENT_SUFFIX}`;
}

function readEnv() {
  return {
    baseUrl: normalizeManagementBaseUrl(import.meta.env.VITE_CLI_PROXY_BASE_URL ?? ''),
    managementKey: (import.meta.env.VITE_CLI_PROXY_MGMT_KEY ?? '').trim(),
    useMock: (import.meta.env.VITE_USE_MOCK ?? '').trim().toLowerCase() === 'true',
  };
}

function assertConfigured() {
  const env = readEnv();
  if (!env.baseUrl || !env.managementKey) {
    throw new Error('请在 vue3/.env.local 配置 VITE_CLI_PROXY_BASE_URL 和 VITE_CLI_PROXY_MGMT_KEY。');
  }
  return env;
}

async function managementFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const env = assertConfigured();
  const response = await fetch(`${env.baseUrl}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${env.managementKey}`,
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`${response.status} ${text || response.statusText}`);
  }

  return (await response.json()) as T;
}

function normalizeBody(input: unknown): { bodyText: string; body: unknown | null } {
  if (input === undefined || input === null) return { bodyText: '', body: null };
  if (typeof input !== 'string') {
    return {
      bodyText: JSON.stringify(input),
      body: input,
    };
  }

  const text = input;
  const trimmed = text.trim();
  if (!trimmed) return { bodyText: text, body: null };
  try {
    return { bodyText: text, body: JSON.parse(trimmed) as unknown };
  } catch {
    return { bodyText: text, body: text };
  }
}

async function apiCall(payload: Record<string, unknown>): Promise<ApiCallResult> {
  const response = await managementFetch<Record<string, unknown>>('/api-call', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
  const { bodyText, body } = normalizeBody(response.body);
  return {
    statusCode: Number(response.status_code ?? response.statusCode ?? 0),
    bodyText,
    body,
  };
}

function normalizeProvider(file: AuthFileItem): string {
  return String(file.provider ?? file.type ?? '')
    .trim()
    .toLowerCase()
    .replace(/_/g, '-');
}

function isCodexFile(file: AuthFileItem): boolean {
  return normalizeProvider(file) === 'codex';
}

function normalizeBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  if (typeof value === 'string') return value.trim().toLowerCase() === 'true';
  return false;
}

function normalizeNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string') {
    const parsed = Number(value.trim());
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, value));
}

function readAuthIndex(file: AuthFileItem): string | null {
  const raw = file.authIndex ?? file.auth_index;
  if (raw === undefined || raw === null) return null;
  const text = String(raw).trim();
  return text ? text : null;
}

function parseJwtPayload(value: unknown): Record<string, unknown> | null {
  if (typeof value !== 'string') return null;
  const tokenPart = value.split('.')[1];
  if (!tokenPart) return null;
  try {
    const base64 = tokenPart.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
    return JSON.parse(atob(padded)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function readObject(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function readString(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function resolveAccountId(file: AuthFileItem): string | null {
  const metadata = readObject(file.metadata);
  const attributes = readObject(file.attributes);
  const candidates = [file.id_token, metadata?.id_token, attributes?.id_token];

  for (const candidate of candidates) {
    const payload = parseJwtPayload(candidate);
    const id = readString(payload?.chatgpt_account_id ?? payload?.chatgptAccountId);
    if (id) return id;
  }

  return null;
}

function resolvePlanType(file: AuthFileItem, payload?: CodexUsagePayload): string | null {
  const metadata = readObject(file.metadata);
  const attributes = readObject(file.attributes);
  const idToken = readObject(file.id_token);
  const metadataIdToken = readObject(metadata?.id_token);
  const values = [
    payload?.plan_type,
    payload?.planType,
    file.plan_type,
    file.planType,
    idToken?.plan_type,
    idToken?.planType,
    metadata?.plan_type,
    metadata?.planType,
    metadataIdToken?.plan_type,
    metadataIdToken?.planType,
    attributes?.plan_type,
    attributes?.planType,
  ];

  for (const value of values) {
    const text = readString(value);
    if (text) return text;
  }
  return null;
}

function resolveAccountName(file: AuthFileItem): string {
  const metadata = readObject(file.metadata);
  const attributes = readObject(file.attributes);
  const candidates = [
    file.account,
    file.email,
    file.username,
    metadata?.account,
    metadata?.email,
    attributes?.account,
    attributes?.email,
  ];

  for (const candidate of candidates) {
    const text = readString(candidate);
    if (text) return text;
  }

  return file.name;
}

function getWindowSeconds(window?: CodexUsageWindow | null): number | null {
  if (!window) return null;
  return normalizeNumber(window.limit_window_seconds ?? window.limitWindowSeconds);
}

function pickQuotaWindows(rateLimit?: CodexRateLimitInfo): {
  fiveHourWindow: CodexUsageWindow | null;
  weeklyWindow: CodexUsageWindow | null;
  limitReached: boolean;
  allowed: boolean | null;
} {
  const primary = rateLimit?.primary_window ?? rateLimit?.primaryWindow ?? null;
  const secondary = rateLimit?.secondary_window ?? rateLimit?.secondaryWindow ?? null;
  let fiveHourWindow: CodexUsageWindow | null = null;
  let weeklyWindow: CodexUsageWindow | null = null;

  for (const window of [primary, secondary]) {
    const seconds = getWindowSeconds(window);
    if (seconds === FIVE_HOUR_SECONDS && !fiveHourWindow) fiveHourWindow = window;
    if (seconds === WEEK_SECONDS && !weeklyWindow) weeklyWindow = window;
  }

  if (!fiveHourWindow) fiveHourWindow = primary && primary !== weeklyWindow ? primary : null;
  if (!weeklyWindow) weeklyWindow = secondary && secondary !== fiveHourWindow ? secondary : null;

  return {
    fiveHourWindow,
    weeklyWindow,
    limitReached: Boolean(rateLimit?.limit_reached ?? rateLimit?.limitReached),
    allowed: typeof rateLimit?.allowed === 'boolean' ? rateLimit.allowed : null,
  };
}

function formatResetLabel(window?: CodexUsageWindow | null): string {
  if (!window) return '-';
  const resetAt = window.reset_at ?? window.resetAt;
  const resetAfterSeconds = normalizeNumber(window.reset_after_seconds ?? window.resetAfterSeconds);

  if (typeof resetAt === 'string' && resetAt.trim()) {
    return new Date(resetAt).toLocaleString();
  }
  if (typeof resetAt === 'number' && Number.isFinite(resetAt)) {
    const timestamp = resetAt < 1e12 ? resetAt * 1000 : resetAt;
    return new Date(timestamp).toLocaleString();
  }
  if (resetAfterSeconds !== null) {
    const target = Date.now() + resetAfterSeconds * 1000;
    return new Date(target).toLocaleString();
  }
  return '-';
}

function buildWindowView(
  window: CodexUsageWindow | null,
  limitReached: boolean,
  allowed: boolean | null,
): QuotaWindowView {
  if (!window) {
    return { remainingPercent: null, usedPercent: null, resetLabel: '-' };
  }

  const rawUsed = normalizeNumber(window.used_percent ?? window.usedPercent);
  const usedPercent = rawUsed ?? (limitReached || allowed === false ? 100 : null);
  const remainingPercent = usedPercent === null ? null : clampPercent(100 - usedPercent);
  return {
    usedPercent: usedPercent === null ? null : clampPercent(usedPercent),
    remainingPercent,
    resetLabel: formatResetLabel(window),
  };
}

function imageCount(percent: number | null, ratio: number): number | null {
  return percent === null ? null : Math.floor(percent * ratio);
}

function nowLabel(): string {
  return new Date().toLocaleString();
}

async function fetchCodexQuota(file: AuthFileItem): Promise<CodexQuotaAccount> {
  const authIndex = readAuthIndex(file);
  if (!authIndex) throw new Error('凭据缺少 auth_index，无法通过管理 API 代理查询额度。');

  const accountId = resolveAccountId(file);
  const headers: Record<string, string> = { ...CODEX_REQUEST_HEADERS };
  if (accountId) headers['Chatgpt-Account-Id'] = accountId;

  const result = await apiCall({
    authIndex,
    method: 'GET',
    url: CODEX_USAGE_URL,
    header: headers,
  });

  if (result.statusCode < 200 || result.statusCode >= 300) {
    const message =
      typeof result.body === 'object' && result.body !== null && 'message' in result.body
        ? String((result.body as { message?: unknown }).message)
        : result.bodyText || `HTTP ${result.statusCode}`;
    throw new Error(message);
  }

  const payload = result.body as CodexUsagePayload | null;
  if (!payload || typeof payload !== 'object') throw new Error('Codex usage 响应为空。');

  const rateLimit = payload.rate_limit ?? payload.rateLimit;
  const { fiveHourWindow, weeklyWindow, limitReached, allowed } = pickQuotaWindows(rateLimit);
  const fiveHour = buildWindowView(fiveHourWindow, limitReached, allowed);
  const weekly = buildWindowView(weeklyWindow, limitReached, allowed);

  return {
    id: file.name,
    name: resolveAccountName(file),
    fileName: file.name,
    status: normalizeBoolean(file.disabled)
      ? 'disabled'
      : normalizeBoolean(file.unavailable)
        ? 'unavailable'
        : 'active',
    statusText: normalizeBoolean(file.disabled)
      ? '已禁用'
      : normalizeBoolean(file.unavailable)
        ? '不可用'
        : '正常',
    planType: resolvePlanType(file, payload),
    fiveHour,
    weekly,
    fiveHourImages: imageCount(fiveHour.remainingPercent, 1),
    weeklyImages: imageCount(weekly.remainingPercent, 8),
    lastRefreshTime: nowLabel(),
  };
}

function buildErrorAccount(file: AuthFileItem, error: unknown): CodexQuotaAccount {
  return {
    id: file.name,
    name: resolveAccountName(file),
    fileName: file.name,
    status: 'error',
    statusText: '查询失败',
    planType: resolvePlanType(file),
    fiveHour: { remainingPercent: null, usedPercent: null, resetLabel: '-' },
    weekly: { remainingPercent: null, usedPercent: null, resetLabel: '-' },
    fiveHourImages: null,
    weeklyImages: null,
    lastRefreshTime: nowLabel(),
    error: error instanceof Error ? error.message : String(error),
  };
}

export async function loadCodexQuotaAccounts(): Promise<CodexQuotaAccount[]> {
  if (readEnv().useMock) return mockCodexQuotaAccounts();

  const payload = await managementFetch<AuthFilesResponse>('/auth-files');
  const files = Array.isArray(payload.files) ? payload.files.filter(isCodexFile) : [];
  const accounts = await Promise.all(
    files.map(async (file) => {
      try {
        return await fetchCodexQuota(file);
      } catch (error) {
        return buildErrorAccount(file, error);
      }
    }),
  );
  return accounts.sort((left, right) => left.fileName.localeCompare(right.fileName));
}

export function mockCodexQuotaAccounts(): CodexQuotaAccount[] {
  return [
    {
      id: 'mock-codex-plus.json',
      name: 'codex-plus@example.com',
      fileName: 'mock-codex-plus.json',
      status: 'active',
      statusText: '正常',
      planType: 'plus',
      fiveHour: { remainingPercent: 62, usedPercent: 38, resetLabel: '约 2 小时后' },
      weekly: { remainingPercent: 54, usedPercent: 46, resetLabel: '周一 08:00' },
      fiveHourImages: 62,
      weeklyImages: 432,
      lastRefreshTime: nowLabel(),
    },
    {
      id: 'mock-codex-team.json',
      name: 'codex-team@example.com',
      fileName: 'mock-codex-team.json',
      status: 'active',
      statusText: '正常',
      planType: 'team',
      fiveHour: { remainingPercent: 18, usedPercent: 82, resetLabel: '约 38 分钟后' },
      weekly: { remainingPercent: 76, usedPercent: 24, resetLabel: '周日 23:30' },
      fiveHourImages: 18,
      weeklyImages: 608,
      lastRefreshTime: nowLabel(),
    },
  ];
}
