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
  error?: string | null;
}

export interface DashboardStats {
  totalAccounts: number;
  activeAccounts: number;
  fiveHourImages: number;
  weeklyImages: number;
  averageFiveHourPercent: number | null;
  averageWeeklyPercent: number | null;
}

export interface SystemOverview {
  appName: string;
  appVersion: string;
  osFamily: string;
  systemVersion: string;
  cpuUsagePercent: number;
  memoryTotalBytes: number;
  memoryUsedBytes: number;
  memoryUsagePercent: number;
  diskTotalBytes: number;
  diskUsedBytes: number;
  diskUsagePercent: number;
}

export interface DefaultPromptSettings {
  mainPrompt: string;
  introPrompt: string;
}

export interface UploadImageAnalysis {
  type: string;
  model: string;
  result: string;
}
