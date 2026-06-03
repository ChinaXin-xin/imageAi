package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.config.ImageGenerationProperties;
import xin.students.imageaioriginal.model.DefaultPromptSettings;
import xin.students.imageaioriginal.model.ImageTaskDetail;
import xin.students.imageaioriginal.model.ImageTaskKitSpec;
import xin.students.imageaioriginal.model.ImageTaskPayload;
import xin.students.imageaioriginal.model.ImageTaskSummary;
import xin.students.imageaioriginal.model.StoredUploadImage;
import xin.students.imageaioriginal.model.UploadImageAnalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ImageTaskQueueService {

    private static final Logger LOG = LoggerFactory.getLogger(ImageTaskQueueService.class);
    private static final String LAYOUT_TEMPLATE_ANALYSIS_PROMPT = """
            请只分析这张排版图的版式用途，不要把它当参考风格图，也不要要求生成图中原有商品。
            重点输出：
            1. 画面里可用于填入本任务产品图片的主体区域、留白区域和信息模块位置；
            2. 产品应放入的位置、大小比例、前后层级、透视角度和裁切关系；
            3. 背景、光影、边框、卡片、分栏或模块化排版的布局约束；
            4. 哪些元素只是排版占位或示例内容，生成时不要照抄其中商品、品牌、文字或图标。
            输出简短明确的排版约束，服务于把本任务产品填进这张版式。
            """;
    private static final int STALE_GENERATION_SECONDS = 60 * 60;
    private static final long STALE_CHECK_INTERVAL_MILLIS = 30_000;
    private static final String STALE_GENERATION_MESSAGE = "生图接口超过 60 分钟未返回，已自动标记失败，请稍后重试。";
    private static final String MANUAL_PAUSE_MESSAGE = "任务已暂停，不会自动请求后端；点击继续后将重新生成。";
    private static final String STARTUP_PAUSE_MESSAGE = "服务上次关闭时任务仍在执行，已自动暂停；点击继续后将重新生成。";
    private static final int DEFAULT_IMAGE_SIZE = 1536;
    private static final int IMAGE_SIZE_STEP = 16;
    private static final int IMAGE_GENERATION_MAX_ATTEMPTS = 3;
    private static final int IMAGE_GENERATION_RETRY_DELAY_MILLIS = 2_000;
    private static final String USAGE_MAIN = "MAIN";
    private static final String USAGE_INTRO = "INTRO";

    private final ObjectMapper objectMapper;
    private final DefaultPromptSettingsService defaultPromptSettingsService;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final ImageGenerationService imageGenerationService;
    private final ImageScenePromptService imageScenePromptService;
    private final ImageTaskRepository imageTaskRepository;
    private final ImageTaskFileService imageTaskFileService;
    private final ImageTaskDownloadService imageTaskDownloadService;
    private final ImageTaskPromptBuilder imageTaskPromptBuilder;
    private final TargetTemplateService targetTemplateService;
    private final ExtraAccessoryService extraAccessoryService;
    private final ImageGenerationProperties imageGenerationProperties;
    private final ExecutorService taskExecutor;
    private final ExecutorService imageJobExecutor;
    private final Object staleCheckLock = new Object();
    private volatile long lastStaleCheckMillis;

    public ImageTaskQueueService(
            ObjectMapper objectMapper,
            DefaultPromptSettingsService defaultPromptSettingsService,
            UploadImageAnalysisService uploadImageAnalysisService,
            ImageGenerationService imageGenerationService,
            ImageScenePromptService imageScenePromptService,
            ImageTaskRepository imageTaskRepository,
            ImageTaskFileService imageTaskFileService,
            ImageTaskDownloadService imageTaskDownloadService,
            ImageTaskPromptBuilder imageTaskPromptBuilder,
            TargetTemplateService targetTemplateService,
            ExtraAccessoryService extraAccessoryService,
            ImageGenerationProperties imageGenerationProperties
    ) {
        this.objectMapper = objectMapper;
        this.defaultPromptSettingsService = defaultPromptSettingsService;
        this.uploadImageAnalysisService = uploadImageAnalysisService;
        this.imageGenerationService = imageGenerationService;
        this.imageScenePromptService = imageScenePromptService;
        this.imageTaskRepository = imageTaskRepository;
        this.imageTaskFileService = imageTaskFileService;
        this.imageTaskDownloadService = imageTaskDownloadService;
        this.imageTaskPromptBuilder = imageTaskPromptBuilder;
        this.targetTemplateService = targetTemplateService;
        this.extraAccessoryService = extraAccessoryService;
        this.imageGenerationProperties = imageGenerationProperties;
        this.taskExecutor = Executors.newFixedThreadPool(
                imageGenerationProperties.resolvedMaxTaskConcurrency(),
                daemonThreadFactory("image-task-worker")
        );
        this.imageJobExecutor = Executors.newFixedThreadPool(
                imageGenerationProperties.resolvedMaxGlobalImageConcurrency(),
                daemonThreadFactory("image-generation-job")
        );
    }

    @PostConstruct
    public void initialize() {
        LOG.info(
                "image.task.concurrency maxTaskConcurrency={} maxImagesPerTask={} maxGlobalImageConcurrency={}",
                imageGenerationProperties.resolvedMaxTaskConcurrency(),
                imageGenerationProperties.resolvedMaxImagesPerTask(),
                imageGenerationProperties.resolvedMaxGlobalImageConcurrency()
        );
        imageTaskRepository.ensureTables();
        failStaleRunningTasks();
        pauseInterruptedRunningTasks();
        resumeUnfinishedTasks();
    }

    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdownNow();
        imageJobExecutor.shutdownNow();
    }

    public ImageTaskSummary createTask(
            String payloadJson,
            List<MultipartFile> realPhotoFiles,
            List<MultipartFile> templateFiles,
            List<MultipartFile> logoFiles,
            List<MultipartFile> wallpaperFiles
    ) {
        imageTaskRepository.ensureTables();
        ImageTaskPayload payload = normalizePayload(parsePayload(payloadJson));
        String taskId = UUID.randomUUID().toString();
        List<StoredTaskFile> files = new ArrayList<>();
        files.addAll(imageTaskFileService.toStoredTaskFiles("realPhoto", realPhotoFiles));
        files.addAll(imageTaskFileService.toStoredTaskFiles("template", templateFiles));
        files.addAll(imageTaskFileService.toStoredTaskFiles("logo", logoFiles));
        files.addAll(imageTaskFileService.toStoredTaskFiles("wallpaper", wallpaperFiles));
        imageTaskRepository.createTask(taskId, payload, files, imageTaskFileService.createThumbnail(files));
        startProcessing(taskId);
        return imageTaskRepository.getTaskSummary(taskId);
    }

    public List<ImageTaskSummary> listTasks() {
        imageTaskRepository.ensureTables();
        failStaleRunningTasks();
        return imageTaskRepository.listTasks();
    }

    public ImageTaskDetail getTask(String taskId) {
        imageTaskRepository.ensureTables();
        failStaleRunningTasks();
        return imageTaskRepository.getTask(taskId);
    }

    public ImageTaskPreviewFile taskFilePreview(String taskId, long fileId) {
        imageTaskRepository.ensureTables();
        return imageTaskRepository.taskFilePreview(taskId, fileId);
    }

    public ImageTaskPreviewFile taskResultImage(String taskId, long resultId) {
        imageTaskRepository.ensureTables();
        return imageTaskRepository.resultImage(taskId, resultId);
    }

    public ImageTaskDetail retryTask(String taskId) {
        imageTaskRepository.ensureTables();
        TaskRecord record = requireTask(taskId);
        if ("ANALYZING".equals(record.status()) || "GENERATING".equals(record.status())) {
            throw new IllegalStateException("任务正在执行中，请先暂停或等待完成后再重试。");
        }
        imageGenerationService.cancelTask(taskId);
        imageTaskRepository.resetTaskForRetry(taskId);
        startProcessing(taskId);
        return getTask(taskId);
    }

    public ImageTaskDetail pauseTask(String taskId) {
        imageTaskRepository.ensureTables();
        requireTask(taskId);
        imageTaskRepository.pauseTask(taskId, MANUAL_PAUSE_MESSAGE);
        imageGenerationService.cancelTask(taskId);
        return getTask(taskId);
    }

    public ImageTaskDetail resumeTask(String taskId) {
        imageTaskRepository.ensureTables();
        TaskRecord record = requireTask(taskId);
        if (!"PAUSED".equals(record.status())) {
            return getTask(taskId);
        }
        imageTaskRepository.resetTaskForRetry(taskId);
        startProcessing(taskId);
        return getTask(taskId);
    }

    public void deleteTask(String taskId) {
        imageTaskRepository.ensureTables();
        requireTask(taskId);
        imageGenerationService.cancelTask(taskId);
        imageTaskRepository.deleteTask(taskId);
    }

    public ImageTaskDetail editResult(String taskId, long resultId, String suggestion) {
        imageTaskRepository.ensureTables();
        TaskRecord task = requireTask(taskId);
        ResultRecord source = imageTaskRepository.findResult(taskId, resultId);
        if (source == null || !"COMPLETED".equals(source.status())) {
            throw new IllegalArgumentException("只能重修已完成的图片结果");
        }
        String normalizedSuggestion = normalizeText(suggestion, "按建议优化图片细节");
        int nextVersion = imageTaskRepository.nextVersionIndex(taskId, source.resultType(), source.itemIndex());
        long editResultId = imageTaskRepository.insertResult(
                taskId,
                source.resultType(),
                source.itemIndex(),
                buildEditPrompt(task, source, normalizedSuggestion),
                "QUEUED",
                source.id(),
                nextVersion,
                normalizedSuggestion
        );
        submitEditGeneration(task, source, editResultId);
        return getTask(taskId);
    }

    public ImageTaskDownloadFile downloadTaskImages(List<String> taskIds) {
        imageTaskRepository.ensureTables();
        return imageTaskDownloadService.downloadTaskImages(taskIds);
    }

    private void submitEditGeneration(TaskRecord task, ResultRecord source, long editResultId) {
        imageJobExecutor.submit(() -> {
            try {
                imageTaskRepository.markResultGenerating(editResultId);
                ImageGenerationService.GeneratedImage generatedImage = imageGenerationService.generate(
                        task.id(),
                        source.resultType() + "重修",
                        source.itemIndex(),
                        buildEditPrompt(task, source, source.editSuggestion()),
                        normalizeImageDimension(task.payload().customWidth(), DEFAULT_IMAGE_SIZE),
                        normalizeImageDimension(task.payload().customHeight(), DEFAULT_IMAGE_SIZE),
                        List.of(imageTaskDownloadService.resultReferenceImage(source))
                );
                if (!imageTaskRepository.completeResult(editResultId, generatedImage)) {
                    throw new IllegalStateException("重修图片结果保存失败，请重试");
                }
            } catch (Exception ex) {
                LOG.error("image.task.edit.failed taskId={} resultId={} message={}", task.id(), editResultId, ex.getMessage(), ex);
                imageTaskRepository.failResult(editResultId, ex);
            }
        });
    }

    private void startProcessing(String taskId) {
        taskExecutor.submit(() -> processTask(taskId));
    }

    private ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void resumeUnfinishedTasks() {
        imageTaskRepository.tasksByStatus(List.of("QUEUED")).forEach(task -> startProcessing(task.id()));
    }

    private void pauseInterruptedRunningTasks() {
        imageTaskRepository.tasksByStatus(List.of("ANALYZING", "GENERATING"))
                .forEach(task -> imageTaskRepository.pauseTask(task.id(), STARTUP_PAUSE_MESSAGE));
    }

    private void failStaleRunningTasks() {
        long currentMillis = System.currentTimeMillis();
        if (currentMillis - lastStaleCheckMillis < STALE_CHECK_INTERVAL_MILLIS) {
            return;
        }
        synchronized (staleCheckLock) {
            currentMillis = System.currentTimeMillis();
            if (currentMillis - lastStaleCheckMillis < STALE_CHECK_INTERVAL_MILLIS) {
                return;
            }
            lastStaleCheckMillis = currentMillis;
        }
        try {
            imageTaskRepository.staleGeneratingTaskIds(STALE_GENERATION_SECONDS)
                    .forEach(taskId -> imageTaskRepository.failStaleRunningTask(taskId, STALE_GENERATION_MESSAGE));
        } catch (Exception ex) {
            LOG.warn("mark stale image tasks failed", ex);
        }
    }

    private void processTask(String taskId) {
        try {
            TaskRecord record = imageTaskRepository.findTask(taskId);
            if (record == null || "COMPLETED".equals(record.status()) || "FAILED".equals(record.status()) || "PAUSED".equals(record.status())) {
                return;
            }
            if (!requiresGeneration(record.payload())) {
                imageTaskRepository.updateTaskState(taskId, "COMPLETED", null, false, true);
                return;
            }

            imageTaskRepository.updateTaskState(taskId, "ANALYZING", null, true, false);
            TargetTemplateService.TargetTemplateRecord mainTargetTemplate = resolveTargetTemplate(
                    record.payload().mainTargetTemplateId(),
                    "MAIN",
                    "主图"
            );
            TargetTemplateService.TargetTemplateRecord introTargetTemplate = resolveTargetTemplate(
                    record.payload().introTargetTemplateId(),
                    "INTRO",
                    "介绍图"
            );
            Map<String, String> analysis = analyzeUploadedFiles(taskId);
            UploadMaterialContext uploadMaterialContext = uploadMaterialContext(taskId);
            ensureTaskNotPaused(taskId);

            DefaultPromptSettings settings = defaultPromptSettingsService.getSettings();
            String finalMainPrompt = imageTaskPromptBuilder.buildGenerationPrompt(
                    "主图",
                    imageTaskPromptBuilder.generationBasePrompt(record.payload().mainPrompt(), settings.mainPrompt(), settings.analysisPrompt()),
                    record.payload(),
                    analysis,
                    uploadMaterialContext,
                    mainTargetTemplate
            );
            String finalIntroPrompt = imageTaskPromptBuilder.buildGenerationPrompt(
                    "介绍图",
                    imageTaskPromptBuilder.generationBasePrompt(record.payload().introPrompt(), settings.introPrompt(), settings.analysisPrompt()),
                    record.payload(),
                    analysis,
                    uploadMaterialContext,
                    introTargetTemplate
            );
            imageTaskRepository.saveAnalysisAndPrompts(taskId, analysis, finalMainPrompt, finalIntroPrompt);
            ensureTaskNotPaused(taskId);
            imageTaskRepository.clearResults(taskId);
            int mainCount = positive(record.payload().mainImageCount());
            int introCount = positive(record.payload().introImageCount());
            List<ImageScenePromptService.ScenePrompt> mainScenes = imageScenePromptService.planScenes("主图", finalMainPrompt, mainCount);
            List<ImageScenePromptService.ScenePrompt> introScenes = imageScenePromptService.planScenes("介绍图", finalIntroPrompt, introCount);
            imageTaskRepository.saveScenePrompts(taskId, mainScenes, introScenes);
            List<GenerationJob> jobs = createGenerationJobs(
                    taskId,
                    finalMainPrompt,
                    finalIntroPrompt,
                    record.payload(),
                    mainTargetTemplate,
                    introTargetTemplate,
                    mainScenes,
                    introScenes
            );
            imageTaskRepository.updateTaskState(taskId, "GENERATING", null, false, false);

            GenerationReferences referenceImages = generationReferenceImages(
                    taskId,
                    record.payload(),
                    mainTargetTemplate,
                    introTargetTemplate
            );
            generateJobsConcurrently(taskId, jobs, record.payload(), referenceImages);

            if (jobs.isEmpty()) {
                LOG.info("image.task.no-generation taskId={}", taskId);
            }
            if (isTaskPausedOrDeleted(taskId)) {
                LOG.info("image.task.skip-complete taskId={} reason=paused-or-deleted", taskId);
                return;
            }
            imageTaskRepository.updateTaskState(taskId, "COMPLETED", null, false, true);
        } catch (Exception ex) {
            if (isTaskPausedOrDeleted(taskId)) {
                LOG.info("image.task.stopped taskId={} message={}", taskId, ex.getMessage());
                return;
            }
            LOG.error("image.task.failed taskId={} message={}", taskId, ex.getMessage(), ex);
            imageTaskRepository.failTask(taskId, ex);
        }
    }

    private void generateJobsConcurrently(
            String taskId,
            List<GenerationJob> jobs,
            ImageTaskPayload payload,
            GenerationReferences referenceImages
    ) {
        if (jobs.isEmpty()) {
            return;
        }
        int perTaskConcurrency = Math.min(
                imageGenerationProperties.resolvedMaxImagesPerTask(),
                imageGenerationProperties.resolvedMaxGlobalImageConcurrency()
        );
        Semaphore perTaskSemaphore = new Semaphore(perTaskConcurrency);
        List<Future<?>> futures = new ArrayList<>(jobs.size());
        for (GenerationJob job : jobs) {
            try {
                perTaskSemaphore.acquire();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                cancelFutures(futures);
                throw new IllegalStateException("任务生成线程被中断：" + taskId, ex);
            }
            futures.add(imageJobExecutor.submit(() -> {
                try {
                    ensureTaskNotPaused(taskId);
                    generateOne(taskId, job, payload, referencesFor(referenceImages, job.resultType()));
                } finally {
                    perTaskSemaphore.release();
                }
            }));
        }

        Exception firstFailure = null;
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                cancelFutures(futures);
                throw new IllegalStateException("任务生成线程被中断：" + taskId, ex);
            } catch (CancellationException ex) {
                if (!isTaskPausedOrDeleted(taskId) && firstFailure == null) {
                    firstFailure = ex;
                }
            } catch (ExecutionException ex) {
                if (!isTaskPausedOrDeleted(taskId) && firstFailure == null) {
                    firstFailure = unwrapExecutionException(ex);
                    cancelFutures(futures);
                }
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException(firstFailure.getMessage(), firstFailure);
        }
    }

    private void cancelFutures(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private Exception unwrapExecutionException(ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof Exception exception) {
            return exception;
        }
        return new IllegalStateException(cause == null ? ex.getMessage() : cause.getMessage(), cause);
    }

    private List<GenerationJob> createGenerationJobs(
            String taskId,
            String finalMainPrompt,
            String finalIntroPrompt,
            ImageTaskPayload payload,
            TargetTemplateService.TargetTemplateRecord mainTargetTemplate,
            TargetTemplateService.TargetTemplateRecord introTargetTemplate,
            List<ImageScenePromptService.ScenePrompt> mainScenes,
            List<ImageScenePromptService.ScenePrompt> introScenes
    ) {
        List<GenerationJob> jobs = new ArrayList<>();
        int mainCount = positive(payload.mainImageCount());
        int introCount = positive(payload.introImageCount());
        ensureTaskNotPaused(taskId);
        for (int index = 1; index <= mainCount; index++) {
            ensureTaskNotPaused(taskId);
            String prompt = imageTaskPromptBuilder.generationItemPrompt(finalMainPrompt, "主图", index, mainCount, sceneAt(mainScenes, index), payload);
            long resultId = imageTaskRepository.insertResult(taskId, "主图", index, prompt, "QUEUED");
            jobs.add(new GenerationJob(resultId, "主图", index, prompt, mainTargetTemplate));
        }
        for (int index = 1; index <= introCount; index++) {
            ensureTaskNotPaused(taskId);
            String prompt = imageTaskPromptBuilder.generationItemPrompt(finalIntroPrompt, "介绍图", index, introCount, sceneAt(introScenes, index), payload);
            long resultId = imageTaskRepository.insertResult(taskId, "介绍图", index, prompt, "QUEUED");
            jobs.add(new GenerationJob(resultId, "介绍图", index, prompt, introTargetTemplate));
        }
        return jobs;
    }

    private ImageScenePromptService.ScenePrompt sceneAt(List<ImageScenePromptService.ScenePrompt> scenes, int index) {
        if (scenes == null || scenes.isEmpty()) {
            return null;
        }
        int position = Math.max(0, Math.min(scenes.size() - 1, index - 1));
        return scenes.get(position);
    }

    private void generateOne(
            String taskId,
            GenerationJob job,
            ImageTaskPayload payload,
            List<StoredUploadImage> referenceImages
    ) {
        imageTaskRepository.markResultGenerating(job.resultId());
        for (int attempt = 1; attempt <= IMAGE_GENERATION_MAX_ATTEMPTS; attempt++) {
                try {
                    ensureTaskNotPaused(taskId);
                    ImageGenerationService.GeneratedImage generatedImage = imageGenerationService.generateWithPreparedReferences(
                            taskId,
                            job.resultType(),
                            job.index(),
                        job.prompt(),
                        normalizeImageDimension(payload.customWidth(), DEFAULT_IMAGE_SIZE),
                        normalizeImageDimension(payload.customHeight(), DEFAULT_IMAGE_SIZE),
                        referenceImages
                );
                if (!imageTaskRepository.completeResult(job.resultId(), generatedImage)) {
                    throw new IllegalStateException("生成结果已超时或任务已失败，请重试。");
                }
                return;
            } catch (Exception ex) {
                if (isTaskPausedOrDeleted(taskId)) {
                    throw ex;
                }
                if (attempt >= IMAGE_GENERATION_MAX_ATTEMPTS || !isRetryableImageGenerationError(ex)) {
                    imageTaskRepository.failResult(job.resultId(), ex);
                    throw ex;
                }
                LOG.warn(
                        "image.task.image.retry taskId={} resultId={} type={} index={} attempt={} nextAttempt={} message={}",
                        taskId,
                        job.resultId(),
                        job.resultType(),
                        job.index(),
                        attempt,
                        attempt + 1,
                        ex.getMessage()
                );
                sleepBeforeRetry(taskId, job, attempt);
            }
        }
    }

    private boolean isRetryableImageGenerationError(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof TimeoutException || hasCause(error, TimeoutException.class)) {
            return true;
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("生图接口超过")
                || message.contains("未返回")
                || message.contains("空响应")
                || message.contains("HTTP 5")
                || message.contains("I/O error")
                || message.contains("Unexpected end")
                || message.contains("Connection reset")
                || message.contains("Read timed out");
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> causeType) {
        Throwable current = error;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBeforeRetry(String taskId, GenerationJob job, int attempt) {
        try {
            Thread.sleep((long) IMAGE_GENERATION_RETRY_DELAY_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "图片生成重试等待被中断：" + taskId + "/" + job.resultType() + "#" + job.index(),
                    interrupted
            );
        }
    }

    private void ensureTaskNotPaused(String taskId) {
        TaskRecord record = imageTaskRepository.findTask(taskId);
        if (record != null && "PAUSED".equals(record.status())) {
            throw new IllegalStateException(MANUAL_PAUSE_MESSAGE);
        }
        if (record == null) {
            throw new IllegalStateException("任务已删除：" + taskId);
        }
    }

    private boolean isTaskPausedOrDeleted(String taskId) {
        TaskRecord record = imageTaskRepository.findTask(taskId);
        return record == null || "PAUSED".equals(record.status());
    }

    private TargetTemplateService.TargetTemplateRecord resolveTargetTemplate(Long templateId, String expectedType, String label) {
        TargetTemplateService.TargetTemplateRecord template = targetTemplateService.findRecord(templateId);
        if (template == null) {
            return null;
        }
        if (!expectedType.equals(template.templateType())) {
            throw new IllegalArgumentException(label + "只能选择" + label + "参考风格图：" + template.name());
        }
        return template;
    }

    private GenerationReferences generationReferenceImages(
            String taskId,
            ImageTaskPayload payload,
            TargetTemplateService.TargetTemplateRecord mainTargetTemplate,
            TargetTemplateService.TargetTemplateRecord introTargetTemplate
    ) {
        List<StoredUploadImage> realPhotoImages = imageTaskRepository.readStoredImages(taskId, "realPhoto");
        List<StoredUploadImage> templateImages = imageTaskRepository.readStoredImages(taskId, "template");
        List<StoredUploadImage> logoImages = imageTaskRepository.readStoredImages(taskId, "logo");
        List<StoredUploadImage> wallpaperImages = imageTaskRepository.readStoredImages(taskId, "wallpaper");
        List<StoredUploadImage> accessoryImages = accessoryRecords(payload.kitSpecs()).stream()
                .map(extraAccessoryService::toStoredImage)
                .toList();
        StoredUploadImage mainTargetTemplateImage = mainTargetTemplate == null
                ? null
                : targetTemplateService.toStoredImage(mainTargetTemplate);
        StoredUploadImage introTargetTemplateImage = introTargetTemplate == null
                ? null
                : targetTemplateService.toStoredImage(introTargetTemplate);
        List<StoredUploadImage> mainReferences = generationReferenceImagesForType(
                "主图",
                payload,
                realPhotoImages,
                templateImages,
                mainTargetTemplateImage,
                logoImages,
                wallpaperImages,
                accessoryImages
        );
        List<StoredUploadImage> introReferences = generationReferenceImagesForType(
                "介绍图",
                payload,
                realPhotoImages,
                templateImages,
                introTargetTemplateImage,
                logoImages,
                wallpaperImages,
                accessoryImages
        );
        List<StoredUploadImage> preparedMainReferences = imageGenerationService.prepareReferenceImagesForGeneration(mainReferences);
        List<StoredUploadImage> preparedIntroReferences = imageGenerationService.prepareReferenceImagesForGeneration(introReferences);
        LOG.info(
                "image.task.references taskId={} mainTotal={} mainBytes={} mainNames={} introTotal={} introBytes={} introNames={}",
                taskId,
                preparedMainReferences.size(),
                preparedMainReferences.stream().mapToLong(image -> image.bytes() == null ? 0 : image.bytes().length).sum(),
                preparedMainReferences.stream().map(StoredUploadImage::fileName).toList(),
                preparedIntroReferences.size(),
                preparedIntroReferences.stream().mapToLong(image -> image.bytes() == null ? 0 : image.bytes().length).sum(),
                preparedIntroReferences.stream().map(StoredUploadImage::fileName).toList()
        );
        return new GenerationReferences(preparedMainReferences, preparedIntroReferences);
    }

    private List<StoredUploadImage> generationReferenceImagesForType(
            String imageType,
            ImageTaskPayload payload,
            List<StoredUploadImage> realPhotoImages,
            List<StoredUploadImage> templateImages,
            StoredUploadImage targetTemplateImage,
            List<StoredUploadImage> logoImages,
            List<StoredUploadImage> wallpaperImages,
            List<StoredUploadImage> accessoryImages
    ) {
        List<StoredUploadImage> referenceImages = new ArrayList<>();
        referenceImages.addAll(realPhotoImages == null ? List.of() : realPhotoImages);
        if (usesUploadAsset(payload.templateUsages(), imageType)) {
            referenceImages.addAll(templateImages == null ? List.of() : templateImages);
        }
        if (targetTemplateImage != null) {
            referenceImages.add(targetTemplateImage);
        }
        if (usesUploadAsset(payload.logoUsages(), imageType)) {
            referenceImages.addAll(logoImages == null ? List.of() : logoImages);
        }
        if (usesUploadAsset(payload.wallpaperUsages(), imageType)) {
            referenceImages.addAll(wallpaperImages == null ? List.of() : wallpaperImages);
        }
        referenceImages.addAll(accessoryImages == null ? List.of() : accessoryImages);
        return referenceImages;
    }

    private List<StoredUploadImage> referencesFor(GenerationReferences references, String resultType) {
        if (references == null) {
            return List.of();
        }
        return "主图".equals(resultType) ? references.mainImages() : references.introImages();
    }

    private List<ExtraAccessoryService.ExtraAccessoryRecord> accessoryRecords(List<ImageTaskKitSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .map(this::accessoryRecord)
                .filter(record -> record != null && record.content() != null && record.content().length > 0)
                .toList();
    }

    private ExtraAccessoryService.ExtraAccessoryRecord accessoryRecord(ImageTaskKitSpec spec) {
        if (spec == null || spec.accessoryId() == null || spec.accessoryId() <= 0 || positive(spec.quantity()) <= 0) {
            return null;
        }
        ExtraAccessoryService.ExtraAccessoryRecord accessory = extraAccessoryService.findRecord(positiveId(spec.accessoryId()));
        if (accessory != null) {
            return accessory;
        }
        return extraAccessoryService.findRecordByName(spec.name());
    }

    private Map<String, String> analyzeUploadedFiles(String taskId) {
        Map<String, String> analysis = new LinkedHashMap<>();
        DefaultPromptSettings settings = defaultPromptSettingsService.getSettings();
        analyzeGroup(taskId, "realPhoto", "实拍图", settings.analysisPrompt(), false, analysis);
        analyzeGroup(taskId, "template", "排版图", LAYOUT_TEMPLATE_ANALYSIS_PROMPT, true, analysis);
        return analysis;
    }

    private void analyzeGroup(
            String taskId,
            String fileGroup,
            String label,
            String prompt,
            boolean styleOnly,
            Map<String, String> analysis
    ) {
        List<StoredUploadImage> files = imageTaskRepository.readStoredImages(taskId, fileGroup);
        if (files.isEmpty()) {
            return;
        }
        UploadImageAnalysis result = styleOnly
                ? uploadImageAnalysisService.analyzeStyleStored(label, prompt, files)
                : uploadImageAnalysisService.analyzeStored(label, prompt, files);
        analysis.put(label, result.result());
        imageTaskRepository.savePartialAnalysis(taskId, analysis);
    }

    private UploadMaterialContext uploadMaterialContext(String taskId) {
        return new UploadMaterialContext(
                imageTaskRepository.countStoredImages(taskId, "template") > 0,
                imageTaskRepository.countStoredImages(taskId, "logo") > 0,
                imageTaskRepository.countStoredImages(taskId, "wallpaper") > 0
        );
    }

    private ImageTaskPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("任务参数不能为空");
        }
        try {
            ImageTaskPayload payload = objectMapper.readValue(payloadJson, ImageTaskPayload.class);
            if (payload == null) {
                throw new IllegalArgumentException("任务参数不能为空");
            }
            return payload;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("任务参数格式错误", ex);
        }
    }

    private ImageTaskPayload normalizePayload(ImageTaskPayload payload) {
        String productName = normalizeText(payload.productName(), randomProductName());
        String ratio = normalizeLegacyRatio(normalizeText(payload.ratio(), "1536:1536"));
        int[] imageSize = normalizeImageSize(ratio, payload.customWidth(), payload.customHeight());
        return new ImageTaskPayload(
                productName,
                normalizeNullable(payload.model()),
                normalizeText(payload.platform(), "Amazon"),
                ratio,
                imageSize[0],
                imageSize[1],
                normalizeText(payload.phoneColor(), "自动"),
                normalizeText(payload.customColor(), "#2563eb"),
                normalizeNullable(payload.logoName()),
                normalizeNullable(payload.wallpaperName()),
                normalizeText(payload.style(), "自动"),
                normalizeText(payload.layout(), "自动"),
                payload.sellingPoints() == null ? List.of() : payload.sellingPoints(),
                payload.hdEnabled(),
                payload.privacyEnabled(),
                positive(payload.hdQuantity()),
                positive(payload.privacyQuantity()),
                positive(payload.mainImageCount()),
                positive(payload.introImageCount()),
                normalizeText(payload.language(), "英文"),
                normalizeNullable(payload.mainPrompt()),
                normalizeNullable(payload.introPrompt()),
                positiveId(payload.mainTargetTemplateId()),
                positiveId(payload.introTargetTemplateId()),
                normalizeUsageList(payload.templateUsages()),
                normalizeUsageList(payload.logoUsages()),
                normalizeUsageList(payload.wallpaperUsages()),
                normalizeKitSpecs(payload.kitSpecs())
        );
    }

    private List<ImageTaskKitSpec> normalizeKitSpecs(List<ImageTaskKitSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(spec -> spec != null && positive(spec.quantity()) > 0)
                .map(spec -> {
                    ExtraAccessoryService.ExtraAccessoryRecord accessory = accessoryRecord(spec);
                    String name = accessory == null ? normalizeNullable(spec.name()) : accessory.name();
                    if (name.isBlank()) {
                        return null;
                    }
                    return new ImageTaskKitSpec(
                            accessory == null ? positiveId(spec.accessoryId()) : accessory.id(),
                            name,
                            Math.max(1, positive(spec.quantity()))
                    );
                })
                .filter(spec -> spec != null)
                .toList();
    }

    private String buildEditPrompt(TaskRecord task, ResultRecord source, String suggestion) {
        StringBuilder builder = new StringBuilder();
        builder.append("基于上传的当前生成图进行局部重修，输出一张新的电商成品图。");
        builder.append("用户修改建议：").append(abbreviate(suggestion, 800)).append("。");
        builder.append("只修正用户指出的缺点，保留当前图的主体构图、产品数量、产品外轮廓、孔位数量、孔位位置、孔位大小差异、材质、光影和平台风格。");
        builder.append("如果图中有清洁包、除尘贴、辅助贴等配件文字，只能保留或还原参考图/当前图已经可见的文字，不要凭空改字、加字或生成无字替代品。");
        builder.append("禁止新增当前图或任务已上传/已选择范围之外的物品，尤其禁止新增黑色小袋、白色小袋、收纳袋、包装盒、卡片、托盘、支架、底座和未选择赠品。");
        builder.append("客户产品范围只包含手机、钢化膜、高清膜、防窥膜、镜头膜，以及已上传或已选择的手机膜相关清洁/安装辅助配件。");
        if (source.prompt() != null && !source.prompt().isBlank()) {
            builder.append("原生成约束摘要：").append(abbreviate(source.prompt(), 1200));
        }
        if (task.analysis() != null && !task.analysis().isEmpty()) {
            builder.append(" 上传图结构分析摘要：").append(abbreviate(String.join("；", task.analysis().values()), 1200));
        }
        builder.append("最终自检：只改变建议中要求改的地方；没有额外袋子/盒子/卡片/托盘；手机膜和镜头膜结构不被通用化。");
        return builder.toString();
    }

    private boolean requiresGeneration(ImageTaskPayload payload) {
        return positive(payload.mainImageCount()) > 0 || positive(payload.introImageCount()) > 0;
    }

    private int positive(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private int positiveOrDefault(Integer value, int fallback) {
        int normalized = positive(value);
        return normalized > 0 ? normalized : fallback;
    }

    private String normalizeLegacyRatio(String ratio) {
        return switch (ratio) {
            case "1500:1500" -> "1536:1536";
            case "1000:1000" -> "1024:1024";
            case "900:600" -> "960:640";
            default -> ratio;
        };
    }

    private int[] normalizeImageSize(String ratio, Integer customWidth, Integer customHeight) {
        if (!"自定义".equals(ratio)) {
            String[] parts = ratio.split(":");
            if (parts.length == 2) {
                try {
                    int width = normalizeImageDimension(Integer.parseInt(parts[0]), DEFAULT_IMAGE_SIZE);
                    int height = normalizeImageDimension(Integer.parseInt(parts[1]), DEFAULT_IMAGE_SIZE);
                    return new int[]{width, height};
                } catch (NumberFormatException ignored) {
                    // Fall through to custom size normalization.
                }
            }
        }
        return new int[]{
                normalizeImageDimension(customWidth, DEFAULT_IMAGE_SIZE),
                normalizeImageDimension(customHeight, DEFAULT_IMAGE_SIZE)
        };
    }

    private int normalizeImageDimension(Integer value, int fallback) {
        int normalized = positiveOrDefault(value, fallback);
        if (normalized < 300) {
            normalized = fallback;
        }
        return Math.max(IMAGE_SIZE_STEP, Math.round((float) normalized / IMAGE_SIZE_STEP) * IMAGE_SIZE_STEP);
    }

    private boolean usesUploadAsset(List<String> usages, String imageType) {
        String usageCode = usageCode(imageType);
        if (usageCode.isBlank()) {
            return false;
        }
        return normalizeUsageList(usages).contains(usageCode);
    }

    private String usageCode(String imageType) {
        return switch (imageType == null ? "" : imageType) {
            case "主图" -> USAGE_MAIN;
            case "介绍图" -> USAGE_INTRO;
            default -> "";
        };
    }

    private List<String> normalizeUsageList(List<String> usages) {
        if (usages == null || usages.isEmpty()) {
            return List.of(USAGE_MAIN, USAGE_INTRO);
        }
        List<String> normalized = usages.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase())
                .filter(value -> USAGE_MAIN.equals(value) || USAGE_INTRO.equals(value))
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of(USAGE_MAIN, USAGE_INTRO) : normalized;
    }

    private Long positiveId(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private TaskRecord requireTask(String taskId) {
        TaskRecord record = imageTaskRepository.findTask(taskId);
        if (record == null) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        return record;
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String randomProductName() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
