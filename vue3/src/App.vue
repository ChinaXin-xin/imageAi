<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import {
  CircleCheck,
  Close,
  Delete,
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
  VideoPause,
  VideoPlay,
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadUserFile } from 'element-plus';
import appIcon from './assets/imageai-icon.png';
import AccessoryKitPicker from './components/AccessoryKitPicker.vue';
import UploadPreviewGrid from './components/UploadPreviewGrid.vue';
import { loadCodexQuotaAccounts } from './services/codexQuotaApi';
import { loadSystemOverview } from './services/systemApi';
import {
  analyzeUploadedImages,
  createExtraAccessory,
  createImageTask,
  createTargetTemplate,
  deleteExtraAccessory,
  deleteImageTask,
  deleteTargetTemplate,
  downloadTaskImages,
  editTaskResult,
  loadImageTask,
  loadImageTasks,
  loadDefaultPromptSettings,
  loadExtraAccessories,
  loadTargetTemplates,
  pauseImageTask,
  retryImageTask,
  resumeImageTask,
  saveDefaultPromptSettings,
} from './services/taskApi';
import type {
  CodexQuotaAccount,
  DashboardStats,
  DefaultPromptSettings,
  ExtraAccessory,
  ImageAssetUsage,
  ImageTaskDetail,
  ImageTaskKitSpec,
  ImageTaskPayload,
  ImageTaskResult,
  ImageTaskScene,
  ImageTaskSummary,
  SystemOverview,
  TargetTemplate,
  TargetTemplateType,
  UploadImageAnalysis,
} from './types/quota';

const DEFAULT_MAIN_PROMPT = `生成高转化电商主图。先严格还原上传实拍图中的手机膜、镜头膜和配件真实结构，再做电商视觉美化。
产品结构优先级高于风格：不得把异形镜头膜改成通用款，不得统一不同大小的孔位，不得增加或删除开孔、镜圈、配件。
主图按 Amazon/TEMU 平台首图标准：不加文字、图标、角标、贴纸文案和水印；通过左右位置、俯拍/斜拍角度、手机颜色、背景光影、轮廓光和轻微 3D 层次做差异化，避免重复铺货感。
钢化膜轮廓可以加清晰高光和玻璃边缘光效，提高高清晰度、立体感和科技感；风格只服务于真实产品展示，不遮挡、不改变产品结构。`;
const DEFAULT_INTRO_PROMPT =
  '生成产品介绍图：围绕高清、防指纹、抗摔、防窥、易安装、镜头保护等卖点进行模块化展示，信息层级清晰，适合详情页。';
const DEFAULT_ANALYSIS_PROMPT = `请客观深析上传图片，重点输出后续生图必须锁定的真实产品结构，不要编造看不见的信息。

如果图片中包含手机膜、镜头膜、保护壳或电子配件，必须逐项描述：
1. 产品类型、外轮廓、边缘形状、缺口、倒角和厚度感；
2. 开孔/孔位数量、相对位置、排列方向、每个孔的相对大小；
3. 哪些孔位大小不一致、哪些结构是非对称或异形结构；
4. 材质、颜色、透明度、反光、高光、表面纹理；
5. 仅在图片可见时描述手机膜相关清洁/安装辅助配件；输出它们的真实形状、颜色、材质、数量、相对尺寸和是否有可见文字，不要套用固定配件外观；
6. 生图时必须禁止模型改成通用款、标准款或常见款的关键细节；
7. 如果是镜头膜，必须明确输出大孔数量、小孔数量、左右/上下位置、每个小孔的相对大小顺序，以及是否禁止生成成等大孔；
8. 如果是任意型号镜头膜，必须先识别手机品牌/型号线索，再按上传图写清所有大孔/小孔的数量、左右/上下位置、大小关系和非对称结构；不要套用其他手机型号的常见孔位；
9. 如果出现清洁/安装辅助配件，必须识别真实外形、颜色、尺寸比例和是否有文字；若有文字，必须抄出参考图可见文字，后续生图只能复现参考图文字，不要生成无字替代品或凭空文字；
10. 客户产品范围只有手机、钢化膜、高清膜、防窥膜、镜头膜及已上传/已选择的手机膜相关清洁安装配件；不要推断包装盒、收纳袋、卡片、托盘、支架、底座或其他赠品。

输出请按“图片1、图片2...”分别描述，最后增加“结构锁定要点”小节，用简短明确的生成约束总结孔位、外形和数量。`;
const DEFAULT_TARGET_TEMPLATE_PROMPT = `请作为跨境电商图片视觉风格分析师，只分析这张参考风格图的视觉风格，不要照抄产品内容。

请输出适合后续生图使用的中文风格说明，重点包含：
1. 画面构图和主体摆放方式
2. 背景材质、颜色氛围和空间层次
3. 光影、高光、反射、阴影、3D质感
4. 文字/图标/信息模块的排版风格，如果没有就说明无明显文案
5. 生成时需要保持的风格约束

只描述风格和画面语言，不要要求生成模板里的具体商品，不要编造品牌。`;
const DEFAULT_SELLING_POINTS = ['高清透亮', '9H硬度', '防指纹', '全屏覆盖', '易安装', '镜头保护'];
const TASK_DRAFT_CACHE_KEY = 'imageai:add-task-draft:v1';
const DEFAULT_IMAGE_SIZE = 1536;
const IMAGE_SIZE_STEP = 16;

type UploadGroup = '实拍图' | '排版图' | '壁纸图';
type AnalysisUploadGroup = '实拍图';
type ActivePage = 'quota' | 'task' | 'queue' | 'templates' | 'settings';

type ViewerImage = {
  src: string;
  name: string;
};

type ViewerNaturalSize = {
  width: number;
  height: number;
};

type ViewerViewport = {
  width: number;
  height: number;
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
  targetTemplatePrompt: DEFAULT_TARGET_TEMPLATE_PROMPT,
  customSellingPoints: DEFAULT_SELLING_POINTS,
});
const settingsLoading = ref(false);
const settingsSaving = ref(false);
const realPhotoFiles = ref<UploadUserFile[]>([]);
const templateFiles = ref<UploadUserFile[]>([]);
const wallpaperFiles = ref<UploadUserFile[]>([]);
const uploadAnalysis = ref<Record<AnalysisUploadGroup, UploadImageAnalysis | null>>({
  实拍图: null,
});
const analysisLoading = ref<Record<AnalysisUploadGroup, boolean>>({
  实拍图: false,
});
const previewUrls = ref<Record<string, string>>({});
const newDefaultSellingPoint = ref('');
const taskQueue = ref<ImageTaskSummary[]>([]);
const selectedQueueTask = ref<ImageTaskDetail | null>(null);
const queueDetailVisible = ref(false);
const selectedDownloadTaskIds = ref<string[]>([]);
const taskZipDownloading = ref(false);
const resultEditDialogVisible = ref(false);
const resultEditSubmitting = ref(false);
const editingResult = ref<ImageTaskResult | null>(null);
const resultEditSuggestion = ref('');
const targetTemplates = ref<TargetTemplate[]>([]);
const targetTemplatesLoading = ref(false);
const targetTemplateErrorMessage = ref('');
const extraAccessories = ref<ExtraAccessory[]>([]);
const extraAccessoriesLoading = ref(false);
const extraAccessoryName = ref('');
const extraAccessoryFiles = ref<UploadUserFile[]>([]);
const extraAccessoryUploading = ref(false);
const targetTemplateUploading = ref<Record<TargetTemplateType, boolean>>({
  MAIN: false,
  INTRO: false,
});
const mainTargetTemplateFiles = ref<UploadUserFile[]>([]);
const introTargetTemplateFiles = ref<UploadUserFile[]>([]);
const targetTemplateNames = ref<Record<TargetTemplateType, string>>({
  MAIN: '',
  INTRO: '',
});
const fullTextDialogVisible = ref(false);
const fullTextDialogTitle = ref('');
const fullTextDialogContent = ref('');
const queueLoading = ref(false);
const queueErrorMessage = ref('');
const addingTask = ref(false);
const imageViewerVisible = ref(false);
const imageViewerImage = ref<ViewerImage | null>(null);
const imageViewerRotation = ref(0);
const imageViewerNaturalSize = ref<ViewerNaturalSize>({ width: 1, height: 1 });
const imageViewerViewport = ref<ViewerViewport>({ width: window.innerWidth, height: window.innerHeight });
let queueRefreshTimer: number | undefined;
let taskQueueRequest: Promise<void> | null = null;

const platforms = ['Amazon', 'TEMU', 'TikTok Shop', '自定义'];
const ratioOptions = ['1536:1536', '1024:1024', '960:640', '自定义'];
const phoneColors = ['自动', '黑色', '白色', '银色', '金色', '蓝色', '紫色', '绿色', '自定义'];
const styleOptions = ['自动', '科技感', '极简风', '简洁品牌风', '3D立体', '高级电商', 'TEMU爆款'];
const layoutOptions = ['自动', '居中展示', '左图右文', '右图左文', '产品矩阵', '场景渲染'];
const languageOptions = ['中文', '英文', '中英双语'];
const uploadGroups: UploadGroup[] = ['实拍图', '排版图', '壁纸图'];
const backendAnalysisGroups: AnalysisUploadGroup[] = ['实拍图'];
const targetTemplateTypes: TargetTemplateType[] = ['MAIN', 'INTRO'];

function defaultAssetUsages(): ImageAssetUsage[] {
  return ['MAIN', 'INTRO'];
}

const imageViewerIsSideways = computed(() => imageViewerRotation.value % 180 !== 0);
const imageViewerImageStyle = computed(() => {
  const naturalWidth = Math.max(1, imageViewerNaturalSize.value.width);
  const naturalHeight = Math.max(1, imageViewerNaturalSize.value.height);
  const sourceAspect = naturalWidth / naturalHeight;
  const visualAspect = imageViewerIsSideways.value ? 1 / sourceAspect : sourceAspect;
  const maxWidth = Math.min(imageViewerViewport.value.width * 0.88, 1320);
  const maxHeight = Math.min(imageViewerViewport.value.height * 0.82, 820);
  let visualWidth = maxWidth;
  let visualHeight = visualWidth / visualAspect;

  if (visualHeight > maxHeight) {
    visualHeight = maxHeight;
    visualWidth = visualHeight * visualAspect;
  }

  const elementWidth = imageViewerIsSideways.value ? visualHeight : visualWidth;
  const elementHeight = imageViewerIsSideways.value ? visualWidth : visualHeight;

  return {
    width: `${Math.max(1, Math.round(elementWidth))}px`,
    height: `${Math.max(1, Math.round(elementHeight))}px`,
    transform: `rotate(${imageViewerRotation.value}deg)`,
  };
});

const taskForm = ref({
  productName: '',
  model: '',
  platform: 'Amazon',
  ratio: '1536:1536',
  customWidth: DEFAULT_IMAGE_SIZE,
  customHeight: DEFAULT_IMAGE_SIZE,
  phoneColor: '自动',
  customColor: '#2563eb',
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
  mainTargetTemplateId: null as number | null,
  introTargetTemplateId: null as number | null,
  templateUsages: defaultAssetUsages(),
  wallpaperUsages: defaultAssetUsages(),
});

const kitSpecs = ref<ImageTaskKitSpec[]>([]);

type TaskDraftCache = {
  taskForm?: Partial<typeof taskForm.value>;
  kitSpecs?: ImageTaskKitSpec[];
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
  loadTargetTemplateList(false);
  loadExtraAccessoryList(false);
  window.addEventListener('keydown', handleImageViewerKeydown, true);
  window.addEventListener('resize', updateImageViewerViewport);
  queueRefreshTimer = window.setInterval(() => {
    if (!taskQueueRequest && (activePage.value === 'queue' || taskQueue.value.some((task) => isRunningTask(task.status)))) {
      loadTaskQueue(false);
    }
  }, 8000);
});

onUnmounted(() => {
  if (queueRefreshTimer) {
    window.clearInterval(queueRefreshTimer);
  }
  window.removeEventListener('keydown', handleImageViewerKeydown, true);
  window.removeEventListener('resize', updateImageViewerViewport);
});

watch(
  [taskForm, kitSpecs, activePage, isCollapsed],
  () => {
    saveTaskDraft();
  },
  { deep: true },
);

watch(
  () => taskForm.value.mainImageCount,
  (count) => {
    if (count <= 0) {
      taskForm.value.mainTargetTemplateId = null;
    }
  },
);

watch(
  () => taskForm.value.introImageCount,
  (count) => {
    if (count <= 0) {
      taskForm.value.introTargetTemplateId = null;
    }
  },
);

watch(
  () => templateFiles.value.length,
  (count, previousCount) => {
    if (count > 0 && previousCount === 0) {
      taskForm.value.templateUsages = defaultAssetUsages();
    }
  },
);

watch(
  () => wallpaperFiles.value.length,
  (count, previousCount) => {
    if (count > 0 && previousCount === 0) {
      taskForm.value.wallpaperUsages = defaultAssetUsages();
    }
  },
);

function restoreTaskDraft() {
  try {
    const rawDraft = window.localStorage.getItem(TASK_DRAFT_CACHE_KEY);
    if (!rawDraft) return;
    const draft = JSON.parse(rawDraft) as TaskDraftCache;
    if (draft.activePage && isActivePage(draft.activePage)) {
      activePage.value = draft.activePage;
    }
    if (typeof draft.isCollapsed === 'boolean') {
      isCollapsed.value = draft.isCollapsed;
    }
  } catch (error) {
    console.warn('恢复添加任务草稿失败', error);
    window.localStorage.removeItem(TASK_DRAFT_CACHE_KEY);
  }
}

function saveTaskDraft() {
  try {
    const draft: TaskDraftCache = {
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
  return ['quota', 'task', 'queue', 'templates', 'settings'].includes(value);
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

function randomTargetTemplateName(type: TargetTemplateType): string {
  return `${type === 'MAIN' ? '主图' : '介绍图'}参考风格-${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
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

async function loadPromptSettings() {
  settingsLoading.value = true;
  try {
    const settings = await loadDefaultPromptSettings();
    const normalizedSettings = {
      ...settings,
      mainPrompt: settings.mainPrompt?.trim() || DEFAULT_MAIN_PROMPT,
      introPrompt: settings.introPrompt?.trim() || DEFAULT_INTRO_PROMPT,
      analysisPrompt: settings.analysisPrompt?.trim() || DEFAULT_ANALYSIS_PROMPT,
      targetTemplatePrompt: settings.targetTemplatePrompt?.trim() || DEFAULT_TARGET_TEMPLATE_PROMPT,
      customSellingPoints: settings.customSellingPoints?.length ? settings.customSellingPoints : DEFAULT_SELLING_POINTS,
    };
    defaultSettings.value = normalizedSettings;
    taskForm.value.mainPrompt = normalizedSettings.mainPrompt;
    taskForm.value.introPrompt = normalizedSettings.introPrompt;
    if (normalizedSettings.customSellingPoints.length > 0) {
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
      targetTemplatePrompt: settings.targetTemplatePrompt?.trim() || DEFAULT_TARGET_TEMPLATE_PROMPT,
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

function uploadFilesFor(): File[] {
  return realPhotoFiles.value.flatMap((file) => (file.raw ? [file.raw as unknown as File] : []));
}

function normalizeCustomImageSize() {
  taskForm.value.ratio = '自定义';
  normalizeTaskImageSize();
}

async function analyzeUploadImage(type: AnalysisUploadGroup) {
  const files = uploadFilesFor();
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
  if (type === '排版图') return templateFiles;
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
  if (realPhotoFiles.value.length + templateFiles.value.length === 0) {
    resetUploadAnalysis('实拍图');
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
  templateFiles.value = [];
  wallpaperFiles.value = [];
  uploadAnalysis.value = {
    实拍图: null,
  };
  analysisLoading.value = {
    实拍图: false,
  };
}

function autoRecognizeModel() {
  if (!taskForm.value.model.trim()) {
    taskForm.value.model = '自动识别参考图';
  }
  ElMessage.success('已启用机型自动识别。');
}

function autoRecognizeKitSpecs() {
  if (!extraAccessories.value.length) {
    ElMessage.warning('请先在参考风格图页下方添加额外配件。');
    return;
  }
  kitSpecs.value = extraAccessories.value.map((item) => ({
    accessoryId: item.id,
    name: item.name,
    quantity: 1,
  }));
  ElMessage.success('已从额外配件库带入套餐规格。');
}

async function addToTaskQueue() {
  ensureProductName();
  normalizeTaskImageSize();
  addingTask.value = true;
  activePage.value = 'queue';
  queueLoading.value = true;
  try {
    const createdTask = await createImageTask(snapshotTaskForm(), {
      realPhotoFiles: rawUploadFiles(realPhotoFiles.value),
      templateFiles: rawUploadFiles(templateFiles.value),
      wallpaperFiles: rawUploadFiles(wallpaperFiles.value),
    });
    selectedQueueTask.value = null;
    taskQueue.value = [createdTask, ...taskQueue.value.filter((task) => task.id !== createdTask.id)];
    ElMessage.success(`任务已提交到后端队列：${createdTask.productName}`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    queueLoading.value = false;
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
    mainTargetTemplateId: taskForm.value.mainTargetTemplateId,
    introTargetTemplateId: taskForm.value.introTargetTemplateId,
    templateUsages: [...taskForm.value.templateUsages],
    wallpaperUsages: [...taskForm.value.wallpaperUsages],
    kitSpecs: kitSpecs.value.map((item) => ({ ...item })),
  };
}

function rawUploadFiles(files: UploadUserFile[]): File[] {
  return files.flatMap((file) => (file.raw ? [file.raw as unknown as File] : []));
}

async function loadTaskQueue(showLoading = true) {
  if (taskQueueRequest) {
    return taskQueueRequest;
  }
  if (showLoading) {
    queueLoading.value = true;
  }
  const request = (async () => {
    queueErrorMessage.value = '';
    try {
      taskQueue.value = await loadImageTasks();
      selectedDownloadTaskIds.value = selectedDownloadTaskIds.value.filter((taskId) =>
        taskQueue.value.some((task) => task.id === taskId && task.status === 'COMPLETED'),
      );
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
  })();
  taskQueueRequest = request;
  try {
    await request;
  } finally {
    if (taskQueueRequest === request) {
      taskQueueRequest = null;
    }
  }
}

async function loadTargetTemplateList(showLoading = true) {
  if (showLoading) {
    targetTemplatesLoading.value = true;
  }
  targetTemplateErrorMessage.value = '';
  try {
    targetTemplates.value = await loadTargetTemplates();
  } catch (error) {
    targetTemplateErrorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    if (showLoading) {
      targetTemplatesLoading.value = false;
    }
  }
}

async function loadExtraAccessoryList(showLoading = true) {
  if (showLoading) {
    extraAccessoriesLoading.value = true;
  }
  targetTemplateErrorMessage.value = '';
  try {
    extraAccessories.value = await loadExtraAccessories();
  } catch (error) {
    targetTemplateErrorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    if (showLoading) {
      extraAccessoriesLoading.value = false;
    }
  }
}

function targetTemplatesByType(type: TargetTemplateType): TargetTemplate[] {
  return targetTemplates.value.filter((template) => template.templateType === type);
}

function selectedTargetTemplate(type: TargetTemplateType): TargetTemplate | null {
  const id = type === 'MAIN' ? taskForm.value.mainTargetTemplateId : taskForm.value.introTargetTemplateId;
  return targetTemplates.value.find((template) => template.id === id && template.templateType === type) ?? null;
}

function targetTemplateDisabledReason(type: TargetTemplateType): string {
  if (type === 'MAIN' && taskForm.value.mainImageCount <= 0) {
    return '主图数量大于 0 后才能选择主图参考风格图';
  }
  if (type === 'INTRO' && taskForm.value.introImageCount <= 0) {
    return '介绍图数量大于 0 后才能选择介绍图参考风格图';
  }
  return '';
}

function handleTargetTemplateSelectVisible(visible: boolean) {
  if (visible) {
    loadTargetTemplateList(false);
  }
}

function handleExtraAccessorySelectVisible(visible: boolean) {
  if (visible) {
    loadExtraAccessoryList(false);
  }
}

function targetTemplateUploadFiles(type: TargetTemplateType) {
  return type === 'MAIN' ? mainTargetTemplateFiles : introTargetTemplateFiles;
}

function clearTargetTemplateUpload(type: TargetTemplateType) {
  const files = targetTemplateUploadFiles(type);
  files.value.forEach(handleUploadRemove);
  files.value = [];
}

async function addTargetTemplate(type: TargetTemplateType) {
  const files = targetTemplateUploadFiles(type).value;
  const rawFile = files.find((file) => file.raw)?.raw as File | undefined;
  if (!rawFile) {
    ElMessage.warning(`请先上传${type === 'MAIN' ? '主图' : '介绍图'}参考风格图。`);
    return;
  }
  targetTemplateUploading.value[type] = true;
  try {
    const created = await createTargetTemplate(type, rawFile, targetTemplateNames.value[type] || randomTargetTemplateName(type));
    await loadTargetTemplateList(false);
    if (type === 'MAIN') {
      taskForm.value.mainTargetTemplateId = created.id;
      clearTargetTemplateUpload('MAIN');
    } else {
      taskForm.value.introTargetTemplateId = created.id;
      clearTargetTemplateUpload('INTRO');
    }
    targetTemplateNames.value[type] = '';
    ElMessage.success(`${created.templateTypeText}参考风格图已分析并保存。`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    targetTemplateUploading.value[type] = false;
  }
}

async function removeTargetTemplate(template: TargetTemplate) {
  try {
    await ElMessageBox.confirm(
      `确定删除参考风格图「${template.name}」吗？已经创建的历史任务不会被删除。`,
      '删除参考风格图',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
    await deleteTargetTemplate(template.id);
    if (taskForm.value.mainTargetTemplateId === template.id) {
      taskForm.value.mainTargetTemplateId = null;
    }
    if (taskForm.value.introTargetTemplateId === template.id) {
      taskForm.value.introTargetTemplateId = null;
    }
    await loadTargetTemplateList(false);
    ElMessage.success('参考风格图已删除。');
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(error instanceof Error ? error.message : String(error));
  }
}

function clearExtraAccessoryUpload() {
  extraAccessoryFiles.value.forEach(handleUploadRemove);
  extraAccessoryFiles.value = [];
}

async function addExtraAccessory() {
  const rawFile = extraAccessoryFiles.value.find((file) => file.raw)?.raw as File | undefined;
  if (!rawFile) {
    ElMessage.warning('请先上传额外配件图片。');
    return;
  }
  extraAccessoryUploading.value = true;
  try {
    const created = await createExtraAccessory(rawFile, extraAccessoryName.value || rawFile.name);
    await loadExtraAccessoryList(false);
    extraAccessoryName.value = '';
    clearExtraAccessoryUpload();
    ElMessage.success(`额外配件「${created.name}」已保存。`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    extraAccessoryUploading.value = false;
  }
}

async function removeExtraAccessory(accessory: ExtraAccessory) {
  try {
    await ElMessageBox.confirm(
      `确定删除额外配件「${accessory.name}」吗？已经创建的历史任务不会被删除。`,
      '删除额外配件',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
    await deleteExtraAccessory(accessory.id);
    kitSpecs.value = kitSpecs.value.filter((item) => item.accessoryId !== accessory.id && item.name !== accessory.name);
    await loadExtraAccessoryList(false);
    ElMessage.success('额外配件已删除。');
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(error instanceof Error ? error.message : String(error));
  }
}

async function deleteQueuedTask(task: ImageTaskSummary | ImageTaskDetail) {
  try {
    await ElMessageBox.confirm(
      `确定删除任务「${task.productName}」吗？任务记录、上传图和生成结果都会删除。`,
      '删除任务',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
    await deleteImageTask(task.id);
    if (selectedQueueTask.value?.id === task.id) {
      selectedQueueTask.value = null;
      queueDetailVisible.value = false;
    }
    await loadTaskQueue(false);
    ElMessage.success('任务已删除。');
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(error instanceof Error ? error.message : String(error));
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

async function pauseQueuedTask(task: ImageTaskSummary | ImageTaskDetail) {
  try {
    await ElMessageBox.confirm(
      `确定暂停任务「${task.productName}」吗？暂停后不会继续请求后端，点击继续会重新生成。`,
      '暂停任务',
      {
        confirmButtonText: '确定暂停',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
    selectedQueueTask.value = await pauseImageTask(task.id);
    await loadTaskQueue(false);
    ElMessage.success('任务已暂停。');
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(error instanceof Error ? error.message : String(error));
  }
}

async function resumeQueuedTask(task: ImageTaskSummary | ImageTaskDetail) {
  try {
    await ElMessageBox.confirm(
      `确定继续任务「${task.productName}」吗？继续后会重新深析并重新生成图片。`,
      '继续任务',
      {
        confirmButtonText: '确定继续',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
    selectedQueueTask.value = await resumeImageTask(task.id);
    await loadTaskQueue(false);
    ElMessage.success('任务已重新加入队列。');
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(error instanceof Error ? error.message : String(error));
  }
}

function toggleDownloadTask(task: ImageTaskSummary, checked: boolean) {
  if (task.status !== 'COMPLETED') {
    return;
  }
  if (checked) {
    if (!selectedDownloadTaskIds.value.includes(task.id)) {
      selectedDownloadTaskIds.value = [...selectedDownloadTaskIds.value, task.id];
    }
  } else {
    selectedDownloadTaskIds.value = selectedDownloadTaskIds.value.filter((taskId) => taskId !== task.id);
  }
}

function handleDownloadTaskChange(task: ImageTaskSummary, checked: unknown) {
  toggleDownloadTask(task, Boolean(checked));
}

async function downloadTaskZip(task: ImageTaskSummary | ImageTaskDetail) {
  await downloadTaskZipByIds([task.id]);
}

async function downloadSelectedTaskZip() {
  await downloadTaskZipByIds(selectedDownloadTaskIds.value);
}

async function downloadTaskZipByIds(taskIds: string[]) {
  if (!taskIds.length) {
    ElMessage.warning('请先勾选已完成任务');
    return;
  }
  taskZipDownloading.value = true;
  try {
    const file = await downloadTaskImages(taskIds);
    saveBlob(file.blob, file.fileName);
    ElMessage.success('图片压缩包已开始下载');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    taskZipDownloading.value = false;
  }
}

function openResultEditDialog(result: ImageTaskResult) {
  editingResult.value = result;
  resultEditSuggestion.value = '';
  resultEditDialogVisible.value = true;
}

async function submitResultEdit() {
  if (!selectedQueueTask.value || !editingResult.value) {
    return;
  }
  const suggestion = resultEditSuggestion.value.trim();
  if (!suggestion) {
    ElMessage.warning('请先输入客户对这张图的修改建议');
    return;
  }
  const taskId = selectedQueueTask.value.id;
  const resultId = editingResult.value.id;
  resultEditDialogVisible.value = false;
  editingResult.value = null;
  resultEditSuggestion.value = '';
  ElMessage.info('已提交后台重修，完成后会出现在历史版本里。');
  resultEditSubmitting.value = true;
  try {
    selectedQueueTask.value = await editTaskResult(taskId, resultId, suggestion);
    await loadTaskQueue(false);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    resultEditSubmitting.value = false;
  }
}

function isRunningTask(status: string): boolean {
  return ['QUEUED', 'ANALYZING', 'GENERATING'].includes(status);
}

function taskStatusTagType(status: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'COMPLETED') return 'success';
  if (status === 'FAILED') return 'danger';
  if (status === 'PAUSED') return 'info';
  if (status === 'GENERATING' || status === 'ANALYZING') return 'warning';
  return 'info';
}

function taskProgress(task: ImageTaskSummary | ImageTaskDetail): number {
  if (!task.totalCount) return isRunningTask(task.status) ? 5 : task.status === 'COMPLETED' ? 100 : 0;
  return Math.min(100, Math.max(0, Math.round((task.completedCount / task.totalCount) * 100)));
}

function fileCount(task: ImageTaskSummary | ImageTaskDetail, type: UploadGroup): number {
  return task.fileSummary?.[type] ?? 0;
}

function uploadGroupDisplayName(type: UploadGroup): string {
  return type;
}

function analysisPreview(value: string | null | undefined): string {
  if (!value) return '暂无';
  return value.length > 300 ? `${value.slice(0, 300)}...` : value;
}

function isLongText(value: string | null | undefined): boolean {
  return Boolean(value && value.length > 300);
}

function sceneFinalPrompt(imageType: '主图' | '介绍图', scene: ImageTaskScene): string {
  const task = selectedQueueTask.value;
  if (!task) return scene.prompt;
  const finalPrompt = imageType === '主图' ? task.finalMainPrompt : task.finalIntroPrompt;
  if (!finalPrompt) return scene.prompt;
  return `${finalPrompt}

【本张图片场景规划】
场景标题：${scene.sceneTitle || `场景${scene.index}`}
场景描述：${scene.prompt}
只允许改变构图、背景、光影、展示角度或卖点表达，不得改变上传图产品结构、孔位、配件数量和套装规格；如果场景与排版图版式、手机完整入画或产品比例约束冲突，必须按排版图和比例约束修正。`;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function inlineMarkdown(value: string): string {
  return escapeHtml(value)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.+?)`/g, '<code>$1</code>');
}

function markdownToHtml(value: string): string {
  const lines = value.replace(/\r\n/g, '\n').split('\n');
  const html: string[] = [];
  let inList = false;

  const closeList = () => {
    if (inList) {
      html.push('</ul>');
      inList = false;
    }
  };

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      closeList();
      html.push('<br>');
      continue;
    }
    const heading = trimmed.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      closeList();
      const level = Math.min(4, heading[1].length + 1);
      html.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }
    const listItem = trimmed.match(/^[-*]\s+(.+)$/);
    if (listItem) {
      if (!inList) {
        html.push('<ul>');
        inList = true;
      }
      html.push(`<li>${inlineMarkdown(listItem[1])}</li>`);
      continue;
    }
    closeList();
    html.push(`<p>${inlineMarkdown(trimmed)}</p>`);
  }
  closeList();
  return html.join('');
}

function openFullTextDialog(title: string, content: string | null | undefined) {
  if (!content) return;
  fullTextDialogTitle.value = title;
  fullTextDialogContent.value = content;
  fullTextDialogVisible.value = true;
}

function updateImageViewerViewport() {
  imageViewerViewport.value = {
    width: window.innerWidth,
    height: window.innerHeight,
  };
}

function loadImageViewerNaturalSize(src: string) {
  imageViewerNaturalSize.value = { width: 1, height: 1 };
  const probe = new Image();
  probe.onload = () => {
    if (imageViewerImage.value?.src !== src) return;
    imageViewerNaturalSize.value = {
      width: probe.naturalWidth || 1,
      height: probe.naturalHeight || 1,
    };
  };
  probe.onerror = () => {
    if (imageViewerImage.value?.src === src) {
      imageViewerNaturalSize.value = { width: 1, height: 1 };
    }
  };
  probe.src = src;
}

function openImageViewer(src: string | null | undefined, name: string | null | undefined) {
  if (!src) return;
  updateImageViewerViewport();
  imageViewerImage.value = {
    src,
    name: name?.trim() || 'image.png',
  };
  imageViewerRotation.value = 0;
  loadImageViewerNaturalSize(src);
  imageViewerVisible.value = true;
}

function closeImageViewer() {
  imageViewerVisible.value = false;
  imageViewerImage.value = null;
  imageViewerRotation.value = 0;
  imageViewerNaturalSize.value = { width: 1, height: 1 };
}

function rotateImageViewer() {
  imageViewerRotation.value = (imageViewerRotation.value + 90) % 360;
}

function handleImageViewerKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && imageViewerVisible.value) {
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    closeImageViewer();
  }
}

function sanitizeDownloadName(name: string | null | undefined): string {
  const normalized = name?.trim().replace(/[\\/:*?"<>|]/g, '_') || 'image.png';
  return /\.[a-z0-9]{2,5}$/i.test(normalized) ? normalized : `${normalized}.png`;
}

function saveBlob(blob: Blob, name: string | null | undefined) {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = name?.trim() || 'image-task-results.zip';
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
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

function taskFileOriginalSrc(file: { original?: string | null; preview?: string | null }): string {
  return file.original || file.preview || '';
}

function visibleKitSpecs(task: ImageTaskDetail | null) {
  return task?.kitSpecs?.filter((item) => Number(item.quantity) > 0) ?? [];
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
    mainTargetTemplateId: null,
    introTargetTemplateId: null,
    templateUsages: defaultAssetUsages(),
    wallpaperUsages: defaultAssetUsages(),
  };
  if (defaultSettings.value.mainPrompt) {
    taskForm.value.mainPrompt = defaultSettings.value.mainPrompt;
  }
  if (defaultSettings.value.introPrompt) {
    taskForm.value.introPrompt = defaultSettings.value.introPrompt;
  }
  taskForm.value.sellingPoints = [...defaultSettings.value.customSellingPoints];
  kitSpecs.value = [];
  clearUploadedImagesAndAnalysis();
  ElMessage.success('任务参数已重置。');
}

function pageTitle(): string {
  if (activePage.value === 'quota') return 'ImageAI 额度监控';
  if (activePage.value === 'queue') return '任务队列';
  if (activePage.value === 'templates') return '参考风格图';
  if (activePage.value === 'settings') return '默认设置';
  return '创建新任务';
}

function pageEyebrow(): string {
  if (activePage.value === 'quota') return 'CLI Proxy API Management';
  if (activePage.value === 'queue') return 'ImageAI Queue';
  if (activePage.value === 'templates') return 'ImageAI Layout Templates';
  if (activePage.value === 'settings') return 'ImageAI Defaults';
  return 'ImageAI Task Center';
}

function pageSubtitle(): string {
  if (activePage.value === 'quota') return '集中查看账号额度、图片生成余量与服务器资源状态。';
  if (activePage.value === 'queue') return '按任务查看待生成图片的参数、素材缩略图和生图详情。';
  if (activePage.value === 'templates') return '维护主图和介绍图参考风格图，可命名上传后在任务里复用。';
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
            <div
              class="brand-image-wrap image-action-wrap"
              role="button"
              tabindex="0"
              @click="openImageViewer(appIcon, 'ImageAI.png')"
              @keydown.enter="openImageViewer(appIcon, 'ImageAI.png')"
            >
              <img class="brand-icon" :src="appIcon" alt="ImageAI" />
              <button class="image-download-button brand-download-button" type="button" title="下载图片" @click.stop="downloadImage(appIcon, 'ImageAI.png')">
                <el-icon><Download /></el-icon>
              </button>
            </div>
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
            :class="{ active: activePage === 'templates' }"
            type="button"
            @click="activePage = 'templates'; loadTargetTemplateList(false); loadExtraAccessoryList(false)"
          >
            <el-icon><CircleCheck /></el-icon>
            <span v-if="!isCollapsed">参考风格图</span>
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
          <div v-else-if="activePage === 'templates'" class="topbar-actions">
            <span class="refresh-time">{{ targetTemplates.length }} 个参考风格图 / {{ extraAccessories.length }} 个配件</span>
            <el-button :icon="Refresh" :loading="targetTemplatesLoading || extraAccessoriesLoading" @click="loadTargetTemplateList(); loadExtraAccessoryList()">
              刷新
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
                    <p>实拍图用于锁定产品结构，排版图用于填入图片版式。</p>
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
                  <UploadPreviewGrid
                    :files="realPhotoFiles"
                    :file-preview-url="filePreviewUrl"
                    :upload-key="uploadKey"
                    @preview="openImageViewer"
                    @download="downloadImage"
                    @remove="(file) => removeUploadFile('实拍图', file)"
                  />
                  <p
                    class="analysis-result-line"
                    :class="{ ready: uploadAnalysis['实拍图'] }"
                  >
                    {{ uploadAnalysis['实拍图']?.result || (analysisLoading['实拍图'] ? '正在深析上传图...' : '暂无深析结果') }}
                  </p>
                </div>

                <div class="upload-section">
                  <div class="section-title">
                    <span>排版图</span>
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
                    <div>上传排版图</div>
                  </el-upload>
                  <UploadPreviewGrid
                    :files="templateFiles"
                    :file-preview-url="filePreviewUrl"
                    :upload-key="uploadKey"
                    @preview="openImageViewer"
                    @download="downloadImage"
                    @remove="(file) => removeUploadFile('排版图', file)"
                  />
                  <div v-if="templateFiles.length" class="asset-usage-options">
                    <span>用于</span>
                    <el-checkbox-group v-model="taskForm.templateUsages" size="small">
                      <el-checkbox-button label="MAIN">主图</el-checkbox-button>
                      <el-checkbox-button label="INTRO">介绍图</el-checkbox-button>
                    </el-checkbox-group>
                  </div>
                </div>
              </section>

              <section class="task-card">
                <div class="task-card-head">
                  <div>
                    <h2>素材</h2>
                    <p>壁纸按上传图直接用于生成。</p>
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
                  <UploadPreviewGrid
                    :files="wallpaperFiles"
                    compact
                    :file-preview-url="filePreviewUrl"
                    :upload-key="uploadKey"
                    @preview="openImageViewer"
                    @download="downloadImage"
                    @remove="(file) => removeUploadFile('壁纸图', file)"
                  />
                  <div v-if="wallpaperFiles.length" class="asset-usage-options">
                    <span>用于</span>
                    <el-checkbox-group v-model="taskForm.wallpaperUsages" size="small">
                      <el-checkbox-button label="MAIN">主图</el-checkbox-button>
                      <el-checkbox-button label="INTRO">介绍图</el-checkbox-button>
                    </el-checkbox-group>
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
                    <p>从额外配件库选择配件后设置数量，数量最低为 1。</p>
                  </div>
                  <el-button size="small" text type="primary" @click="autoRecognizeKitSpecs">从配件库带入</el-button>
                </div>

                <AccessoryKitPicker
                  v-model="kitSpecs"
                  :accessories="extraAccessories"
                  @open-accessories="handleExtraAccessorySelectVisible"
                  @preview="openImageViewer"
                  @download="downloadImage"
                />

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

                <div class="target-template-selectors">
                  <div class="form-row no-margin">
                    <label>主图参考风格图</label>
                    <el-select
                      v-model="taskForm.mainTargetTemplateId"
                      clearable
                      filterable
                      :disabled="taskForm.mainImageCount <= 0"
                      :placeholder="targetTemplateDisabledReason('MAIN') || '选择已上传主图参考风格图'"
                      @visible-change="handleTargetTemplateSelectVisible"
                    >
                      <el-option
                        v-for="template in targetTemplatesByType('MAIN')"
                        :key="template.id"
                        :label="template.name"
                        :value="template.id"
                      >
                        <div class="template-option">
                          <img :src="template.preview" :alt="template.name" />
                          <div>
                            <strong>{{ template.name }}</strong>
                            <small>{{ analysisPreview(template.styleAnalysis) }}</small>
                          </div>
                        </div>
                      </el-option>
                    </el-select>
                    <p v-if="targetTemplateDisabledReason('MAIN')" class="field-hint">{{ targetTemplateDisabledReason('MAIN') }}</p>
                    <div v-if="selectedTargetTemplate('MAIN')" class="selected-template-card">
                      <div class="selected-template-image image-action-wrap" role="button" tabindex="0" @click="openImageViewer(selectedTargetTemplate('MAIN')?.preview, selectedTargetTemplate('MAIN')?.fileName)" @keydown.enter="openImageViewer(selectedTargetTemplate('MAIN')?.preview, selectedTargetTemplate('MAIN')?.fileName)">
                        <img :src="selectedTargetTemplate('MAIN')?.preview" :alt="selectedTargetTemplate('MAIN')?.name" />
                        <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(selectedTargetTemplate('MAIN')?.preview, selectedTargetTemplate('MAIN')?.fileName)">
                          <el-icon><Download /></el-icon>
                        </button>
                      </div>
                      <div>
                        <strong>{{ selectedTargetTemplate('MAIN')?.name }}</strong>
                        <p
                          class="clickable-text-preview"
                          role="button"
                          tabindex="0"
                          @click="openFullTextDialog('主图参考风格图风格', selectedTargetTemplate('MAIN')?.styleAnalysis)"
                          @keydown.enter="openFullTextDialog('主图参考风格图风格', selectedTargetTemplate('MAIN')?.styleAnalysis)"
                        >
                          {{ analysisPreview(selectedTargetTemplate('MAIN')?.styleAnalysis) }}
                        </p>
                      </div>
                    </div>
                  </div>
                  <div class="form-row no-margin">
                    <label>介绍图参考风格图</label>
                    <el-select
                      v-model="taskForm.introTargetTemplateId"
                      clearable
                      filterable
                      :disabled="taskForm.introImageCount <= 0"
                      :placeholder="targetTemplateDisabledReason('INTRO') || '选择已上传介绍图参考风格图'"
                      @visible-change="handleTargetTemplateSelectVisible"
                    >
                      <el-option
                        v-for="template in targetTemplatesByType('INTRO')"
                        :key="template.id"
                        :label="template.name"
                        :value="template.id"
                      >
                        <div class="template-option">
                          <img :src="template.preview" :alt="template.name" />
                          <div>
                            <strong>{{ template.name }}</strong>
                            <small>{{ analysisPreview(template.styleAnalysis) }}</small>
                          </div>
                        </div>
                      </el-option>
                    </el-select>
                    <p v-if="targetTemplateDisabledReason('INTRO')" class="field-hint">{{ targetTemplateDisabledReason('INTRO') }}</p>
                    <div v-if="selectedTargetTemplate('INTRO')" class="selected-template-card">
                      <div class="selected-template-image image-action-wrap" role="button" tabindex="0" @click="openImageViewer(selectedTargetTemplate('INTRO')?.preview, selectedTargetTemplate('INTRO')?.fileName)" @keydown.enter="openImageViewer(selectedTargetTemplate('INTRO')?.preview, selectedTargetTemplate('INTRO')?.fileName)">
                        <img :src="selectedTargetTemplate('INTRO')?.preview" :alt="selectedTargetTemplate('INTRO')?.name" />
                        <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(selectedTargetTemplate('INTRO')?.preview, selectedTargetTemplate('INTRO')?.fileName)">
                          <el-icon><Download /></el-icon>
                        </button>
                      </div>
                      <div>
                        <strong>{{ selectedTargetTemplate('INTRO')?.name }}</strong>
                        <p
                          class="clickable-text-preview"
                          role="button"
                          tabindex="0"
                          @click="openFullTextDialog('介绍图参考风格图风格', selectedTargetTemplate('INTRO')?.styleAnalysis)"
                          @keydown.enter="openFullTextDialog('介绍图参考风格图风格', selectedTargetTemplate('INTRO')?.styleAnalysis)"
                        >
                          {{ analysisPreview(selectedTargetTemplate('INTRO')?.styleAnalysis) }}
                        </p>
                      </div>
                    </div>
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
                  <p>后端会深析实拍图，并按排版图、参考风格图和素材用途提交最终生图提示词。</p>
                </div>
                <div class="queue-head-actions">
                  <span>{{ taskQueue.length }} 个任务</span>
                  <el-button
                    type="primary"
                    :icon="Download"
                    :loading="taskZipDownloading"
                    :disabled="selectedDownloadTaskIds.length === 0"
                    @click="downloadSelectedTaskZip"
                  >
                    下载选中结果
                  </el-button>
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
                  <el-checkbox
                    class="queue-select"
                    :model-value="selectedDownloadTaskIds.includes(task.id)"
                    :disabled="task.status !== 'COMPLETED'"
                    @change="handleDownloadTaskChange(task, $event)"
                    @click.stop
                  />
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
                      <span>排版图 {{ fileCount(task, '排版图') }}</span>
                      <span>主图 {{ task.form.mainImageCount }}</span>
                      <span>介绍图 {{ task.form.introImageCount }}</span>
                      <span>进度 {{ task.completedCount }} / {{ task.totalCount }}</span>
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
                    <div class="queue-side-actions">
                      <el-button text type="primary" @click="openQueueDetail(task)">查看详情</el-button>
                      <el-button
                        v-if="isRunningTask(task.status)"
                        text
                        type="warning"
                        :icon="VideoPause"
                        @click="pauseQueuedTask(task)"
                      >
                        暂停
                      </el-button>
                      <el-button
                        v-else-if="task.status === 'PAUSED'"
                        text
                        type="success"
                        :icon="VideoPlay"
                        @click="resumeQueuedTask(task)"
                      >
                        继续
                      </el-button>
                      <el-button v-else-if="task.status === 'FAILED'" text type="warning" @click="retryQueuedTask(task)">重试</el-button>
                      <el-button
                        v-if="task.status === 'COMPLETED'"
                        text
                        type="primary"
                        :icon="Download"
                        :loading="taskZipDownloading"
                        @click="downloadTaskZip(task)"
                      >
                        下载结果
                      </el-button>
                      <el-button text type="danger" :icon="Delete" @click="deleteQueuedTask(task)">删除</el-button>
                    </div>
                  </div>
                </article>
              </div>
            </section>
          </section>

          <section v-else-if="activePage === 'templates'" class="templates-page" v-loading="targetTemplatesLoading">
            <el-alert
              v-if="targetTemplateErrorMessage"
              class="queue-error"
              type="error"
              :title="targetTemplateErrorMessage"
              show-icon
              :closable="false"
            />

            <section class="template-columns">
              <article v-for="type in targetTemplateTypes" :key="type" class="template-panel">
                <div class="task-card-head">
                  <div>
                    <h2>{{ type === 'MAIN' ? '主图参考风格图' : '介绍图参考风格图' }}</h2>
                    <p>上传参考风格图后，后端会自动调用 GPT 5.5 分析并保存风格。</p>
                  </div>
                </div>

                <div class="form-row">
                  <label>模板名称</label>
                  <el-input
                    v-model="targetTemplateNames[type]"
                    :placeholder="type === 'MAIN' ? '例如：深色科技主图风格' : '例如：模块化介绍图风格'"
                  />
                </div>

                <el-upload
                  v-model:file-list="targetTemplateUploadFiles(type).value"
                  class="compact-upload"
                  action="#"
                  drag
                  :limit="1"
                  :auto-upload="false"
                  :show-file-list="false"
                >
                  <el-icon><Upload /></el-icon>
                  <div>拖拽或点击上传参考风格图</div>
                </el-upload>

                <UploadPreviewGrid
                  :files="targetTemplateUploadFiles(type).value"
                  compact
                  :file-preview-url="filePreviewUrl"
                  :upload-key="uploadKey"
                  @preview="openImageViewer"
                  @download="downloadImage"
                  @remove="() => clearTargetTemplateUpload(type)"
                />

                <el-button
                  class="template-add-button"
                  type="primary"
                  :icon="Plus"
                  :loading="targetTemplateUploading[type]"
                  @click="addTargetTemplate(type)"
                >
                  添加并深析参考风格图
                </el-button>

                <div v-if="targetTemplatesByType(type).length" class="target-template-list">
                  <article v-for="template in targetTemplatesByType(type)" :key="template.id" class="target-template-card">
                    <div
                      class="target-template-preview image-action-wrap"
                      role="button"
                      tabindex="0"
                      @click="openImageViewer(template.preview, template.fileName)"
                      @keydown.enter="openImageViewer(template.preview, template.fileName)"
                    >
                      <img :src="template.preview" :alt="template.name" />
                      <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(template.preview, template.fileName)">
                        <el-icon><Download /></el-icon>
                      </button>
                    </div>
                    <div class="target-template-body">
                      <div class="target-template-head">
                        <strong>{{ template.name }}</strong>
                        <el-button text type="danger" :icon="Delete" @click="removeTargetTemplate(template)">删除</el-button>
                      </div>
                      <small>{{ template.model }} / {{ template.createdAt }}</small>
                      <p
                        class="clickable-text-preview"
                        role="button"
                        tabindex="0"
                        @click="openFullTextDialog(`${template.templateTypeText}参考风格图风格`, template.styleAnalysis)"
                        @keydown.enter="openFullTextDialog(`${template.templateTypeText}参考风格图风格`, template.styleAnalysis)"
                      >
                        {{ analysisPreview(template.styleAnalysis) }}
                      </p>
                      <el-button
                        class="text-more-button"
                        size="small"
                        text
                        type="primary"
                        @click="openFullTextDialog(`${template.templateTypeText}参考风格图风格`, template.styleAnalysis)"
                      >
                        查看全文
                      </el-button>
                    </div>
                  </article>
                </div>
                <el-empty v-else description="暂无参考风格图" />
              </article>
            </section>

            <section class="template-panel accessory-panel" v-loading="extraAccessoriesLoading">
              <div class="task-card-head">
                <div>
                  <h2>额外配件</h2>
                  <p>维护套餐规格中可选择的配件，添加任务时从这里选择配件并设置数量。</p>
                </div>
              </div>

              <div class="accessory-form">
                <div class="form-row">
                  <label>配件名称</label>
                  <el-input
                    v-model="extraAccessoryName"
                    placeholder="例如：钢化膜、镜头膜、清洁包"
                  />
                </div>
                <div>
                  <el-upload
                    v-model:file-list="extraAccessoryFiles"
                    class="compact-upload"
                    action="#"
                    drag
                    :limit="1"
                    :auto-upload="false"
                    :show-file-list="false"
                  >
                    <el-icon><Upload /></el-icon>
                    <div>拖拽或点击上传配件图</div>
                  </el-upload>
                  <UploadPreviewGrid
                    :files="extraAccessoryFiles"
                    compact
                    :file-preview-url="filePreviewUrl"
                    :upload-key="uploadKey"
                    @preview="openImageViewer"
                    @download="downloadImage"
                    @remove="() => clearExtraAccessoryUpload()"
                  />
                </div>
                <el-button
                  class="template-add-button"
                  type="primary"
                  :icon="Plus"
                  :loading="extraAccessoryUploading"
                  @click="addExtraAccessory"
                >
                  添加额外配件
                </el-button>
              </div>

              <div v-if="extraAccessories.length" class="accessory-list">
                <article v-for="accessory in extraAccessories" :key="accessory.id" class="accessory-card">
                  <div
                    class="accessory-preview image-action-wrap"
                    role="button"
                    tabindex="0"
                    @click="openImageViewer(accessory.preview, accessory.fileName)"
                    @keydown.enter="openImageViewer(accessory.preview, accessory.fileName)"
                  >
                    <img :src="accessory.preview" :alt="accessory.name" />
                    <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(accessory.preview, accessory.fileName)">
                      <el-icon><Download /></el-icon>
                    </button>
                  </div>
                  <div>
                    <strong>{{ accessory.name }}</strong>
                    <small>{{ accessory.fileName }} / {{ accessory.createdAt }}</small>
                  </div>
                  <el-button text type="danger" :icon="Delete" @click="removeExtraAccessory(accessory)">删除</el-button>
                </article>
              </div>
              <el-empty v-else description="暂无额外配件" />
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

              <div class="form-row">
                <label>深析实拍图的提示词</label>
                <el-input
                    v-model="defaultSettings.analysisPrompt"
                    type="textarea"
                    :rows="10"
                    maxlength="2000"
                    show-word-limit
                    placeholder="请输入深析上传图时发送给 GPT 的默认分析要求"
                />
              </div>

              <div style="height: 20px;"></div>
              
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
              <div style="height: 20px;"></div>

              <div class="form-row">
                <label>参考风格图分析提示词</label>
                <el-input
                  v-model="defaultSettings.targetTemplatePrompt"
                  type="textarea"
                  :rows="15"
                  maxlength="3000"
                  show-word-limit
                  placeholder="请输入分析参考风格图风格时发送给 GPT 的提示词"
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
              <span>进度 {{ selectedQueueTask.completedCount }} / {{ selectedQueueTask.totalCount }}</span>
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
            排版图 {{ fileCount(selectedQueueTask, '排版图') }}
          </el-descriptions-item>
          <el-descriptions-item label="卖点" :span="3">
            {{ selectedQueueTask.form.sellingPoints.join('、') || '未选择' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="selectedQueueTask.errorMessage" label="错误信息" :span="4">
            <span class="queue-error-text">{{ selectedQueueTask.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="queue-detail-grid">
          <section class="detail-block submitted-block">
            <h3>提交图片</h3>
            <div class="submitted-file-groups">
              <div v-for="type in uploadGroups" :key="type" class="submitted-file-group">
                <div class="submitted-file-title">{{ uploadGroupDisplayName(type) }} {{ taskFilesByGroup(type).length }}</div>
                <div v-if="taskFilesByGroup(type).length" class="submitted-file-grid">
                  <article v-for="file in taskFilesByGroup(type)" :key="file.id" class="submitted-file-card">
                    <div
                      v-if="file.preview"
                      class="image-action-wrap"
                      role="button"
                      tabindex="0"
                      @click="openImageViewer(taskFileOriginalSrc(file), file.fileName)"
                      @keydown.enter="openImageViewer(taskFileOriginalSrc(file), file.fileName)"
                    >
                      <img :src="file.preview" :alt="file.fileName" />
                      <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(taskFileOriginalSrc(file), file.fileName)">
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

          <section class="detail-block kit-spec-block">
            <h3>套装规格</h3>
            <div v-if="visibleKitSpecs(selectedQueueTask).length" class="queue-tags">
              <span v-for="item in visibleKitSpecs(selectedQueueTask)" :key="item.name">
                {{ item.name }} x {{ item.quantity }}
              </span>
            </div>
            <p v-else class="empty-inline">未选择</p>
          </section>

          <section class="detail-block detail-wide">
            <h3>后端深析结果</h3>
            <div class="analysis-grid">
              <article v-for="type in backendAnalysisGroups" :key="type" class="analysis-card">
                <strong>{{ uploadGroupDisplayName(type) }}</strong>
                <p
                  :class="{ clickable: isLongText(selectedQueueTask.analysis?.[type]) }"
                  @click="openFullTextDialog(`${uploadGroupDisplayName(type)}深析结果`, selectedQueueTask.analysis?.[type])"
                >
                  {{ analysisPreview(selectedQueueTask.analysis?.[type]) }}
                </p>
                <el-button
                  v-if="isLongText(selectedQueueTask.analysis?.[type])"
                  class="text-more-button"
                  size="small"
                  text
                  type="primary"
                  @click="openFullTextDialog(`${uploadGroupDisplayName(type)}深析结果`, selectedQueueTask.analysis?.[type])"
                >
                  查看全文
                </el-button>
              </article>
            </div>
          </section>

          <el-divider />

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
            <h3>场景规划</h3>
            <div class="analysis-grid">
              <article class="analysis-card">
                <strong>主图场景 {{ selectedQueueTask.mainScenes?.length || 0 }}</strong>
                <el-empty
                  v-if="!selectedQueueTask.mainScenes?.length"
                  description="暂无主图场景"
                  :image-size="56"
                />
                <div v-else class="scene-list">
                  <div v-for="scene in selectedQueueTask.mainScenes" :key="`main-${scene.index}`" class="scene-item">
                    <strong>主图 #{{ scene.index }} {{ scene.sceneTitle }}</strong>
                    <p
                      :class="{ clickable: isLongText(sceneFinalPrompt('主图', scene)) }"
                      @click="openFullTextDialog(`主图场景 #${scene.index}`, sceneFinalPrompt('主图', scene))"
                    >
                      {{ analysisPreview(sceneFinalPrompt('主图', scene)) }}
                    </p>
                    <el-button
                      v-if="isLongText(sceneFinalPrompt('主图', scene))"
                      class="text-more-button"
                      size="small"
                      text
                      type="primary"
                      @click="openFullTextDialog(`主图场景 #${scene.index}`, sceneFinalPrompt('主图', scene))"
                    >
                      查看全文
                    </el-button>
                  </div>
                </div>
              </article>
              <article class="analysis-card">
                <strong>介绍图场景 {{ selectedQueueTask.introScenes?.length || 0 }}</strong>
                <el-empty
                  v-if="!selectedQueueTask.introScenes?.length"
                  description="暂无介绍图场景"
                  :image-size="56"
                />
                <div v-else class="scene-list">
                  <div v-for="scene in selectedQueueTask.introScenes" :key="`intro-${scene.index}`" class="scene-item">
                    <strong>介绍图 #{{ scene.index }} {{ scene.sceneTitle }}</strong>
                    <p
                      :class="{ clickable: isLongText(sceneFinalPrompt('介绍图', scene)) }"
                      @click="openFullTextDialog(`介绍图场景 #${scene.index}`, sceneFinalPrompt('介绍图', scene))"
                    >
                      {{ analysisPreview(sceneFinalPrompt('介绍图', scene)) }}
                    </p>
                    <el-button
                      v-if="isLongText(sceneFinalPrompt('介绍图', scene))"
                      class="text-more-button"
                      size="small"
                      text
                      type="primary"
                      @click="openFullTextDialog(`介绍图场景 #${scene.index}`, sceneFinalPrompt('介绍图', scene))"
                    >
                      查看全文
                    </el-button>
                  </div>
                </div>
              </article>
            </div>
          </section>

          <section class="detail-block detail-wide">
            <h3>生成结果</h3>
            <el-empty v-if="selectedQueueTask.results.length === 0" description="暂无生成结果" />
            <div v-else class="result-list">
              <article v-for="result in selectedQueueTask.results" :key="result.id" class="result-row">
                <div class="result-row-head">
                  <strong>{{ result.resultType }} #{{ result.itemIndex }}</strong>
                  <el-tag v-if="result.versionIndex" type="info" effect="plain">v{{ result.versionIndex }}</el-tag>
                  <el-tag :type="taskStatusTagType(result.status)" effect="plain">{{ result.statusText }}</el-tag>
                </div>
                <div
                  v-if="generatedImageSrc(result)"
                  class="result-preview image-action-wrap"
                  role="button"
                  tabindex="0"
                  @click="openImageViewer(generatedImageSrc(result), `${result.resultType}-${result.itemIndex}-v${result.versionIndex || 1}.png`)"
                  @keydown.enter="openImageViewer(generatedImageSrc(result), `${result.resultType}-${result.itemIndex}-v${result.versionIndex || 1}.png`)"
                >
                  <img :src="generatedImageSrc(result)" :alt="`${result.resultType} ${result.itemIndex}`" />
                  <button class="image-download-button" type="button" title="下载图片" @click.stop="downloadImage(generatedImageSrc(result), `${result.resultType}-${result.itemIndex}-v${result.versionIndex || 1}.png`)">
                    <el-icon><Download /></el-icon>
                  </button>
                </div>
                <p v-else class="empty-inline result-empty-image">暂无图片</p>
                <p v-if="result.parentResultId" class="result-version-note">
                  来自结果 #{{ result.parentResultId }} 的重修版本
                </p>
                <p v-if="result.editSuggestion" class="result-version-note">
                  <strong>建议：</strong>{{ result.editSuggestion }}
                </p>
<!--                <p v-if="result.imageUrl"><strong>图片地址：</strong>{{ result.imageUrl }}</p>-->
                <p v-if="result.errorMessage" class="queue-error-text"><strong>错误：</strong>{{ result.errorMessage }}</p>
                <div class="result-row-actions">
                  <el-button
                    v-if="result.status === 'COMPLETED' && generatedImageSrc(result)"
                    size="small"
                    type="primary"
                    :icon="RefreshRight"
                    :loading="resultEditSubmitting && editingResult?.id === result.id"
                    @click="openResultEditDialog(result)"
                  >
                    按建议重修
                  </el-button>
                </div>
              </article>
            </div>
          </section>
        </div>

        <div class="dialog-actions">
          <el-button v-if="isRunningTask(selectedQueueTask.status)" type="warning" :icon="VideoPause" @click="pauseQueuedTask(selectedQueueTask)">
            暂停任务
          </el-button>
          <el-button v-else-if="selectedQueueTask.status === 'PAUSED'" type="success" :icon="VideoPlay" @click="resumeQueuedTask(selectedQueueTask)">
            继续任务
          </el-button>
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
      <div class="markdown-content" v-html="markdownToHtml(fullTextDialogContent)" />
    </el-dialog>

    <el-dialog
      v-model="resultEditDialogVisible"
      title="按客户建议重修图片"
      width="560px"
      append-to-body
    >
      <div v-if="editingResult" class="result-edit-dialog">
        <div v-if="generatedImageSrc(editingResult)" class="result-edit-preview">
          <img :src="generatedImageSrc(editingResult)" :alt="`${editingResult.resultType} ${editingResult.itemIndex}`" />
        </div>
        <el-input
          v-model="resultEditSuggestion"
          type="textarea"
          :rows="5"
          maxlength="500"
          show-word-limit
          placeholder="输入客户对这张图的修改建议，例如：去掉左下角多出来的袋子，湿巾包必须保留 WET WIPES 字样。"
        />
      </div>
      <template #footer>
        <el-button @click="resultEditDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resultEditSubmitting" @click="submitResultEdit">确定重修</el-button>
      </template>
    </el-dialog>

    <Teleport to="body">
      <div v-if="imageViewerVisible && imageViewerImage" class="image-viewer-mask" @click="closeImageViewer">
        <button class="image-viewer-close" type="button" title="关闭" @click.stop="closeImageViewer">
          <el-icon><Close /></el-icon>
        </button>
        <button class="image-viewer-rotate" type="button" title="旋转" @click.stop="rotateImageViewer">
          <el-icon><RefreshRight /></el-icon>
        </button>
        <div class="image-viewer-stage" :class="{ 'is-sideways': imageViewerIsSideways }">
          <img
            :src="imageViewerImage.src"
            :alt="imageViewerImage.name"
            :style="imageViewerImageStyle"
            @click.stop
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
