export interface AuthFileItem {
  name: string;
  type?: string;
  provider?: string;
  disabled?: boolean | string | number;
  unavailable?: boolean | string | number;
  status?: string;
  statusMessage?: string;
  authIndex?: string | number | null;
  runtimeOnly?: boolean | string;
  lastRefresh?: string | number;
  modified?: string | number;
  [key: string]: unknown;
}

export interface AuthFilesResponse {
  files?: AuthFileItem[];
  total?: number;
}

export interface ApiCallResult {
  statusCode: number;
  bodyText: string;
  body: unknown | null;
}

export interface CodexUsageWindow {
  used_percent?: number | string | null;
  usedPercent?: number | string | null;
  limit_window_seconds?: number | string | null;
  limitWindowSeconds?: number | string | null;
  reset_after_seconds?: number | string | null;
  resetAfterSeconds?: number | string | null;
  reset_at?: string | number | null;
  resetAt?: string | number | null;
}

export interface CodexRateLimitInfo {
  primary_window?: CodexUsageWindow | null;
  primaryWindow?: CodexUsageWindow | null;
  secondary_window?: CodexUsageWindow | null;
  secondaryWindow?: CodexUsageWindow | null;
  limit_reached?: boolean;
  limitReached?: boolean;
  allowed?: boolean;
}

export interface CodexUsagePayload {
  plan_type?: string;
  planType?: string;
  rate_limit?: CodexRateLimitInfo;
  rateLimit?: CodexRateLimitInfo;
}

export interface QuotaWindowView {
  remainingPercent: number | null;
  usedPercent: number | null;
  resetLabel: string;
}

export interface CodexQuotaAccount {
  id: string;
  name: string;
  fileName: string;
  status: 'active' | 'disabled' | 'unavailable' | 'error';
  statusText: string;
  planType: string | null;
  fiveHour: QuotaWindowView;
  weekly: QuotaWindowView;
  fiveHourImages: number | null;
  weeklyImages: number | null;
  lastRefreshTime: string;
  error?: string;
}

export interface DashboardStats {
  totalAccounts: number;
  activeAccounts: number;
  fiveHourImages: number;
  weeklyImages: number;
  averageFiveHourPercent: number | null;
  averageWeeklyPercent: number | null;
}
