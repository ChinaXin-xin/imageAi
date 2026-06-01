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
  analysisPrompt: string;
  targetTemplatePrompt: string;
  customSellingPoints: string[];
}

export interface UploadImageAnalysis {
  type: string;
  model: string;
  result: string;
}

export type TargetTemplateType = 'MAIN' | 'INTRO';

export interface TargetTemplate {
  id: number;
  templateType: TargetTemplateType;
  templateTypeText: string;
  name: string;
  fileName: string;
  contentType: string;
  fileSize: number;
  preview: string;
  styleAnalysis: string;
  model: string;
  createdAt: string;
  updatedAt: string;
}

export interface ImageTaskKitSpec {
  name: string;
  quantity: number;
}

export interface ImageTaskPayload {
  productName: string;
  model: string;
  platform: string;
  ratio: string;
  customWidth: number;
  customHeight: number;
  phoneColor: string;
  customColor: string;
  logoName: string;
  wallpaperName: string;
  style: string;
  layout: string;
  sellingPoints: string[];
  hdEnabled: boolean;
  privacyEnabled: boolean;
  hdQuantity: number;
  privacyQuantity: number;
  mainImageCount: number;
  introImageCount: number;
  language: string;
  mainPrompt: string;
  introPrompt: string;
  mainTargetTemplateId?: number | null;
  introTargetTemplateId?: number | null;
  kitSpecs: ImageTaskKitSpec[];
}

export interface ImageTaskResult {
  id: number;
  resultType: string;
  itemIndex: number;
  status: string;
  statusText: string;
  prompt: string;
  imageUrl?: string | null;
  imageBase64?: string | null;
  revisedPrompt?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ImageTaskFileView {
  id: number;
  group: string;
  groupName: string;
  fileName: string;
  contentType: string;
  fileSize: number;
  preview: string;
}

export interface ImageTaskSummary {
  id: string;
  productName: string;
  status: string;
  statusText: string;
  createdAt: string;
  updatedAt: string;
  thumbnail: string;
  thumbnailName: string;
  fileSummary: Record<string, number>;
  form: ImageTaskPayload;
  completedCount: number;
  totalCount: number;
  errorMessage?: string | null;
}

export interface ImageTaskDetail extends ImageTaskSummary {
  startedAt: string;
  completedAt: string;
  kitSpecs: ImageTaskKitSpec[];
  files: Record<string, ImageTaskFileView[]>;
  analysis: Record<string, string>;
  finalMainPrompt?: string | null;
  finalIntroPrompt?: string | null;
  results: ImageTaskResult[];
}
