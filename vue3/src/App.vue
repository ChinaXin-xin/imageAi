<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  CircleCheck,
  Close,
  Document,
  Download,
  Expand,
  Fold,
  Setting,
  Monitor,
  Plus,
  Refresh,
  RefreshRight,
  Upload,
} from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { UploadUserFile } from 'element-plus';
import appIcon from './assets/imageai-icon.png';
import { loadCodexQuotaAccounts } from './services/codexQuotaApi';
import { loadSystemOverview } from './services/systemApi';
import {
  analyzeUploadedImages,
  createImageTask,
  loadImageTask,
  loadImageTasks,
  loadDefaultPromptSettings,
  retryImageTask,
  saveDefaultPromptSettings,
} from './services/taskApi';
import type {
  CodexQuotaAccount,
  DashboardStats,
  DefaultPromptSettings,
  ImageTaskDetail,
  ImageTaskPayload,
  ImageTaskSummary,
  SystemOverview,
  UploadImageAnalysis,
} from './types/quota';

const DEFAULT_MAIN_PROMPT =
  '生成高转化电商主图：突出手机膜产品质感、包装完整度和平台风格，画面干净高级，主体清晰，适合跨境电商首图。';
const DEFAULT_INTRO_PROMPT =
  '生成产品介绍图：围绕高清、防指纹、抗摔、防窥、易安装、镜头保护等卖点进行模块化展示，信息层级清晰，适合详情页。';
const DEFAULT_ANALYSIS_PROMPT =
  '请分析上传图片中的产品类型、材质、包装、颜色、机型线索、可用于主图和介绍图的卖点，不要编造看不见的信息。';
const DEFAULT_SELLING_POINTS = ['高清透亮', '9H硬度', '防指纹', '全屏覆盖', '易安装', '镜头保护'];
const TASK_DRAFT_CACHE_KEY = 'imageai:add-task-draft:v1';
const DEFAULT_IMAGE_SIZE = 1536;
const IMAGE_SIZE_STEP = 16;

type UploadGroup = '实拍图' | '包装图' | '模板图' | 'Logo图' | '壁纸图';
type AnalysisUploadGroup = '实拍图' | '包装图';
type ActivePage = 'quota' | 'task' | 'queue' | 'settings';

type ViewerImage = {
  src: string;
  name: string;
};

const accounts = ref<CodexQuotaAccount[]>([]);
const loading = ref(false);
const errorMessage = ref('');
const systemErrorMessage = ref('');
const lastRefreshAt = ref('');
const isCollapsed = ref(false);
const activePage = ref<ActivePage>('quota');
const systemOverview = ref<SystemOverview | null>(null);
const defaultSettings = ref<DefaultPromptSettings>({
  mainPrompt: DEFAULT_MAIN_PROMPT,
  introPrompt: DEFAULT_INTRO_PROMPT,
  analysisPrompt: DEFAULT_ANALYSIS_PROMPT,
  customSellingPoints: DEFAULT_SELLING_POINTS,
});
const settingsLoading = ref(false);
const settingsSaving = ref(false);
const taskDraftRestored = ref(false);
const realPhotoFiles = ref<UploadUserFile[]>([]);
const packageImageFiles = ref<UploadUserFile[]>([]);
const templateFiles = ref<UploadUserFile[]>([]);
const logoFiles = ref<UploadUserFile[]>([]);
const wallpaperFiles = ref<UploadUserFile[]>([]);
const uploadAnalysis = ref<Record<AnalysisUploadGroup, UploadImageAnalysis | null>>({
  实拍图: null,
  包装图: null,
});
const analysisLoading = ref<Record<AnalysisUploadGroup, boolean>>({
  实拍图: false,
  包装图: false,
});
const previewUrls = ref<Record<string, string>>({});
const newDefaultSellingPoint = ref('');
const taskQueue = ref<ImageTaskSummary[]>([]);
const selectedQueueTask = ref<ImageTaskDetail | null>(null);
const queueDetailVisible = ref(false);
const fullTextDialogVisible = ref(false);
const fullTextDialogTitle = ref('');
const fullTextDialogContent = ref('');
const queueLoading = ref(false);
const queueErrorMessage = ref('');
const addingTask = ref(false);
const imageViewerVisible = ref(false);
const imageViewerImage = ref<ViewerImage | null>(null);
const imageViewerRotation = ref(0);
let queueRefreshTimer: number | undefined;

const platforms = ['Amazon', 'TEMU', 'TikTok Shop', '自定义'];
const ratioOptions = ['1536:1536', '1024:1024', '960:640', '自定义'];
const phoneColors = ['自动', '黑色', '白色', '银色', '金色', '蓝色', '紫色', '绿色', '自定义'];
const styleOptions = ['自动', '科技感', '极简风', '苹果风', '3D立体', '高级电商', 'TEMU爆款'];
const layoutOptions = ['自动', '居中展示', '左图右文', '右图左文', '产品矩阵', '场景渲染'];
const languageOptions = ['中文', '英文', '中英双语'];
const uploadGroups: UploadGroup[] = ['实拍图', '包装图', '模板图', 'Logo图', '壁纸图'];

const taskForm = ref({
  productName: '',
  model: '',
  platform: 'Amazon',
  ratio: '1536:1536',
  customWidth: DEFAULT_IMAGE_SIZE,
  customHeight: DEFAULT_IMAGE_SIZE,
  phoneColor: '自动',
  customColor: '#2563eb',
  logoName: '',
  wallpaperName: '',
  style: '自动',
  layout: '自动',
  sellingPoints: ['高清透亮', '9H硬度', '防指纹', '全屏覆盖', '易安装', '镜头保护'],
  newSellingPoint: '',
  hdEnabled: true,
  privacyEnabled: false,
  hdQuantity: 5,
  privacyQuantity: 0,
  mainImageCount: 7,
  introImageCount: 10,
  language: '英文',
  mainPrompt: '',
  introPrompt: '',
});

const kitSpecs = ref([
  { name: '钢化膜', quantity: 0 },
  { name: '镜头膜', quantity: 0 },
  { name: '清洁包', quantity: 0 },
  { name: '除螨贴', quantity: 0 },
  { name: '挂卡', quantity: 0 },
  { name: '固定器', quantity: 0 },
]);

type TaskDraftCache = {
  taskForm?: Partial<typeof taskForm.value>;
  kitSpecs?: Array<{ name: string; quantity: number }>;
  activePage?: ActivePage;
  isCollapsed?: boolean;
  savedAt?: string;
};

const sellingPointOptions = computed(() => {
  const merged = [...defaultSettings.value.customSellingPoints, ...taskForm.value.sellingPoints]
    .filter((point) => point && point.trim())
    .map((point) => point.trim());
  return Array.from(new Set(merged));
});

const stats = computed<DashboardStats>(() => {
  const fiveHourValues = accounts.value
    .map((account) => account.fiveHour.remainingPercent)
    .filter((value): value is number => typeof value === 'number');
  const weeklyValues = accounts.value
    .map((account) => account.weekly.remainingPercent)
    .filter((value): value is number => typeof value === 'number');

  return {
    totalAccounts: accounts.value.length,
    activeAccounts: accounts.value.filter((account) => account.status === 'active').length,
    fiveHourImages: accounts.value.reduce((sum, account) => sum + (account.fiveHourImages ?? 0), 0),
    weeklyImages: accounts.value.reduce((sum, account) => sum + (account.weeklyImages ?? 0), 0),
    averageFiveHourPercent: average(fiveHourValues),
    averageWeeklyPercent: average(weeklyValues),
  };
});

function average(values: number[]): number | null {
  if (values.length === 0) return null;
  return Math.round(values.reduce((sum, value) => sum + value, 0) / values.length);
}

function formatPercent(value: number | null): string {
  return typeof value === 'number' ? `${Math.round(value)}%` : '--';
}

function formatNumber(value: number | null): string {
  return typeof value === 'number' ? value.toLocaleString() : '--';
}

function progressColor(value: number | null): string {
  if (value === null) return '#cbd5e1';
  if (value >= 70) return '#2563eb';
  if (value >= 30) return '#d97706';
  return '#dc2626';
}

function statusTagType(status: CodexQuotaAccount['status']): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'active') return 'success';
  if (status === 'disabled') return 'info';
  if (status === 'unavailable') return 'warning';
  return 'danger';
}

async function refreshQuota() {
  loading.value = true;
  errorMessage.value = '';
  systemErrorMessage.value = '';

  try {
    const [quotaResult, systemResult] = await Promise.allSettled([
      loadCodexQuotaAccounts(),
      loadSystemOverview(),
    ]);

    if (systemResult.status === 'fulfilled') {
      systemOverview.value = systemResult.value;
    } else {
      systemErrorMessage.value =
        systemResult.reason instanceof Error ? systemResult.reason.message : String(systemResult.reason);
    }

    if (quotaResult.status === 'fulfilled') {
      accounts.value = quotaResult.value;
    } else {
      throw quotaResult.reason;
    }

    lastRefreshAt.value = new Date().toLocaleString();
    if (accounts.value.length === 0) {
      ElMessage.warning('未发现 ImageAI 凭据文件。');
    } else {
      ElMessage.success('额度数据已刷新。');
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function formatBytes(value: number | null | undefined): string {
  if (!value || value <= 0) return '--';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return `${size >= 10 ? size.toFixed(0) : size.toFixed(1)} ${units[index]}`;
}

function formatMetricPercent(value: number | null | undefined): string {
  return typeof value === 'number' ? `${value.toFixed(value % 1 === 0 ? 0 : 1)}%` : '--';
}

function systemFallbackText(): string {
  return systemErrorMessage.value ? '接口异常' : '等待后端返回';
}

onMounted(() => {
  restoreTaskDraft();
  refreshQuota();
  loadPromptSettings();
  loadTaskQueue(false);
  window.addEventListener('keydown', handleImageViewerKeydown);
  queueRefreshTimer = window.setInterval(() => {
    if (activePage.value === 'queue' || taskQueue.value.some((task) => isRunningTask(task.status))) {
      loadTaskQueue(false);
    }
  }, 3000);
});

onUnmounted(() => {
  if (queueRefreshTimer) {
    window.clearInterval(queueRefreshTimer);
  }
  window.removeEventListener('keydown', handleImageViewerKeydown);
});

watch(
  [taskForm, kitSpecs, activePage, isCollapsed],
  () => {
    saveTaskDraft();
  },
  { deep: true },
);

function restoreTaskDraft() {
  try {
    const rawDraft = window.localStorage.getItem(TASK_DRAFT_CACHE_KEY);
    if (!rawDraft) return;
    const draft = JSON.parse(rawDraft) as TaskDraftCache;
    if (draft.taskForm) {
      taskForm.value = {
        ...taskForm.value,
        ...draft.taskForm,
      };
      normalizeTaskImageSize();
    }
    if (draft.kitSpecs?.length) {
      const cachedSpecs = new Map(draft.kitSpecs.map((item) => [item.name, item.quantity]));
      kitSpecs.value = kitSpecs.value.map((item) => ({
        ...item,
        quantity: Math.max(0, Number(cachedSpecs.get(item.name) ?? item.quantity)),
      }));
    }
    if (draft.activePage && isActivePage(draft.activePage)) {
      activePage.value = draft.activePage;
    }
    if (typeof draft.isCollapsed === 'boolean') {
      isCollapsed.value = draft.isCollapsed;
    }
    taskDraftRestored.value = true;
  } catch (error) {
    console.warn('恢复添加任务草稿失败', error);
    window.localStorage.removeItem(TASK_DRAFT_CACHE_KEY);
  }
}

function saveTaskDraft() {
  try {
    const draft: TaskDraftCache = {
      taskForm: { ...taskForm.value },
      kitSpecs: kitSpecs.value.map((item) => ({ ...item })),
      activePage: activePage.value,
      isCollapsed: isCollapsed.value,
      savedAt: new Date().toISOString(),
    };
    window.localStorage.setItem(TASK_DRAFT_CACHE_KEY, JSON.stringify(draft));
  } catch (error) {
    console.warn('保存添加任务草稿失败', error);
  }
}

function isActivePage(value: string): value is ActivePage {
  return ['quota', 'task', 'queue', 'settings'].includes(value);
}

function normalizeImageDimension(value: number | null | undefined, fallback = DEFAULT_IMAGE_SIZE): number {
  const numericValue = Number(value);
  if (!Number.isFinite(numericValue) || numericValue < 300) {
    return fallback;
  }
  return Math.max(304, Math.round(numericValue / IMAGE_SIZE_STEP) * IMAGE_SIZE_STEP);
}

function normalizeLegacyRatio(ratio: string): string {
  if (ratio === '1500:1500') return '1536:1536';
  if (ratio === '1000:1000') return '1024:1024';
  if (ratio === '900:600') return '960:640';
  return ratio;
}

function normalizeTaskImageSize() {
  taskForm.value.ratio = normalizeLegacyRatio(taskForm.value.ratio);
  if (taskForm.value.ratio !== '自定义') {
    const [width, height] = taskForm.value.ratio.split(':').map((value) => Number(value));
    if (Number.isFinite(width) && Number.isFinite(height)) {
      taskForm.value.customWidth = width;
      taskForm.value.customHeight = height;
      return;
    }
  }
  taskForm.value.customWidth = normalizeImageDimension(taskForm.value.customWidth);
  taskForm.value.customHeight = normalizeImageDimension(taskForm.value.customHeight);
}

function randomProductName(): string {
  return Math.random().toString(36).slice(2, 10).toUpperCase();
}

function ensureProductName() {
  if (!taskForm.value.productName.trim()) {
    taskForm.value.productName = randomProductName();
  }
}

function selectPlatform(platform: string) {
  taskForm.value.platform = platform;
  if (platform === 'Amazon') {
    selectRatio('1536:1536');
  } else if (platform === 'TEMU') {
    selectRatio('1024:1024');
  } else if (platform === 'TikTok Shop') {
    selectRatio('960:640');
  }
}

function selectRatio(ratio: string) {
  taskForm.value.ratio = ratio;
  const [width, height] = ratio.split(':').map((value) => Number(value));
  if (Number.isFinite(width) && Number.isFinite(height)) {
    taskForm.value.customWidth = width;
    taskForm.value.customHeight = height;
  }
}

function updateQuantity(index: number, delta: number) {
  kitSpecs.value[index].quantity = Math.max(0, kitSpecs.value[index].quantity + delta);
}

async function loadPromptSettings() {
  settingsLoading.value = true;
  try {
    const settings = await loadDefaultPromptSettings();
    const normalizedSettings = {
      ...settings,
      mainPrompt: settings.mainPrompt?.trim() || DEFAULT_MAIN_PROMPT,
      introPrompt: settings.introPrompt?.trim() || DEFAULT_INTRO_PROMPT,
      analysisPrompt: settings.analysisPrompt?.trim() || DEFAULT_ANALYSIS_PROMPT,
      customSellingPoints: settings.customSellingPoints?.length ? settings.customSellingPoints : DEFAULT_SELLING_POINTS,
    };
    defaultSettings.value = normalizedSettings;
    if (!taskForm.value.mainPrompt.trim()) {
      taskForm.value.mainPrompt = normalizedSettings.mainPrompt;
    }
    if (!taskForm.value.introPrompt.trim()) {
      taskForm.value.introPrompt = normalizedSettings.introPrompt;
    }
    if (!taskDraftRestored.value && normalizedSettings.customSellingPoints.length > 0) {
      taskForm.value.sellingPoints = [...normalizedSettings.customSellingPoints];
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    settingsLoading.value = false;
  }
}

async function savePromptSettings() {
  settingsSaving.value = true;
  try {
    const settings = await saveDefaultPromptSettings(defaultSettings.value);
    defaultSettings.value = {
      ...settings,
      mainPrompt: settings.mainPrompt?.trim() || DEFAULT_MAIN_PROMPT,
      introPrompt: settings.introPrompt?.trim() || DEFAULT_INTRO_PROMPT,
      analysisPrompt: settings.analysisPrompt?.trim() || DEFAULT_ANALYSIS_PROMPT,
      customSellingPoints: settings.customSellingPoints?.length ? settings.customSellingPoints : DEFAULT_SELLING_POINTS,
    };
    taskForm.value.mainPrompt = defaultSettings.value.mainPrompt;
    taskForm.value.introPrompt = defaultSettings.value.introPrompt;
    taskForm.value.sellingPoints = [...defaultSettings.value.customSellingPoints];
    ElMessage.success('默认提示词已保存。');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    settingsSaving.value = false;
  }
}

function uploadFilesFor(type: AnalysisUploadGroup): File[] {
  const source = type === '实拍图' ? realPhotoFiles.value : packageImageFiles.value;
  return source.flatMap((file) => (file.raw ? [file.raw as unknown as File] : []));
}

function normalizeCustomImageSize() {
  taskForm.value.ratio = '自定义';
  normalizeTaskImageSize();
}

async function analyzeUploadImage(type: AnalysisUploadGroup) {
  const files = uploadFilesFor(type);
  if (files.length === 0) {
    ElMessage.warning(`请先上传${type}。`);
    return;
  }
  uploadAnalysis.value[type] = null;
  analysisLoading.value[type] = true;
  try {
    uploadAnalysis.value[type] = await analyzeUploadedImages(type, files, defaultSettings.value.analysisPrompt);
    ElMessage.success(`${type}深析完成。`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    analysisLoading.value[type] = false;
  }
}

function resetUploadAnalysis(type: AnalysisUploadGroup) {
  uploadAnalysis.value[type] = null;
  analysisLoading.value[type] = false;
}

function uploadKey(file: UploadUserFile): string {
  return String(file.uid ?? file.name);
}

function uploadListFor(type: UploadGroup) {
  if (type === '实拍图') return realPhotoFiles;
  if (type === '包装图') return packageImageFiles;
  if (type === '模板图') return templateFiles;
  if (type === 'Logo图') return logoFiles;
  return wallpaperFiles;
}

function filePreviewUrl(file: UploadUserFile): string {
  if (file.url) return file.url;
  const key = uploadKey(file);
  if (!previewUrls.value[key] && file.raw) {
    previewUrls.value[key] = URL.createObjectURL(file.raw as unknown as Blob);
  }
  return previewUrls.value[key] ?? '';
}

function handleUploadRemove(file: UploadUserFile) {
  const key = uploadKey(file);
  const url = previewUrls.value[key];
  if (url) {
    URL.revokeObjectURL(url);
    delete previewUrls.value[key];
  }
}

function removeUploadFile(type: UploadGroup, file: UploadUserFile) {
  const source = uploadListFor(type);
  source.value = source.value.filter((item) => uploadKey(item) !== uploadKey(file));
  handleUploadRemove(file);
  if (type === '实拍图' && source.value.length === 0) {
    resetUploadAnalysis('实拍图');
  }
  if (type === '包装图' && source.value.length === 0) {
    resetUploadAnalysis('包装图');
  }
  if (realPhotoFiles.value.length + packageImageFiles.value.length + templateFiles.value.length === 0) {
    resetUploadAnalysis('实拍图');
    resetUploadAnalysis('包装图');
  }
}

function addDefaultSellingPoint() {
  const point = newDefaultSellingPoint.value.trim();
  if (!point) return;
  if (!defaultSettings.value.customSellingPoints.includes(point)) {
    defaultSettings.value.customSellingPoints.push(point);
  }
  if (!taskForm.value.sellingPoints.includes(point)) {
    taskForm.value.sellingPoints.push(point);
  }
  newDefaultSellingPoint.value = '';
}

function removeDefaultSellingPoint(point: string) {
  defaultSettings.value.customSellingPoints = defaultSettings.value.customSellingPoints.filter((item) => item !== point);
  taskForm.value.sellingPoints = taskForm.value.sellingPoints.filter((item) => item !== point);
}

function clearUploadedImagesAndAnalysis() {
  Object.values(previewUrls.value).forEach((url) => URL.revokeObjectURL(url));
  previewUrls.value = {};
  realPhotoFiles.value = [];
  packageImageFiles.value = [];
  templateFiles.value = [];
  logoFiles.value = [];
  wallpaperFiles.value = [];
  uploadAnalysis.value = {
    实拍图: null,
    包装图: null,
  };
  analysisLoading.value = {
    实拍图: false,
    包装图: false,
  };
}

function autoRecognizeModel() {
  if (!taskForm.value.model.trim()) {
    taskForm.value.model = '自动识别参考图';
  }
  ElMessage.success('已启用机型自动识别。');
}

function autoRecognizeLogo() {
  if (!taskForm.value.logoName.trim()) {
    taskForm.value.logoName = '自动识别品牌';
  }
  ElMessage.success('已启用 Logo 自动识别。');
}

function autoRecognizeKitSpecs() {
  const defaults: Record<string, number> = {
    钢化膜: 1,
    镜头膜: 1,
    清洁包: 1,
    除螨贴: 2,
    挂卡: 1,
    固定器: 0,
  };
  kitSpecs.value = kitSpecs.value.map((item) => ({
    ...item,
    quantity: defaults[item.name] ?? 0,
  }));
  ElMessage.success('已按经典套装规格自动识别。');
}

async function addToTaskQueue() {
  ensureProductName();
  normalizeTaskImageSize();
  addingTask.value = true;
  try {
    const createdTask = await createImageTask(snapshotTaskForm(), {
      realPhotoFiles: rawUploadFiles(realPhotoFiles.value),
      packageImageFiles: rawUploadFiles(packageImageFiles.value),
      templateFiles: rawUploadFiles(templateFiles.value),
      logoFiles: rawUploadFiles(logoFiles.value),
      wallpaperFiles: rawUploadFiles(wallpaperFiles.value),
    });
    selectedQueueTask.value = createdTask;
    activePage.value = 'queue';
    await loadTaskQueue(false);
    ElMessage.success(`任务已提交到后端队列：${createdTask.productName}`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    addingTask.value = false;
  }
}

function snapshotTaskForm(): ImageTaskPayload {
  normalizeTaskImageSize();
  return {
    productName: taskForm.value.productName,
    model: taskForm.value.model,
    platform: taskForm.value.platform,
    ratio: taskForm.value.ratio,
    customWidth: taskForm.value.customWidth,
    customHeight: taskForm.value.customHeight,
    phoneColor: taskForm.value.phoneColor,
    customColor: taskForm.value.customColor,
    logoName: taskForm.value.logoName,
    wallpaperName: taskForm.value.wallpaperName,
    style: taskForm.value.style,
    layout: taskForm.value.layout,
    sellingPoints: [...taskForm.value.sellingPoints],
    hdEnabled: taskForm.value.hdEnabled,
    privacyEnabled: taskForm.value.privacyEnabled,
    hdQuantity: taskForm.value.hdQuantity,
    privacyQuantity: taskForm.value.privacyQuantity,
    mainImageCount: taskForm.value.mainImageCount,
    introImageCount: taskForm.value.introImageCount,
    language: taskForm.value.language,
    mainPrompt: taskForm.value.mainPrompt,
    introPrompt: taskForm.value.introPrompt,
    kitSpecs: kitSpecs.value.map((item) => ({ ...item })),
  };
}

function rawUploadFiles(files: UploadUserFile[]): File[] {
  return files.flatMap((file) => (file.raw ? [file.raw as unknown as File] : []));
}

async function loadTaskQueue(showLoading = true) {
  if (showLoading) {
    queueLoading.value = true;
  }
  queueErrorMessage.value = '';
  try {
    taskQueue.value = await loadImageTasks();
    if (queueDetailVisible.value && selectedQueueTask.value) {
      selectedQueueTask.value = await loadImageTask(selectedQueueTask.value.id);
    }
  } catch (error) {
    queueErrorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    if (showLoading) {
      queueLoading.value = false;
    }
  }
}

function openQueuePage() {
  activePage.value = 'queue';
  loadTaskQueue();
}

async function openQueueDetail(task: ImageTaskSummary) {
  queueDetailVisible.value = true;
  try {
    selectedQueueTask.value = await loadImageTask(task.id);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  }
}

async function retryQueuedTask(task: ImageTaskSummary | ImageTaskDetail) {
  try {
    selectedQueueTask.value = await retryImageTask(task.id);
    await loadTaskQueue(false);
    ElMessage.success('任务已重新加入队列。');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  }
}

function isRunningTask(status: string): boolean {
  return ['QUEUED', 'ANALYZING', 'GENERATING'].includes(status);
}

function taskStatusTagType(status: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'COMPLETED') return 'success';
  if (status === 'FAILED') return 'danger';
  if (status === 'GENERATING' || status === 'ANALYZING') return 'warning';
  return 'info';
}

function taskProgress(task: ImageTaskSummary | ImageTaskDetail): number {
  if (!task.totalCount) return isRunningTask(task.status) ? 5 : task.status === 'COMPLETED' ? 100 : 0;
  return Math.round((task.completedCount / task.totalCount) * 100);
}

function fileCount(task: ImageTaskSummary | ImageTaskDetail, type: UploadGroup): number {
  return task.fileSummary?.[type] ?? 0;
}

function analysisPreview(value: string | null | undefined): string {
  if (!value) return '暂无';
  return value.length > 300 ? `${value.slice(0, 300)}...` : value;
}

function isLongText(value: string | null | undefined): boolean {
  return Boolean(value && value.length > 300);
}

function openFullTextDialog(title: string, content: string | null | undefined) {
  if (!content) return;
  fullTextDialogTitle.value = title;
  fullTextDialogContent.value = content;
  fullTextDialogVisible.value = true;
}

function openImageViewer(src: string | null | undefined, name: string | null | undefined) {
  if (!src) return;
  imageViewerImage.value = {
    src,
    name: name?.trim() || 'image.png',
  };
  imageViewerRotation.value = 0;
  imageViewerVisible.value = true;
}

function closeImageViewer() {
  imageViewerVisible.value = false;
  imageViewerImage.value = null;
  imageViewerRotation.value = 0;
}

function rotateImageViewer() {
  imageViewerRotation.value = (imageViewerRotation.value + 90) % 360;
}

function handleImageViewerKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && imageViewerVisible.value) {
    closeImageViewer();
  }
}

function sanitizeDownloadName(name: string | null | undefined): string {
  const normalized = name?.trim().replace(/[\\/:*?"<>|]/g, '_') || 'image.png';
  return /\.[a-z0-9]{2,5}$/i.test(normalized) ? normalized : `${normalized}.png`;
}

async function downloadImage(src: string | null | undefined, name: string | null | undefined) {
  if (!src) return;
  const anchor = document.createElement('a');
  anchor.download = sanitizeDownloadName(name);
  try {
    if (src.startsWith('data:') || src.startsWith('blob:')) {
      anchor.href = src;
    } else {
      const response = await fetch(src);
      if (!response.ok) {
        throw new Error(response.statusText);
      }
      const objectUrl = URL.createObjectURL(await response.blob());
      anchor.href = objectUrl;
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    }
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
  } catch (error) {
    anchor.href = src;
    anchor.target = '_blank';
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    ElMessage.warning('图片已在新窗口打开，可从浏览器保存。');
  }
}

function taskFilesByGroup(type: UploadGroup) {
  return selectedQueueTask.value?.files?.[type] ?? [];
}

function generatedImageSrc(result: { imageUrl?: string | null; imageBase64?: string | null }): string {
  if (result.imageUrl) return result.imageUrl;
  if (!result.imageBase64) return '';
  return result.imageBase64.startsWith('data:')
    ? result.imageBase64
    : `data:image/png;base64,${result.imageBase64}`;
}

function resetTaskForm() {
  taskForm.value = {
    productName: '',
    model: '',
    platform: 'Amazon',
    ratio: '1536:1536',
    customWidth: DEFAULT_IMAGE_SIZE,
    customHeight: DEFAULT_IMAGE_SIZE,
    phoneColor: '自动',
    customColor: '#2563eb',
    logoName: '',
    wallpaperName: '',
    style: '自动',
    layout: '自动',
    sellingPoints: ['高清透亮', '9H硬度', '防指纹', '全屏覆盖', '易安装', '镜头保护'],
    newSellingPoint: '',
    hdEnabled: true,
    privacyEnabled: false,
    hdQuantity: 5,
    privacyQuantity: 0,
    mainImageCount: 7,
    introImageCount: 10,
    language: '英文',
    mainPrompt: '',
    introPrompt: '',
  };
  if (defaultSettings.value.mainPrompt) {
    taskForm.value.mainPrompt = defaultSettings.value.mainPrompt;
  }
  if (defaultSettings.value.introPrompt) {
    taskForm.value.introPrompt = defaultSettings.value.introPrompt;
  }
  taskForm.value.sellingPoints = [...defaultSettings.value.customSellingPoints];
  kitSpecs.value = kitSpecs.value.map((item) => ({ ...item, quantity: 0 }));
  clearUploadedImagesAndAnalysis();
  ElMessage.success('任务参数已重置。');
}

function pageTitle(): string {
  if (activePage.value === 'quota') return 'ImageAI 额度监控';
  if (activePage.value === 'queue') return '任务队列';
  if (activePage.value === 'settings') return '默认设置';
  return '创建新任务';
}

function pageEyebrow(): string {
  if (activePage.value === 'quota') return 'CLI Proxy API Management';
  if (activePage.value === 'queue') return 'ImageAI Queue';
  if (activePage.value === 'settings') return 'ImageAI Defaults';
  return 'ImageAI Task Center';
}

function pageSubtitle(): string {
  if (activePage.value === 'quota') return '集中查看账号额度、图片生成余量与服务器资源状态。';
  if (activePage.value === 'queue') return '按任务查看待生成图片的参数、素材缩略图和生图详情。';
  if (activePage.value === 'settings') return '维护主图和介绍图默认提示词，添加任务时自动带入。';
  return '上传资料并配置生成参数，将商品主图与介绍图任务加入队列。';
}
</script>

<template>
  <el-config-provider>
    <el-container class="app-layout">
      <el-aside :width="isCollapsed ? '68px' : '292px'" class="sidebar" :class="{ collapsed: isCollapsed }">
        <div class="brand-block">
          <div class="brand-main">
            <img class="brand-icon" :src="appIcon" alt="ImageAI" />
            <div v-if="!isCollapsed" class="brand-copy">
              <strong style="position: relative; top: 2px; user-select: none;">
                ImageAI
              </strong>
            </div>
          </div>
          <button class="collapse-button" type="button" title="折叠菜单" @click="isCollapsed = !isCollapsed">
            <el-icon>
              <Expand v-if="isCollapsed" />
              <Fold v-else />
            </el-icon>
          </button>
        </div>

        <div v-if="!isCollapsed" class="sidebar-section-label">工作台</div>

        <div class="nav-list">
          <button
            class="nav-item"
            :class="{ active: activePage === 'quota' }"
            type="button"
            @click="activePage = 'quota'"
          >
            <el-icon><Monitor /></el-icon>
            <span v-if="!isCollapsed">ImageAI 额度</span>
          </button>
          <button
            class="nav-item"
            :class="{ active: activePage === 'task' }"
            type="button"
            @click="activePage = 'task'"
          >
            <el-icon><Plus /></el-icon>
            <span v-if="!isCollapsed">添加任务</span>
          </button>
          <button
            class="nav-item"
            :class="{ active: activePage === 'queue' }"
            type="button"
            @click="openQueuePage"
          >
            <el-icon><Document /></el-icon>
            <span v-if="!isCollapsed">任务队列</span>
          </button>
          <button
            class="nav-item"
            :class="{ active: activePage === 'settings' }"
            type="button"
            @click="activePage = 'settings'"
          >
            <el-icon><Setting /></el-icon>
            <span v-if="!isCollapsed">默认设置</span>
          </button>
        </div>

        <div v-if="!isCollapsed" class="sidebar-footer">
          <span>系统状态</span>
          <strong>{{ systemOverview ? `${formatMetricPercent(systemOverview.cpuUsagePercent)} CPU` : systemFallbackText() }}</strong>
        </div>
      </el-aside>

      <el-container class="main-area">
        <el-header class="topbar">
          <div class="title-block">
            <p class="eyebrow">{{ pageEyebrow() }}</p>
            <h1>{{ pageTitle() }}</h1>
            <p class="page-subtitle">{{ pageSubtitle() }}</p>
          </div>
          <div v-if="activePage === 'quota'" class="topbar-actions">
            <span v-if="lastRefreshAt" class="refresh-time">刷新于 {{ lastRefreshAt }}</span>
            <el-button :icon="Refresh" type="primary" :loading="loading" @click="refreshQuota">
              刷新
            </el-button>
          </div>
          <div v-else-if="activePage === 'task'" class="topbar-actions">
            <el-button
              type="primary"
              :icon="Plus"
              :loading="addingTask"
              @click="addToTaskQueue"
            >
              添加到任务队列
            </el-button>
          </div>
          <div v-else-if="activePage === 'settings'" class="topbar-actions">
            <el-button
              type="primary"
              :icon="Document"
              :loading="settingsSaving"
              @click="savePromptSettings"
            >
              保存默认设置
            </el-button>
          </div>
          <div v-else class="topbar-actions">
            <span class="refresh-time">{{ taskQueue.length }} 个任务</span>
          </div>
        </el-header>

        <el-main class="content-wrap">
          <el-alert
            v-if="activePage === 'quota' && errorMessage"
            class="alert-block"
            type="error"
            :title="errorMessage"
            show-icon
            :closable="false"
          />

          <template v-if="activePage === 'quota'">
          <section class="summary-grid">
            <article class="summary-card">
              <span class="summary-label">ImageAI 账号</span>
              <strong>{{ stats.totalAccounts }}</strong>
              <small>{{ stats.activeAccounts }} 个正常</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">平均 5 小时额度</span>
              <strong>{{ formatPercent(stats.averageFiveHourPercent) }}</strong>
              <small>可生成 {{ stats.fiveHourImages.toLocaleString() }} 张图</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">平均每周额度</span>
              <strong>{{ formatPercent(stats.averageWeeklyPercent) }}</strong>
              <small>可生成 {{ stats.weeklyImages.toLocaleString() }} 张图</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">系统版本</span>
              <strong>{{ systemOverview?.osFamily || systemFallbackText() }}</strong>
              <small>{{ systemOverview?.systemVersion || systemErrorMessage || '等待后端返回' }}</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">CPU 占比</span>
              <strong>{{ formatMetricPercent(systemOverview?.cpuUsagePercent) }}</strong>
              <small>{{ systemErrorMessage || '服务器实时负载' }}</small>
            </article>
            <article class="summary-card">
              <span class="summary-label">内存信息</span>
              <strong>{{ formatMetricPercent(systemOverview?.memoryUsagePercent) }}</strong>
              <small>
                {{ formatBytes(systemOverview?.memoryUsedBytes) }} /
                {{ formatBytes(systemOverview?.memoryTotalBytes) }}
              </small>
            </article>
            <article class="summary-card">
              <span class="summary-label">外存信息</span>
              <strong>{{ formatMetricPercent(systemOverview?.diskUsagePercent) }}</strong>
              <small>
                {{ formatBytes(systemOverview?.diskUsedBytes) }} /
                {{ formatBytes(systemOverview?.diskTotalBytes) }}
              </small>
            </article>
            <article class="summary-card">
              <span class="summary-label">查询状态</span>
              <strong>{{ loading ? '刷新中' : '已就绪' }}</strong>
              <small>{{ errorMessage ? '需要检查后端接口' : '通过后端代理实时查询' }}</small>
            </article>
          </section>
          </template>

          <section v-else-if="activePage === 'task'" class="task-page">
            <div class="task-column product-panel">
              <section class="task-card">
                <div class="task-card-head">
                  <div>
                    <h2>商品资料</h2>
                    <p>实拍图、包装图和模板用于任务生成参考。</p>
                  </div>
                  <el-button size="small" :icon="Refresh" @click="ensureProductName">生成名称</el-button>
                </div>

                <div class="form-row">
                  <label>商品名称</label>
                  <div class="inline-fields">
                    <el-input v-model="taskForm.productName" placeholder="留空自动生成 8 位数字字母名称" />
                    <el-button @click="ensureProductName">自动生成</el-button>
                  </div>
                </div>

                <div class="upload-section">
                  <div class="section-title">
                    <span>实拍图</span>
                    <small>可上传多个</small>
                    <el-button
                      size="small"
                      text
                      type="primary"
                      :loading="analysisLoading['实拍图']"
                      @click="analyzeUploadImage('实拍图')"
                    >
                      深析上传图
                    </el-button>
                    <el-popover
                      placement="right"
                      width="420"
                      trigger="hover"
                    >
                      <template #reference>
                        <span class="analysis-chip" :class="{ ready: uploadAnalysis['实拍图'] }">分析结果</span>
                      </template>
                      <div class="analysis-popover">
                        <strong>实拍图深析结果</strong>
                        <p>{{ uploadAnalysis['实拍图']?.result || '上传图片后点击“深析上传图”，这里会显示 GPT 5.5 返回的分析结果。' }}</p>
                      </div>
                    </el-popover>
                  </div>
                  <el-upload
                    v-model:file-list="realPhotoFiles"
                    class="compact-upload"
                    action="#"
                    drag
                    multiple
                    :auto-upload="false"
                    :show-file-list="false"
                    @remove="handleUploadRemove"
                  >
                    <el-icon><Upload /></el-icon>
                    <div>拖拽或点击上传实拍图</div>
                  </el-upload>
                  <div v-if="realPhotoFiles.length" class="preview-grid">
                    <div v-for="file in realPhotoFiles" :key="uploadKey(file)" class="preview-tile">
                      <div class="image-action-wrap" role="button" tabindex="0" @click="openImageViewer(filePreviewUrl(file), file.name)" @keydown.enter="openImageViewer(filePreviewUrl(file), file.name)">
                        <img :src="filePreviewUrl(file)" :alt="file.name" />
                        <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(filePreviewUrl(file), file.name)">
                          <el-icon><Download /></el-icon>
                        </button>
                      </div>
                      <span>{{ file.name }}</span>
                      <button class="remove-upload-button" type="button" @click="removeUploadFile('实拍图', file)">移除</button>
                    </div>
                  </div>
                  <p
                    class="analysis-result-line"
                    :class="{ ready: uploadAnalysis['实拍图'] }"
                  >
                    {{ uploadAnalysis['实拍图']?.result || (analysisLoading['实拍图'] ? '正在深析上传图...' : '暂无深析结果') }}
                  </p>
                </div>

                <div class="upload-section">
                  <div class="section-title">
                    <span>包装图</span>
                    <small>可上传多个</small>
                    <el-button
                      size="small"
                      text
                      type="primary"
                      :loading="analysisLoading['包装图']"
                      @click="analyzeUploadImage('包装图')"
                    >
                      深析上传图
                    </el-button>
                    <el-popover
                      placement="right"
                      width="420"
                      trigger="hover"
                    >
                      <template #reference>
                        <span class="analysis-chip" :class="{ ready: uploadAnalysis['包装图'] }">分析结果</span>
                      </template>
                      <div class="analysis-popover">
                        <strong>包装图深析结果</strong>
                        <p>{{ uploadAnalysis['包装图']?.result || '上传图片后点击“深析上传图”，这里会显示 GPT 5.5 返回的分析结果。' }}</p>
                      </div>
                    </el-popover>
                  </div>
                  <el-upload
                    v-model:file-list="packageImageFiles"
                    class="compact-upload"
                    action="#"
                    drag
                    multiple
                    :auto-upload="false"
                    :show-file-list="false"
                    @remove="handleUploadRemove"
                  >
                    <el-icon><Upload /></el-icon>
                    <div>拖拽或点击上传包装图</div>
                  </el-upload>
                  <div v-if="packageImageFiles.length" class="preview-grid">
                    <div v-for="file in packageImageFiles" :key="uploadKey(file)" class="preview-tile">
                      <div class="image-action-wrap" role="button" tabindex="0" @click="openImageViewer(filePreviewUrl(file), file.name)" @keydown.enter="openImageViewer(filePreviewUrl(file), file.name)">
                        <img :src="filePreviewUrl(file)" :alt="file.name" />
                        <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(filePreviewUrl(file), file.name)">
                          <el-icon><Download /></el-icon>
                        </button>
                      </div>
                      <span>{{ file.name }}</span>
                      <button class="remove-upload-button" type="button" @click="removeUploadFile('包装图', file)">移除</button>
                    </div>
                  </div>
                  <p
                    class="analysis-result-line"
                    :class="{ ready: uploadAnalysis['包装图'] }"
                  >
                    {{ uploadAnalysis['包装图']?.result || (analysisLoading['包装图'] ? '正在深析上传图...' : '暂无深析结果') }}
                  </p>
                </div>

                <div class="upload-section">
                  <div class="section-title">
                    <span>模板图</span>
                    <small>仅支持上传一张</small>
                  </div>
                  <el-upload
                    v-model:file-list="templateFiles"
                    class="compact-upload"
                    action="#"
                    drag
                    :limit="1"
                    :auto-upload="false"
                    :show-file-list="false"
                    @remove="handleUploadRemove"
                  >
                    <el-icon><Upload /></el-icon>
                    <div>上传模板图</div>
                  </el-upload>
                  <div v-if="templateFiles.length" class="preview-grid">
                    <div v-for="file in templateFiles" :key="uploadKey(file)" class="preview-tile">
                      <div class="image-action-wrap" role="button" tabindex="0" @click="openImageViewer(filePreviewUrl(file), file.name)" @keydown.enter="openImageViewer(filePreviewUrl(file), file.name)">
                        <img :src="filePreviewUrl(file)" :alt="file.name" />
                        <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(filePreviewUrl(file), file.name)">
                          <el-icon><Download /></el-icon>
                        </button>
                      </div>
                      <span>{{ file.name }}</span>
                      <button class="remove-upload-button" type="button" @click="removeUploadFile('模板图', file)">移除</button>
                    </div>
                  </div>
                </div>
              </section>

              <section class="task-card">
                <div class="task-card-head">
                  <div>
                    <h2>品牌与素材</h2>
                    <p>Logo、壁纸和品牌名称会进入提示词上下文。</p>
                  </div>
                </div>
                <div class="two-fields">
                  <div class="form-row">
                    <label>Logo 图片</label>
                    <el-upload
                      v-model:file-list="logoFiles"
                      class="line-upload"
                      action="#"
                      :auto-upload="false"
                      :limit="1"
                      :show-file-list="false"
                      @remove="handleUploadRemove"
                    >
                      <el-button :icon="Upload">上传 Logo</el-button>
                    </el-upload>
                    <div v-if="logoFiles.length" class="preview-grid compact-preview-grid">
                      <div v-for="file in logoFiles" :key="uploadKey(file)" class="preview-tile">
                        <div class="image-action-wrap" role="button" tabindex="0" @click="openImageViewer(filePreviewUrl(file), file.name)" @keydown.enter="openImageViewer(filePreviewUrl(file), file.name)">
                          <img :src="filePreviewUrl(file)" :alt="file.name" />
                          <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(filePreviewUrl(file), file.name)">
                            <el-icon><Download /></el-icon>
                          </button>
                        </div>
                        <span>{{ file.name }}</span>
                        <button class="remove-upload-button" type="button" @click="removeUploadFile('Logo图', file)">移除</button>
                      </div>
                    </div>
                  </div>
                  <div class="form-row">
                    <label>Logo 名称</label>
                    <div class="inline-fields">
                      <el-input v-model="taskForm.logoName" placeholder="输入品牌名，留空可自动识别" />
                      <el-button @click="autoRecognizeLogo">自动识别</el-button>
                    </div>
                  </div>
                </div>
                <div class="form-row">
                  <label>手机壁纸</label>
                  <el-upload
                    v-model:file-list="wallpaperFiles"
                    class="compact-upload"
                    action="#"
                    drag
                    :limit="1"
                    :auto-upload="false"
                    :show-file-list="false"
                    @remove="handleUploadRemove"
                  >
                    <el-icon><Upload /></el-icon>
                    <div>上传壁纸，可选</div>
                  </el-upload>
                  <div v-if="wallpaperFiles.length" class="preview-grid compact-preview-grid">
                    <div v-for="file in wallpaperFiles" :key="uploadKey(file)" class="preview-tile">
                      <div class="image-action-wrap" role="button" tabindex="0" @click="openImageViewer(filePreviewUrl(file), file.name)" @keydown.enter="openImageViewer(filePreviewUrl(file), file.name)">
                        <img :src="filePreviewUrl(file)" :alt="file.name" />
                        <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(filePreviewUrl(file), file.name)">
                          <el-icon><Download /></el-icon>
                        </button>
                      </div>
                      <span>{{ file.name }}</span>
                      <button class="remove-upload-button" type="button" @click="removeUploadFile('壁纸图', file)">移除</button>
                    </div>
                  </div>
                </div>
              </section>
            </div>

            <div class="task-column parameter-panel">
              <section class="task-card">
                <div class="task-card-head">
                  <div>
                    <h2>生成参数</h2>
                    <p>平台、比例、机型、颜色与设计风格。</p>
                  </div>
                </div>

                <div class="choice-grid">
                  <div class="form-row">
                    <label>平台选择</label>
                    <div class="segmented-list">
                      <button
                        v-for="platform in platforms"
                        :key="platform"
                        type="button"
                        :class="{ selected: taskForm.platform === platform }"
                        @click="selectPlatform(platform)"
                      >
                        {{ platform }}
                      </button>
                    </div>
                  </div>
                  <div class="form-row">
                    <div class="label-help-row">
                      <label>图片比例</label>
                      <el-popover placement="top" width="320" trigger="hover">
                        <template #reference>
                          <button class="help-button" type="button">?</button>
                        </template>
                        <p class="help-copy">
                          Image 2 要求宽高都能被 16 整除。推荐 Amazon 用 1536 x 1536；TEMU 用 1024 x 1024；横版场景用 960 x 640。自定义尺寸会自动吸附到 16 的倍数。
                        </p>
                      </el-popover>
                    </div>
                    <div class="segmented-list">
                      <button
                        v-for="ratio in ratioOptions"
                        :key="ratio"
                        type="button"
                        :class="{ selected: taskForm.ratio === ratio }"
                        @click="selectRatio(ratio)"
                      >
                        {{ ratio }}
                      </button>
                    </div>
                    <div class="ratio-inputs">
                      <el-input-number
                        v-model="taskForm.customWidth"
                        :min="304"
                        :step="16"
                        controls-position="right"
                        @change="normalizeCustomImageSize"
                      />
                      <span>×</span>
                      <el-input-number
                        v-model="taskForm.customHeight"
                        :min="304"
                        :step="16"
                        controls-position="right"
                        @change="normalizeCustomImageSize"
                      />
                    </div>
                  </div>
                </div>

                <div class="choice-grid">
                  <div class="form-row">
                    <label>机型</label>
                    <div class="inline-fields">
                      <el-input v-model="taskForm.model" placeholder="不输入时自动识别参考图" />
                      <el-button @click="autoRecognizeModel">自动识别</el-button>
                    </div>
                  </div>
                  <div class="form-row">
                    <label>手机颜色</label>
                    <div class="color-list">
                      <button
                        v-for="color in phoneColors"
                        :key="color"
                        type="button"
                        :class="{ selected: taskForm.phoneColor === color }"
                        @click="taskForm.phoneColor = color"
                      >
                        {{ color }}
                      </button>
                      <div class="custom-color-control">
                        <span>取色</span>
                        <el-color-picker
                          v-model="taskForm.customColor"
                          size="large"
                          @change="taskForm.phoneColor = '自定义'"
                        />
                      </div>
                    </div>
                  </div>
                </div>

                <div class="choice-grid">
                  <div class="form-row">
                    <label>设计风格</label>
                    <div class="tile-list">
                      <button
                        v-for="style in styleOptions"
                        :key="style"
                        type="button"
                        :class="{ selected: taskForm.style === style }"
                        @click="taskForm.style = style"
                      >
                        {{ style }}
                      </button>
                    </div>
                  </div>
                  <div class="form-row">
                    <label>布局模式</label>
                    <div class="tile-list">
                      <button
                        v-for="layout in layoutOptions"
                        :key="layout"
                        type="button"
                        :class="{ selected: taskForm.layout === layout }"
                        @click="taskForm.layout = layout"
                      >
                        {{ layout }}
                      </button>
                    </div>
                  </div>
                </div>
              </section>

              <section class="task-card">
                <div class="task-card-head">
                  <div>
                    <h2>套装规格与卖点</h2>
                    <p>默认数量为 0，可按任务需要手动调整。</p>
                  </div>
                  <el-button size="small" text type="primary" @click="autoRecognizeKitSpecs">自动识别</el-button>
                </div>

                <div class="kit-grid">
                  <div v-for="(item, index) in kitSpecs" :key="item.name" class="kit-item">
                    <span>{{ item.name }}</span>
                    <div class="stepper">
                      <button type="button" @click="updateQuantity(index, -1)">-</button>
                      <strong>{{ item.quantity }}</strong>
                      <button type="button" @click="updateQuantity(index, 1)">+</button>
                    </div>
                  </div>
                </div>

                <div class="form-row no-margin">
                  <label>卖点多选</label>
                  <el-select
                    v-model="taskForm.sellingPoints"
                    class="selling-select"
                    multiple
                    filterable
                    collapse-tags
                    collapse-tags-tooltip
                    placeholder="从默认设置中选择卖点"
                  >
                    <el-option
                      v-for="point in sellingPointOptions"
                      :key="point"
                      :label="point"
                      :value="point"
                    />
                  </el-select>
                </div>
              </section>

              <section class="task-card">
                <div class="task-card-head">
                  <div>
                    <h2>生成数量与提示词</h2>
                    <p>主图和介绍图提示词可分别填写，不填则自动组合。</p>
                  </div>
                </div>

                <div class="generation-panel">
                  <div class="quantity-row">
                    <label class="feature-toggle">
                      <el-checkbox v-model="taskForm.hdEnabled" />
                      <span>高清（HD）</span>
                    </label>
                    <el-input-number v-model="taskForm.hdQuantity" :min="0" :max="99" />
                  </div>
                  <div class="quantity-row">
                    <label class="feature-toggle">
                      <el-checkbox v-model="taskForm.privacyEnabled" />
                      <span>防窥膜（Privacy）</span>
                    </label>
                    <el-input-number v-model="taskForm.privacyQuantity" :min="0" :max="99" />
                  </div>
                  <div class="quantity-row">
                    <span>主图数量</span>
                    <el-input-number v-model="taskForm.mainImageCount" :min="0" :max="99" />
                  </div>
                  <div class="quantity-row">
                    <span>介绍图数量</span>
                    <el-input-number v-model="taskForm.introImageCount" :min="0" :max="99" />
                  </div>
                  <div class="language-row">
                    <span>语言</span>
                    <el-radio-group v-model="taskForm.language">
                      <el-radio-button v-for="lang in languageOptions" :key="lang" :label="lang" />
                    </el-radio-group>
                  </div>
                </div>

                <div class="prompt-grid">
                  <div class="form-row">
                    <label>主图提示词</label>
                    <el-input
                      v-model="taskForm.mainPrompt"
                      type="textarea"
                      :rows="4"
                      maxlength="500"
                      show-word-limit
                      placeholder="输入主图提示词，描述画面效果、风格、元素等"
                    />
                  </div>
                  <div class="form-row">
                    <label>介绍图提示词</label>
                    <el-input
                      v-model="taskForm.introPrompt"
                      type="textarea"
                      :rows="4"
                      maxlength="500"
                      show-word-limit
                      placeholder="输入介绍图提示词，描述展示内容和风格"
                    />
                  </div>
                </div>
              </section>

              <div class="task-actions">
                <el-button size="large" type="primary" :icon="Plus" :loading="addingTask" @click="addToTaskQueue">添加到任务队列</el-button>
                <el-button size="large" :icon="Refresh" @click="resetTaskForm">清空重置</el-button>
              </div>
            </div>
          </section>

          <section v-else-if="activePage === 'queue'" class="queue-page">
            <section class="queue-panel" v-loading="queueLoading">
              <div class="panel-head">
                <div>
                  <h2>任务队列</h2>
                  <p>后端会先深析上传图，再把深析结果拼接到最终生图提示词中。</p>
                </div>
                <div class="queue-head-actions">
                  <span>{{ taskQueue.length }} 个任务</span>
                  <el-button :icon="Refresh" @click="loadTaskQueue()">刷新</el-button>
                </div>
              </div>

              <el-alert
                v-if="queueErrorMessage"
                class="queue-error"
                type="error"
                :title="queueErrorMessage"
                show-icon
                :closable="false"
              />

              <el-empty
                v-if="!queueLoading && taskQueue.length === 0"
                description="暂无任务，先在添加任务页点击添加到任务队列"
              />

              <div v-else class="queue-list">
                <article v-for="task in taskQueue" :key="task.id" class="queue-row">
                  <div
                    class="queue-thumb image-action-wrap"
                    role="button"
                    tabindex="0"
                    @click="task.thumbnail ? openImageViewer(task.thumbnail, task.thumbnailName) : openQueueDetail(task)"
                    @keydown.enter="task.thumbnail ? openImageViewer(task.thumbnail, task.thumbnailName) : openQueueDetail(task)"
                  >
                    <img v-if="task.thumbnail" :src="task.thumbnail" :alt="task.thumbnailName" />
                    <button
                      v-if="task.thumbnail"
                      class="image-download-button"
                      type="button"
                      title="下载图片"
                      @click.stop="downloadImage(task.thumbnail, task.thumbnailName)"
                    >
                      <el-icon><Download /></el-icon>
                    </button>
                    <span v-else>无图</span>
                  </div>
                  <div class="queue-main">
                    <div class="queue-title-row">
                      <h2>{{ task.productName }}</h2>
                      <el-tag :type="taskStatusTagType(task.status)" effect="plain">{{ task.statusText }}</el-tag>
                    </div>
                    <p>{{ task.form.platform }} / {{ task.form.customWidth }} x {{ task.form.customHeight }} / {{ task.form.language }}</p>
                    <div class="queue-tags">
                      <span>实拍图 {{ fileCount(task, '实拍图') }}</span>
                      <span>包装图 {{ fileCount(task, '包装图') }}</span>
                      <span>模板图 {{ fileCount(task, '模板图') }}</span>
                      <span>主图 {{ task.form.mainImageCount }}</span>
                      <span>介绍图 {{ task.form.introImageCount }}</span>
                      <span>生成 {{ task.completedCount }} / {{ task.totalCount }}</span>
                    </div>
                    <el-progress
                      class="queue-progress"
                      :percentage="taskProgress(task)"
                      :stroke-width="6"
                      :show-text="false"
                      :status="task.status === 'FAILED' ? 'exception' : task.status === 'COMPLETED' ? 'success' : undefined"
                    />
                    <p v-if="task.errorMessage" class="queue-error-text">{{ task.errorMessage }}</p>
                  </div>
                  <div class="queue-side">
                    <span>{{ task.createdAt }}</span>
                    <el-button text type="primary" @click="openQueueDetail(task)">查看详情</el-button>
                    <el-button v-if="task.status === 'FAILED'" text type="warning" @click="retryQueuedTask(task)">重试</el-button>
                  </div>
                </article>
              </div>
            </section>
          </section>

          <section v-else class="settings-page">
            <section class="task-card settings-card" v-loading="settingsLoading">
              <div class="task-card-head">
                <div>
                  <h2>提示词默认内容</h2>
                  <p>这里保存到数据库，添加任务页面会默认带入这两段提示词。</p>
                </div>
                <el-button :icon="Refresh" @click="loadPromptSettings">重新读取</el-button>
              </div>

              <div class="prompt-grid">
                <div class="form-row">
                  <label>主图提示词默认内容</label>
                  <el-input
                    v-model="defaultSettings.mainPrompt"
                    type="textarea"
                    :rows="10"
                    maxlength="2000"
                    show-word-limit
                    placeholder="请输入主图提示词默认内容"
                  />
                </div>
                <div class="form-row">
                  <label>介绍图提示词默认内容</label>
                  <el-input
                    v-model="defaultSettings.introPrompt"
                    type="textarea"
                    :rows="10"
                    maxlength="2000"
                    show-word-limit
                    placeholder="请输入介绍图提示词默认内容"
                  />
                </div>
              </div>

              <div class="form-row">
                <label>深析上传图的提示词</label>
                <el-input
                  v-model="defaultSettings.analysisPrompt"
                  type="textarea"
                  :rows="5"
                  maxlength="2000"
                  show-word-limit
                  placeholder="请输入深析上传图时发送给 GPT 的默认分析要求"
                />
              </div>

              <div class="form-row">
                <label>自定义卖点</label>
                <div class="default-point-editor">
                  <el-tag
                    v-for="point in defaultSettings.customSellingPoints"
                    :key="point"
                    closable
                    effect="plain"
                    @close="removeDefaultSellingPoint(point)"
                  >
                    {{ point }}
                  </el-tag>
                  <div class="add-point">
                    <el-input
                      v-model="newDefaultSellingPoint"
                      placeholder="输入卖点后回车添加"
                      @keyup.enter="addDefaultSellingPoint"
                    />
                    <el-button :icon="Plus" @click="addDefaultSellingPoint">添加</el-button>
                  </div>
                </div>
              </div>

              <div class="settings-actions">
                <el-button type="primary" size="large" :loading="settingsSaving" @click="savePromptSettings">
                  保存到数据库
                </el-button>
                <span>保存后会同步更新“添加任务”页面的默认提示词。</span>
              </div>
            </section>
          </section>

          <template v-if="activePage === 'quota'">
          <el-skeleton v-if="loading && accounts.length === 0" :rows="8" animated class="quota-skeleton" />

          <el-empty
            v-else-if="!loading && accounts.length === 0 && !errorMessage"
            description="暂无 ImageAI 账号额度数据"
          />

          <section v-else class="accounts-panel">
            <div class="panel-head">
              <div>
                <h2>账号额度</h2>
                <p>每 5 小时 1% 约等于 1 张图，每周 1% 约等于 8 张图。</p>
              </div>
              <span>{{ accounts.length }} 个账号</span>
            </div>
            <div class="account-scroll">
              <div class="account-grid">
                <div v-for="account in accounts" :key="account.id" class="account-cell">
              <el-card shadow="hover" class="account-card">
                <template #header>
                  <div class="account-header">
                    <div class="account-title">
                      <el-icon class="account-icon"><CircleCheck /></el-icon>
                      <div>
                        <h2>{{ account.name }}</h2>
                        <p>{{ account.fileName }}</p>
                      </div>
                    </div>
                    <el-tag style="margin-top: -12px;" :type="statusTagType(account.status)" effect="light">
                      {{ account.statusText }}
                    </el-tag>
                  </div>
                </template>

                <el-alert
                  v-if="account.error"
                  class="account-error"
                  type="error"
                  :title="account.error"
                  show-icon
                  :closable="false"
                />

                <div class="meta-row">
                  <span>套餐</span>
                  <strong>{{ account.planType || '未知' }}</strong>
                </div>

                <div class="quota-block">
                  <div class="quota-head">
                    <div>
                      <span class="quota-label">5 小时额度</span>
                      <strong>{{ formatPercent(account.fiveHour.remainingPercent) }}</strong>
                    </div>
                    <div class="image-count">{{ formatNumber(account.fiveHourImages) }} 张</div>
                  </div>
                  <el-progress
                    :percentage="account.fiveHour.remainingPercent ?? 0"
                    :color="progressColor(account.fiveHour.remainingPercent)"
                    :stroke-width="8"
                  />
                  <div class="quota-foot">
                    <span>已用 {{ formatPercent(account.fiveHour.usedPercent) }}</span>
                    <span>重置 {{ account.fiveHour.resetLabel }}</span>
                  </div>
                </div>

                <div class="quota-block">
                  <div class="quota-head">
                    <div>
                      <span class="quota-label">每周额度</span>
                      <strong>{{ formatPercent(account.weekly.remainingPercent) }}</strong>
                    </div>
                    <div class="image-count">{{ formatNumber(account.weeklyImages) }} 张</div>
                  </div>
                  <el-progress
                    :percentage="account.weekly.remainingPercent ?? 0"
                    :color="progressColor(account.weekly.remainingPercent)"
                    :stroke-width="8"
                  />
                  <div class="quota-foot">
                    <span>已用 {{ formatPercent(account.weekly.usedPercent) }}</span>
                    <span>重置 {{ account.weekly.resetLabel }}</span>
                  </div>
                </div>

                <div class="card-footer">
                  <span>最后刷新时间</span>
                  <strong>{{ account.lastRefreshTime }}</strong>
                </div>
              </el-card>
                </div>
              </div>
            </div>
          </section>
          </template>
        </el-main>
      </el-container>
    </el-container>

    <el-dialog
      v-model="queueDetailVisible"
      title="生图详情"
      width="90vw"
      top="5vh"
      class="queue-detail-dialog"
      append-to-body
      destroy-on-close
    >
      <div v-if="selectedQueueTask" class="queue-detail">
        <div class="queue-detail-head">
          <div class="queue-detail-thumb">
            <div
              v-if="selectedQueueTask.thumbnail"
              class="image-action-wrap"
              role="button"
              tabindex="0"
              @click="openImageViewer(selectedQueueTask.thumbnail, selectedQueueTask.thumbnailName)"
              @keydown.enter="openImageViewer(selectedQueueTask.thumbnail, selectedQueueTask.thumbnailName)"
            >
              <img :src="selectedQueueTask.thumbnail" :alt="selectedQueueTask.thumbnailName" />
              <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(selectedQueueTask.thumbnail, selectedQueueTask.thumbnailName)">
                <el-icon><Download /></el-icon>
              </button>
            </div>
            <span v-else>无缩略图</span>
          </div>
          <div class="queue-detail-title">
            <div>
              <h2>{{ selectedQueueTask.productName }}</h2>
              <p>{{ selectedQueueTask.createdAt }} / {{ selectedQueueTask.statusText }}</p>
            </div>
            <div class="queue-tags">
              <span>{{ selectedQueueTask.form.platform }}</span>
              <span>{{ selectedQueueTask.form.customWidth }} x {{ selectedQueueTask.form.customHeight }}</span>
              <span>{{ selectedQueueTask.form.language }}</span>
              <span>生成 {{ selectedQueueTask.completedCount }} / {{ selectedQueueTask.totalCount }}</span>
            </div>
          </div>
        </div>

        <el-descriptions class="compact-descriptions" :column="4" size="small" border>
          <el-descriptions-item label="机型">{{ selectedQueueTask.form.model || '自动识别' }}</el-descriptions-item>
          <el-descriptions-item label="手机颜色">{{ selectedQueueTask.form.phoneColor }}</el-descriptions-item>
          <el-descriptions-item label="设计风格">{{ selectedQueueTask.form.style }}</el-descriptions-item>
          <el-descriptions-item label="布局模式">{{ selectedQueueTask.form.layout }}</el-descriptions-item>
          <el-descriptions-item label="主图数量">{{ selectedQueueTask.form.mainImageCount }}</el-descriptions-item>
          <el-descriptions-item label="介绍图数量">{{ selectedQueueTask.form.introImageCount }}</el-descriptions-item>
          <el-descriptions-item label="高清数量">{{ selectedQueueTask.form.hdEnabled ? selectedQueueTask.form.hdQuantity : 0 }}</el-descriptions-item>
          <el-descriptions-item label="防窥数量">{{ selectedQueueTask.form.privacyEnabled ? selectedQueueTask.form.privacyQuantity : 0 }}</el-descriptions-item>
          <el-descriptions-item label="素材数量">
            实拍 {{ fileCount(selectedQueueTask, '实拍图') }} /
            包装 {{ fileCount(selectedQueueTask, '包装图') }} /
            模板 {{ fileCount(selectedQueueTask, '模板图') }}
          </el-descriptions-item>
          <el-descriptions-item label="卖点" :span="3">
            {{ selectedQueueTask.form.sellingPoints.join('、') || '未选择' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="selectedQueueTask.errorMessage" label="错误信息" :span="4">
            <span class="queue-error-text">{{ selectedQueueTask.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="queue-detail-grid">
          <section class="detail-block">
            <h3>提交图片</h3>
            <div class="submitted-file-groups">
              <div v-for="type in uploadGroups" :key="type" class="submitted-file-group">
                <div class="submitted-file-title">{{ type }} {{ taskFilesByGroup(type).length }}</div>
                <div v-if="taskFilesByGroup(type).length" class="submitted-file-grid">
                  <article v-for="file in taskFilesByGroup(type)" :key="file.id" class="submitted-file-card">
                    <div
                      v-if="file.preview"
                      class="image-action-wrap"
                      role="button"
                      tabindex="0"
                      @click="openImageViewer(file.preview, file.fileName)"
                      @keydown.enter="openImageViewer(file.preview, file.fileName)"
                    >
                      <img :src="file.preview" :alt="file.fileName" />
                      <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(file.preview, file.fileName)">
                        <el-icon><Download /></el-icon>
                      </button>
                    </div>
                    <span v-else>无预览</span>
                    <p>{{ file.fileName }}</p>
                  </article>
                </div>
                <p v-else class="empty-inline">未上传</p>
              </div>
            </div>
          </section>

          <section class="detail-block">
            <h3>套装规格</h3>
            <div class="queue-tags">
              <span v-for="item in selectedQueueTask.kitSpecs" :key="item.name">
                {{ item.name }} x {{ item.quantity }}
              </span>
            </div>
          </section>

          <section class="detail-block detail-wide">
            <h3>后端深析结果</h3>
            <div class="analysis-grid">
              <article v-for="type in uploadGroups" :key="type" class="analysis-card">
                <strong>{{ type }}</strong>
                <p
                  :class="{ clickable: isLongText(selectedQueueTask.analysis?.[type]) }"
                  @click="openFullTextDialog(`${type}深析结果`, selectedQueueTask.analysis?.[type])"
                >
                  {{ analysisPreview(selectedQueueTask.analysis?.[type]) }}
                </p>
                <el-button
                  v-if="isLongText(selectedQueueTask.analysis?.[type])"
                  text
                  type="primary"
                  @click="openFullTextDialog(`${type}深析结果`, selectedQueueTask.analysis?.[type])"
                >
                  查看全文
                </el-button>
              </article>
            </div>
          </section>

          <section class="detail-block detail-wide">
            <h3>最终生图提示词</h3>
            <div class="prompt-preview-grid">
              <article>
                <strong>主图</strong>
                <p @click="openFullTextDialog('主图最终提示词', selectedQueueTask.finalMainPrompt)">
                  {{ analysisPreview(selectedQueueTask.finalMainPrompt || '后端生成中') }}
                </p>
              </article>
              <article>
                <strong>介绍图</strong>
                <p @click="openFullTextDialog('介绍图最终提示词', selectedQueueTask.finalIntroPrompt)">
                  {{ analysisPreview(selectedQueueTask.finalIntroPrompt || '后端生成中') }}
                </p>
              </article>
            </div>
          </section>

          <section class="detail-block detail-wide">
            <h3>生成结果</h3>
            <el-empty v-if="selectedQueueTask.results.length === 0" description="暂无生成结果" />
            <div v-else class="result-list">
              <article v-for="result in selectedQueueTask.results" :key="result.id" class="result-row">
                <div>
                  <strong>{{ result.resultType }} #{{ result.itemIndex }}</strong>
                  <el-tag :type="taskStatusTagType(result.status)" effect="plain">{{ result.statusText }}</el-tag>
                </div>
                <div
                  v-if="generatedImageSrc(result)"
                  class="result-preview image-action-wrap"
                  role="button"
                  tabindex="0"
                  @click="openImageViewer(generatedImageSrc(result), `${result.resultType}-${result.itemIndex}.png`)"
                  @keydown.enter="openImageViewer(generatedImageSrc(result), `${result.resultType}-${result.itemIndex}.png`)"
                >
                  <img :src="generatedImageSrc(result)" :alt="`${result.resultType} ${result.itemIndex}`" />
                  <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(generatedImageSrc(result), `${result.resultType}-${result.itemIndex}.png`)">
                    <el-icon><Download /></el-icon>
                  </button>
                </div>
                <p v-if="result.imageUrl"><strong>图片地址：</strong>{{ result.imageUrl }}</p>
                <p v-if="result.revisedPrompt"><strong>修订提示词：</strong>{{ analysisPreview(result.revisedPrompt) }}</p>
                <p v-if="result.errorMessage" class="queue-error-text"><strong>错误：</strong>{{ result.errorMessage }}</p>
              </article>
            </div>
          </section>
        </div>

        <div class="dialog-actions">
          <el-button v-if="selectedQueueTask.status === 'FAILED'" type="warning" @click="retryQueuedTask(selectedQueueTask)">
            重新生成
          </el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog
      v-model="fullTextDialogVisible"
      :title="fullTextDialogTitle"
      width="70vw"
      top="8vh"
      class="full-text-dialog"
      append-to-body
    >
      <pre>{{ fullTextDialogContent }}</pre>
    </el-dialog>

    <Teleport to="body">
      <div v-if="imageViewerVisible && imageViewerImage" class="image-viewer-mask" @click="closeImageViewer">
        <button class="image-viewer-close" type="button" title="关闭" @click.stop="closeImageViewer">
          <el-icon><Close /></el-icon>
        </button>
        <button class="image-viewer-rotate" type="button" title="旋转" @click.stop="rotateImageViewer">
          <el-icon><RefreshRight /></el-icon>
        </button>
        <div class="image-viewer-stage" @click.stop>
          <img
            :src="imageViewerImage.src"
            :alt="imageViewerImage.name"
            :style="{ transform: `rotate(${imageViewerRotation}deg)` }"
          />
        </div>
        <button
          class="image-viewer-download"
          type="button"
          title="下载图片"
          @click.stop="downloadImage(imageViewerImage.src, imageViewerImage.name)"
        >
          <el-icon><Download /></el-icon>
        </button>
      </div>
    </Teleport>
  </el-config-provider>
</template>
