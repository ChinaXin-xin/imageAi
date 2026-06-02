package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import xin.students.imageaioriginal.model.ImageTaskFileView;
import xin.students.imageaioriginal.model.ImageTaskKitSpec;
import xin.students.imageaioriginal.model.ImageTaskPayload;
import xin.students.imageaioriginal.model.ImageTaskResultView;
import xin.students.imageaioriginal.model.ImageTaskSummary;
import xin.students.imageaioriginal.model.StoredUploadImage;
import xin.students.imageaioriginal.model.UploadImageAnalysis;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ImageTaskQueueService {

    private static final Logger LOG = LoggerFactory.getLogger(ImageTaskQueueService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int STALE_GENERATION_SECONDS = 15 * 60;
    private static final String STALE_GENERATION_MESSAGE = "生图接口超过 15 分钟未返回，已自动标记失败，请稍后重试。";
    private static final String MANUAL_PAUSE_MESSAGE = "任务已暂停，不会自动请求后端；点击继续后将重新生成。";
    private static final String STARTUP_PAUSE_MESSAGE = "服务上次关闭时任务仍在执行，已自动暂停；点击继续后将重新生成。";
    private static final int THUMB_MAX_EDGE = 320;
    private static final int DEFAULT_IMAGE_SIZE = 1536;
    private static final int IMAGE_SIZE_STEP = 16;
    private static final int MAX_ANALYSIS_PROMPT_CHARS = 1800;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final DefaultPromptSettingsService defaultPromptSettingsService;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final ImageGenerationService imageGenerationService;
    private final TargetTemplateService targetTemplateService;
    private final ExtraAccessoryService extraAccessoryService;
    private final ImageGenerationProperties imageGenerationProperties;
    private final ExecutorService taskExecutor;
    private final ExecutorService imageJobExecutor;

    public ImageTaskQueueService(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DefaultPromptSettingsService defaultPromptSettingsService,
            UploadImageAnalysisService uploadImageAnalysisService,
            ImageGenerationService imageGenerationService,
            TargetTemplateService targetTemplateService,
            ExtraAccessoryService extraAccessoryService,
            ImageGenerationProperties imageGenerationProperties
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.defaultPromptSettingsService = defaultPromptSettingsService;
        this.uploadImageAnalysisService = uploadImageAnalysisService;
        this.imageGenerationService = imageGenerationService;
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
        ensureTables();
        failStaleRunningTasks();
        pauseInterruptedRunningTasks();
        resumeUnfinishedTasks();
    }

    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdownNow();
        imageJobExecutor.shutdownNow();
    }

    public ImageTaskDetail createTask(
            String payloadJson,
            List<MultipartFile> realPhotoFiles,
            List<MultipartFile> packageImageFiles,
            List<MultipartFile> templateFiles,
            List<MultipartFile> logoFiles,
            List<MultipartFile> wallpaperFiles
    ) {
        ensureTables();
        ImageTaskPayload payload = normalizePayload(parsePayload(payloadJson));
        String taskId = UUID.randomUUID().toString();
        List<StoredTaskFile> files = new ArrayList<>();
        files.addAll(toStoredTaskFiles("realPhoto", realPhotoFiles));
        files.addAll(toStoredTaskFiles("packageImage", packageImageFiles));
        files.addAll(toStoredTaskFiles("template", templateFiles));
        files.addAll(toStoredTaskFiles("logo", logoFiles));
        files.addAll(toStoredTaskFiles("wallpaper", wallpaperFiles));
        Thumbnail thumbnail = createThumbnail(files);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertTask(connection, taskId, payload, files, thumbnail);
                insertFiles(connection, taskId, files);
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("保存任务队列失败", ex);
        }

        startProcessing(taskId);
        return getTask(taskId);
    }

    public List<ImageTaskSummary> listTasks() {
        ensureTables();
        failStaleRunningTasks();
        List<ImageTaskSummary> tasks = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from image_tasks order by created_at desc
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                TaskRecord record = readTaskRecord(resultSet);
                tasks.add(toSummary(connection, record));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取任务队列失败", ex);
        }
        return tasks;
    }

    public ImageTaskDetail getTask(String taskId) {
        ensureTables();
        failStaleRunningTasks();
        try (Connection connection = dataSource.getConnection()) {
            TaskRecord record = findTask(connection, taskId);
            if (record == null) {
                throw new IllegalArgumentException("任务不存在：" + taskId);
            }
            return toDetail(connection, record);
        } catch (SQLException ex) {
            throw new IllegalStateException("读取任务详情失败", ex);
        }
    }

    public ImageTaskDetail retryTask(String taskId) {
        ensureTables();
        failStaleRunningTasks();
        try (Connection connection = dataSource.getConnection()) {
            TaskRecord record = findTask(connection, taskId);
            if (record == null) {
                throw new IllegalArgumentException("任务不存在：" + taskId);
            }
            if (isRunningStatus(record.status())) {
                throw new IllegalStateException("任务正在执行中，不需要重试：" + statusText(record.status()));
            }
            resetTaskForRetry(connection, taskId);
        } catch (SQLException ex) {
            throw new IllegalStateException("重试任务失败", ex);
        }
        startProcessing(taskId);
        return getTask(taskId);
    }

    public ImageTaskDetail pauseTask(String taskId) {
        ensureTables();
        try (Connection connection = dataSource.getConnection()) {
            TaskRecord record = findTask(connection, taskId);
            if (record == null) {
                throw new IllegalArgumentException("任务不存在：" + taskId);
            }
            if ("COMPLETED".equals(record.status()) || "FAILED".equals(record.status())) {
                throw new IllegalStateException("已结束任务不能暂停：" + statusText(record.status()));
            }
            pauseTask(connection, taskId, MANUAL_PAUSE_MESSAGE);
        } catch (SQLException ex) {
            throw new IllegalStateException("暂停任务失败：" + taskId, ex);
        }
        imageGenerationService.cancelTask(taskId);
        return getTask(taskId);
    }

    public ImageTaskDetail resumeTask(String taskId) {
        ensureTables();
        failStaleRunningTasks();
        try (Connection connection = dataSource.getConnection()) {
            TaskRecord record = findTask(connection, taskId);
            if (record == null) {
                throw new IllegalArgumentException("任务不存在：" + taskId);
            }
            if (!"PAUSED".equals(record.status())) {
                throw new IllegalStateException("只有暂停中的任务可以继续：" + statusText(record.status()));
            }
            resetTaskForRetry(connection, taskId);
        } catch (SQLException ex) {
            throw new IllegalStateException("继续任务失败：" + taskId, ex);
        }
        startProcessing(taskId);
        return getTask(taskId);
    }

    public void deleteTask(String taskId) {
        ensureTables();
        imageGenerationService.cancelTask(taskId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("delete from image_tasks where id = ?")) {
            statement.setString(1, taskId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("任务不存在：" + taskId);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("删除任务失败：" + taskId, ex);
        }
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
        List<String> taskIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select id from image_tasks where status = 'QUEUED'
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                taskIds.add(resultSet.getString("id"));
            }
        } catch (SQLException ex) {
            LOG.warn("resume unfinished image tasks failed", ex);
            return;
        }
        taskIds.forEach(this::startProcessing);
    }

    private void pauseInterruptedRunningTasks() {
        try (Connection connection = dataSource.getConnection()) {
            List<String> taskIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    select id from image_tasks where status in ('ANALYZING', 'GENERATING')
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    taskIds.add(resultSet.getString("id"));
                }
            }
            for (String taskId : taskIds) {
                pauseTask(connection, taskId, STARTUP_PAUSE_MESSAGE);
            }
        } catch (SQLException ex) {
            LOG.warn("pause interrupted image tasks failed", ex);
        }
    }

    private void pauseTask(Connection connection, String taskId, String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_task_results
                set status = 'PAUSED', error_message = ?, updated_at = current_timestamp(3)
                where task_id = ? and status in ('QUEUED', 'ANALYZING', 'GENERATING')
                """)) {
            statement.setString(1, message);
            statement.setString(2, taskId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_tasks
                set status = 'PAUSED',
                    error_message = ?,
                    updated_at = current_timestamp(3)
                where id = ? and status in ('QUEUED', 'ANALYZING', 'GENERATING')
                """)) {
            statement.setString(1, message);
            statement.setString(2, taskId);
            statement.executeUpdate();
        }
    }

    private void failStaleRunningTasks() {
        try (Connection connection = dataSource.getConnection()) {
            failStaleRunningTasks(connection);
        } catch (SQLException ex) {
            LOG.warn("mark stale image tasks failed", ex);
        }
    }

    private void failStaleRunningTasks(Connection connection) throws SQLException {
        List<String> taskIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select distinct r.task_id
                from image_task_results r
                join image_tasks t on t.id = r.task_id
                where t.status = 'GENERATING'
                  and r.status = 'GENERATING'
                  and r.updated_at < timestampadd(second, ?, current_timestamp(3))
                """)) {
            statement.setInt(1, -STALE_GENERATION_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    taskIds.add(resultSet.getString("task_id"));
                }
            }
        }
        for (String taskId : taskIds) {
            failStaleRunningTask(connection, taskId);
        }
    }

    private void failStaleRunningTask(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_task_results
                set status = 'FAILED', error_message = ?, updated_at = current_timestamp(3)
                where task_id = ? and status in ('GENERATING', 'QUEUED')
                """)) {
            statement.setString(1, STALE_GENERATION_MESSAGE);
            statement.setString(2, taskId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_tasks
                set status = 'FAILED',
                    error_message = ?,
                    completed_at = current_timestamp(3),
                    updated_at = current_timestamp(3)
                where id = ? and status = 'GENERATING'
                """)) {
            statement.setString(1, STALE_GENERATION_MESSAGE);
            statement.setString(2, taskId);
            statement.executeUpdate();
        }
    }

    private void processTask(String taskId) {
        try {
            TaskRecord record = findTask(taskId);
            if (record == null || "COMPLETED".equals(record.status()) || "FAILED".equals(record.status()) || "PAUSED".equals(record.status())) {
                return;
            }
            updateTaskState(taskId, "ANALYZING", null, true, false);

            Map<String, String> analysis = analyzeUploadedFiles(taskId);
            if (requiresGeneration(record.payload()) && analysis.isEmpty()) {
                throw new IllegalStateException("生成主图或介绍图前必须先深析上传图，请至少上传实拍图、包装图或模板图。");
            }
            ensureTaskNotPaused(taskId);

            DefaultPromptSettings settings = defaultPromptSettingsService.getSettings();
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
            String finalMainPrompt = buildGenerationPrompt(
                    "主图",
                    generationBasePrompt(record.payload().mainPrompt(), settings.mainPrompt(), settings.analysisPrompt()),
                    record.payload(),
                    analysis,
                    mainTargetTemplate
            );
            String finalIntroPrompt = buildGenerationPrompt(
                    "介绍图",
                    generationBasePrompt(record.payload().introPrompt(), settings.introPrompt(), settings.analysisPrompt()),
                    record.payload(),
                    analysis,
                    introTargetTemplate
            );
            saveAnalysisAndPrompts(taskId, analysis, finalMainPrompt, finalIntroPrompt);
            ensureTaskNotPaused(taskId);
            clearResults(taskId);
            List<GenerationJob> jobs = createGenerationJobs(
                    taskId,
                    finalMainPrompt,
                    finalIntroPrompt,
                    record.payload(),
                    mainTargetTemplate,
                    introTargetTemplate
            );
            updateTaskState(taskId, "GENERATING", null, false, false);

            List<StoredUploadImage> referenceImages = generationReferenceImages(readAllStoredImages(taskId), record.payload());
            generateJobsConcurrently(taskId, jobs, record.payload(), referenceImages);

            if (jobs.isEmpty()) {
                LOG.info("image.task.no-generation taskId={}", taskId);
            }
            if (isTaskPausedOrDeleted(taskId)) {
                LOG.info("image.task.skip-complete taskId={} reason=paused-or-deleted", taskId);
                return;
            }
            updateTaskState(taskId, "COMPLETED", null, false, true);
        } catch (Exception ex) {
            if (isTaskPausedOrDeleted(taskId)) {
                LOG.info("image.task.stopped taskId={} message={}", taskId, ex.getMessage());
                return;
            }
            LOG.error("image.task.failed taskId={} message={}", taskId, ex.getMessage(), ex);
            failTask(taskId, ex);
        }
    }

    private void generateJobsConcurrently(
            String taskId,
            List<GenerationJob> jobs,
            ImageTaskPayload payload,
            List<StoredUploadImage> referenceImages
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
                    generateOne(taskId, job, payload, referenceImages);
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
            TargetTemplateService.TargetTemplateRecord introTargetTemplate
    ) {
        List<GenerationJob> jobs = new ArrayList<>();
        int mainCount = positive(payload.mainImageCount());
        int introCount = positive(payload.introImageCount());
        for (int index = 1; index <= mainCount; index++) {
            String prompt = generationItemPrompt(finalMainPrompt, "主图", index, mainCount);
            long resultId = insertResult(taskId, "主图", index, prompt, "QUEUED");
            jobs.add(new GenerationJob(resultId, "主图", index, prompt, mainTargetTemplate));
        }
        for (int index = 1; index <= introCount; index++) {
            String prompt = generationItemPrompt(finalIntroPrompt, "介绍图", index, introCount);
            long resultId = insertResult(taskId, "介绍图", index, prompt, "QUEUED");
            jobs.add(new GenerationJob(resultId, "介绍图", index, prompt, introTargetTemplate));
        }
        return jobs;
    }

    private String generationItemPrompt(String basePrompt, String resultType, int index, int total) {
        return basePrompt + "\n\n【当前生成】" + resultType + "第 " + index + " / " + total + " 张。";
    }

    private void generateOne(
            String taskId,
            GenerationJob job,
            ImageTaskPayload payload,
            List<StoredUploadImage> referenceImages
    ) {
        markResultGenerating(job.resultId());
        try {
            ImageGenerationService.GeneratedImage generatedImage = imageGenerationService.generate(
                    taskId,
                    job.resultType(),
                    job.index(),
                    job.prompt(),
                    normalizeImageDimension(payload.customWidth(), DEFAULT_IMAGE_SIZE),
                    normalizeImageDimension(payload.customHeight(), DEFAULT_IMAGE_SIZE),
                    referenceImages
            );
            if (!completeResult(job.resultId(), generatedImage)) {
                throw new IllegalStateException("生成结果已超时或任务已失败，请重试。");
            }
        } catch (Exception ex) {
            failResult(job.resultId(), ex);
            throw ex;
        }
    }

    private void ensureTaskNotPaused(String taskId) {
        TaskRecord record = findTask(taskId);
        if (record != null && "PAUSED".equals(record.status())) {
            throw new IllegalStateException(MANUAL_PAUSE_MESSAGE);
        }
        if (record == null) {
            throw new IllegalStateException("任务已删除：" + taskId);
        }
    }

    private boolean isTaskPausedOrDeleted(String taskId) {
        TaskRecord record = findTask(taskId);
        return record == null || "PAUSED".equals(record.status());
    }

    private TargetTemplateService.TargetTemplateRecord resolveTargetTemplate(Long templateId, String expectedType, String label) {
        TargetTemplateService.TargetTemplateRecord template = targetTemplateService.findRecord(templateId);
        if (template == null) {
            return null;
        }
        if (!expectedType.equals(template.templateType())) {
            throw new IllegalArgumentException(label + "只能选择" + label + "目标模板：" + template.name());
        }
        return template;
    }

    private List<StoredUploadImage> generationReferenceImages(
            List<StoredUploadImage> uploadImages,
            ImageTaskPayload payload
    ) {
        List<StoredUploadImage> referenceImages = new ArrayList<>(
                uploadImages == null ? List.of() : uploadImages
        );
        accessoryRecords(payload.kitSpecs()).forEach(accessory ->
                referenceImages.add(extraAccessoryService.toStoredImage(accessory))
        );
        LOG.info(
                "image.task.references total={} names={}",
                referenceImages.size(),
                referenceImages.stream().map(StoredUploadImage::fileName).toList()
        );
        return referenceImages;
    }

    private List<ExtraAccessoryService.ExtraAccessoryRecord> accessoryRecords(List<ImageTaskKitSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(spec -> spec != null && positive(spec.quantity()) > 0)
                .map(this::accessoryRecord)
                .filter(accessory -> accessory != null)
                .toList();
    }

    private ExtraAccessoryService.ExtraAccessoryRecord accessoryRecord(ImageTaskKitSpec spec) {
        if (spec == null) {
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
        String prompt = defaultPromptSettingsService.getSettings().analysisPrompt();
        analyzeGroup(taskId, "realPhoto", "实拍图", prompt, analysis);
        analyzeGroup(taskId, "packageImage", "包装图", prompt, analysis);
        analyzeGroup(taskId, "template", "模板图", prompt, analysis);
        analyzeGroup(taskId, "logo", "Logo图", prompt, analysis);
        analyzeGroup(taskId, "wallpaper", "壁纸图", prompt, analysis);
        return analysis;
    }

    private void analyzeGroup(
            String taskId,
            String fileGroup,
            String label,
            String prompt,
            Map<String, String> analysis
    ) {
        List<StoredUploadImage> files = readStoredImages(taskId, fileGroup);
        if (files.isEmpty()) {
            return;
        }
        UploadImageAnalysis result = uploadImageAnalysisService.analyzeStored(label, prompt, files);
        analysis.put(label, result.result());
        savePartialAnalysis(taskId, analysis);
    }

    private String buildGenerationPrompt(
            String imageType,
            String basePrompt,
            ImageTaskPayload payload,
            Map<String, String> analysis,
            TargetTemplateService.TargetTemplateRecord targetTemplate
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("【最高优先级：结构锁定】\n");
        builder.append("上传参考图 > 深析结果 > 套装数量 > 任务参数 > 模板风格。先还原真实产品结构，再做电商美化。\n");
        builder.append("不得改成通用款；不得统一大小不同的孔位；不得增加、删除、移动或遮挡孔位、缺口、外轮廓和配件。\n\n");

        builder.append("【上传图深析结果】\n");
        analysis.forEach((label, result) -> builder.append("[").append(label).append("]\n")
                .append(abbreviate(normalizeNullable(result), MAX_ANALYSIS_PROMPT_CHARS))
                .append("\n"));
        builder.append("\n");

        builder.append("【手机膜结构规则】\n");
        builder.append("镜头膜/屏幕膜按上传图的外轮廓、孔位数量、孔位位置、孔位大小生成。若小孔大小不同或结构非对称，必须保留差异；禁止做成等大、等距、分离镜圈或标准圆环。孔洞内不要添加不存在的镜片、金属圈、螺丝、图标或文字。\n\n");

        appendKitLock(builder, payload);

        builder.append("【任务参数】\n");
        builder.append("【平台】").append(normalizeText(payload.platform(), "Amazon")).append("\n");
        builder.append("【尺寸】")
                .append(normalizeImageDimension(payload.customWidth(), DEFAULT_IMAGE_SIZE))
                .append("x")
                .append(normalizeImageDimension(payload.customHeight(), DEFAULT_IMAGE_SIZE))
                .append("\n");
        builder.append("【语言】").append(normalizeText(payload.language(), "英文")).append("\n");
        builder.append("【机型】").append(normalizeText(payload.model(), "根据上传图自动识别")).append("\n");
        builder.append("【手机颜色】").append(normalizeText(payload.phoneColor(), "自动")).append("\n");
        builder.append("【设计风格】").append(normalizeText(payload.style(), "自动")).append("\n");
        builder.append("【布局模式】").append(normalizeText(payload.layout(), "自动")).append("\n");
        appendLogoContext(builder, payload, analysis);
        appendWallpaperContext(builder, payload, analysis);
        builder.append("【卖点】").append(joinList(payload.sellingPoints())).append("\n");
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        builder.append("【套装规格】").append(kitSpecText).append("\n");
        String productTypeText = productTypeText(payload);
        if (!"未选择".equals(productTypeText)) {
            builder.append("【产品类型】").append(productTypeText).append("\n");
        }
        builder.append("\n【").append(imageType).append("画面要求】\n");
        builder.append(normalizeText(basePrompt, "生成跨境电商图片。")).append("\n");
        builder.append("画面要求只控制构图、光影和质感，不得改写真实产品结构。\n");
        appendTargetTemplateContext(builder, imageType, targetTemplate);
        builder.append("【视觉特效】加强玻璃高光、材质反射、柔和阴影和轻微3D纵深，但不能遮挡或改变产品结构。\n");
        builder.append("\n【负面约束】\n");
        builder.append("不要通用款；不要标准化异形镜头膜；不要把不同大小小孔做成同样大小；不要把一体式镜头膜改成分离圆环；不要添加未选配件、额外孔、额外镜片、额外包装、Logo、水印或装饰文字。\n");
        builder.append("\n【生成要求】结合上传图深析、任务参数和规格生成；必须包含与机型一致的手机或手机模型；套装配件严格按数量出现，未选择的配件不要出现；不要编造不可见细节。");
        return builder.toString();
    }

    private void appendKitLock(StringBuilder builder, ImageTaskPayload payload) {
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        if ("未选择".equals(kitSpecText)) {
            return;
        }
        builder.append("【套装规格数量锁定】").append(kitSpecText).append("\n");
        builder.append("套装规格里的每一种配件都必须按数量准确出现：数量为 1 只出现 1 个，数量为 2 只出现 2 个；未选择的配件不要出现。\n");
        appendAccessoryReferenceContext(builder, payload.kitSpecs());
        builder.append("\n");
    }

    private void appendAccessoryReferenceContext(StringBuilder builder, List<ImageTaskKitSpec> specs) {
        List<ExtraAccessoryService.ExtraAccessoryRecord> accessories = accessoryRecords(specs);
        if (accessories.isEmpty()) {
            builder.append("【配件参考图】未找到已保存的配件图片，请仅按套装规格文字生成。\n");
            return;
        }
        builder.append("【配件参考图】已将以下配件图片作为生图参考图传入：")
                .append(String.join("、", accessories.stream().map(ExtraAccessoryService.ExtraAccessoryRecord::name).toList()))
                .append("。生成时必须参考对应配件图片的形状、颜色、材质，并按套装规格数量准确摆放。\n");
    }

    private void appendTargetTemplateContext(
            StringBuilder builder,
            String imageType,
            TargetTemplateService.TargetTemplateRecord targetTemplate
    ) {
        if (targetTemplate == null) {
            return;
        }
        builder.append("【").append(imageType).append("目标模板风格】")
                .append(abbreviate(normalizeNullable(targetTemplate.styleAnalysis()), MAX_ANALYSIS_PROMPT_CHARS))
                .append("\n");
        builder.append("【").append(imageType).append("目标模板约束】模板只作低优先级风格参考，不得改变上传图孔位、外轮廓、配件数量和产品结构。\n");
    }

    private ImageTaskPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("任务参数不能为空");
        }
        try {
            return objectMapper.readValue(payloadJson, ImageTaskPayload.class);
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

    private void insertTask(
            Connection connection,
            String taskId,
            ImageTaskPayload payload,
            List<StoredTaskFile> files,
            Thumbnail thumbnail
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into image_tasks (
                  id, product_name, status, payload_json,
                  thumbnail, thumbnail_content_type, thumbnail_file_name,
                  real_photo_count, package_image_count, template_count
                ) values (?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, payload.productName());
            statement.setString(3, toJson(payload));
            statement.setBytes(4, thumbnail.bytes());
            statement.setString(5, thumbnail.contentType());
            statement.setString(6, thumbnail.fileName());
            statement.setInt(7, countGroup(files, "realPhoto"));
            statement.setInt(8, countGroup(files, "packageImage"));
            statement.setInt(9, countGroup(files, "template"));
            statement.executeUpdate();
        }
    }

    private void insertFiles(Connection connection, String taskId, List<StoredTaskFile> files) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into image_task_files (
                  task_id, file_group, file_name, content_type, file_size, content, sort_order
                ) values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (StoredTaskFile file : files) {
                statement.setString(1, taskId);
                statement.setString(2, file.fileGroup());
                statement.setString(3, file.fileName());
                statement.setString(4, file.contentType());
                statement.setLong(5, file.bytes().length);
                statement.setBytes(6, file.bytes());
                statement.setInt(7, file.sortOrder());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<StoredTaskFile> toStoredTaskFiles(String fileGroup, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<StoredTaskFile> storedFiles = new ArrayList<>();
        int index = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                storedFiles.add(new StoredTaskFile(
                        fileGroup,
                        normalizeText(file.getOriginalFilename(), "upload-" + index),
                        normalizeText(file.getContentType(), "application/octet-stream"),
                        file.getBytes(),
                        index++
                ));
            } catch (IOException ex) {
                throw new IllegalStateException("读取上传图片失败：" + file.getOriginalFilename(), ex);
            }
        }
        return storedFiles;
    }

    private Thumbnail createThumbnail(List<StoredTaskFile> files) {
        if (files.isEmpty()) {
            return new Thumbnail(null, null, null);
        }
        StoredTaskFile source = files.get(0);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source.bytes()));
            if (image == null) {
                return new Thumbnail(source.bytes(), source.contentType(), source.fileName());
            }
            BufferedImage resized = resizeToRgb(image);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam params = writer.getDefaultWriteParam();
                if (params.canWriteCompressed()) {
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    params.setCompressionQuality(0.76f);
                }
                writer.write(null, new IIOImage(resized, null, null), params);
            } finally {
                writer.dispose();
            }
            return new Thumbnail(output.toByteArray(), "image/jpeg", source.fileName());
        } catch (IOException ex) {
            return new Thumbnail(source.bytes(), source.contentType(), source.fileName());
        }
    }

    private BufferedImage resizeToRgb(BufferedImage source) {
        double scale = Math.min(1D, (double) THUMB_MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private TaskRecord findTask(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select * from image_tasks where id = ?")) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readTaskRecord(resultSet);
                }
            }
        }
        return null;
    }

    private TaskRecord findTask(String taskId) {
        try (Connection connection = dataSource.getConnection()) {
            return findTask(connection, taskId);
        } catch (SQLException ex) {
            throw new IllegalStateException("读取任务失败：" + taskId, ex);
        }
    }

    private TaskRecord readTaskRecord(ResultSet resultSet) throws SQLException {
        ImageTaskPayload payload = parsePayload(resultSet.getString("payload_json"));
        return new TaskRecord(
                resultSet.getString("id"),
                resultSet.getString("product_name"),
                resultSet.getString("status"),
                resultSet.getString("payload_json"),
                payload,
                parseAnalysis(resultSet.getString("analysis_json")),
                resultSet.getString("final_main_prompt"),
                resultSet.getString("final_intro_prompt"),
                resultSet.getBytes("thumbnail"),
                resultSet.getString("thumbnail_content_type"),
                resultSet.getString("thumbnail_file_name"),
                resultSet.getInt("real_photo_count"),
                resultSet.getInt("package_image_count"),
                resultSet.getInt("template_count"),
                resultSet.getString("error_message"),
                formatTime(resultSet.getTimestamp("created_at")),
                formatTime(resultSet.getTimestamp("updated_at")),
                formatTime(resultSet.getTimestamp("started_at")),
                formatTime(resultSet.getTimestamp("completed_at"))
        );
    }

    private ImageTaskSummary toSummary(Connection connection, TaskRecord record) throws SQLException {
        ResultStats stats = progressStats(connection, record);
        return new ImageTaskSummary(
                record.id(),
                record.productName(),
                record.status(),
                statusText(record.status()),
                record.createdAt(),
                record.updatedAt(),
                thumbnailDataUrl(record),
                normalizeText(record.thumbnailFileName(), "暂无缩略图"),
                fileSummary(record),
                record.payload(),
                stats.completed(),
                stats.total(),
                record.errorMessage()
        );
    }

    private ImageTaskDetail toDetail(Connection connection, TaskRecord record) throws SQLException {
        ResultStats stats = progressStats(connection, record);
        return new ImageTaskDetail(
                record.id(),
                record.productName(),
                record.status(),
                statusText(record.status()),
                record.createdAt(),
                record.updatedAt(),
                record.startedAt(),
                record.completedAt(),
                thumbnailDataUrl(record),
                normalizeText(record.thumbnailFileName(), "暂无缩略图"),
                fileSummary(record),
                record.payload(),
                record.payload().kitSpecs() == null ? List.of() : record.payload().kitSpecs(),
                listTaskFiles(connection, record.id()),
                record.analysis(),
                detailFinalPrompt(record, "主图"),
                detailFinalPrompt(record, "介绍图"),
                listResults(connection, record.id()),
                stats.completed(),
                stats.total(),
                record.errorMessage()
        );
    }

    private String detailFinalPrompt(TaskRecord record, String imageType) {
        String storedPrompt = "主图".equals(imageType) ? record.finalMainPrompt() : record.finalIntroPrompt();
        if (storedPrompt == null || storedPrompt.isBlank() || storedPrompt.contains("【上传图深析结果】")) {
            return storedPrompt;
        }
        Map<String, String> analysis = record.analysis();
        if (analysis == null || analysis.isEmpty()) {
            return storedPrompt;
        }
        DefaultPromptSettings settings = defaultPromptSettingsService.getSettings();
        String basePrompt = "主图".equals(imageType)
                ? generationBasePrompt(record.payload().mainPrompt(), settings.mainPrompt(), settings.analysisPrompt())
                : generationBasePrompt(record.payload().introPrompt(), settings.introPrompt(), settings.analysisPrompt());
        TargetTemplateService.TargetTemplateRecord targetTemplate = "主图".equals(imageType)
                ? resolveTargetTemplate(record.payload().mainTargetTemplateId(), "MAIN", "主图")
                : resolveTargetTemplate(record.payload().introTargetTemplateId(), "INTRO", "介绍图");
        return buildGenerationPrompt(imageType, basePrompt, record.payload(), analysis, targetTemplate);
    }

    private List<ImageTaskResultView> listResults(Connection connection, String taskId) throws SQLException {
        List<ImageTaskResultView> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select * from image_task_results where task_id = ? order by id asc
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(new ImageTaskResultView(
                            resultSet.getLong("id"),
                            resultSet.getString("result_type"),
                            resultSet.getInt("item_index"),
                            resultSet.getString("status"),
                            statusText(resultSet.getString("status")),
                            resultSet.getString("prompt"),
                            resultSet.getString("image_url"),
                            resultSet.getString("image_base64"),
                            null,
                            resultSet.getString("error_message"),
                            formatTime(resultSet.getTimestamp("created_at")),
                            formatTime(resultSet.getTimestamp("updated_at"))
                    ));
                }
            }
        }
        return results;
    }

    private Map<String, List<ImageTaskFileView>> listTaskFiles(Connection connection, String taskId) throws SQLException {
        Map<String, List<ImageTaskFileView>> files = new LinkedHashMap<>();
        files.put("实拍图", new ArrayList<>());
        files.put("包装图", new ArrayList<>());
        files.put("模板图", new ArrayList<>());
        files.put("Logo图", new ArrayList<>());
        files.put("壁纸图", new ArrayList<>());
        try (PreparedStatement statement = connection.prepareStatement("""
                select id, file_group, file_name, content_type, file_size, content
                from image_task_files
                where task_id = ?
                order by file_group asc, sort_order asc, id asc
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String group = resultSet.getString("file_group");
                    String groupName = fileGroupName(group);
                    StoredTaskFile storedFile = new StoredTaskFile(
                            group,
                            resultSet.getString("file_name"),
                            resultSet.getString("content_type"),
                            resultSet.getBytes("content"),
                            0
                    );
                    Thumbnail thumbnail = createThumbnail(List.of(storedFile));
                    String preview = thumbnail.bytes() == null || thumbnail.bytes().length == 0
                            ? ""
                            : "data:" + normalizeText(thumbnail.contentType(), "image/jpeg") + ";base64,"
                            + Base64.getEncoder().encodeToString(thumbnail.bytes());
                    files.computeIfAbsent(groupName, ignored -> new ArrayList<>()).add(new ImageTaskFileView(
                            resultSet.getLong("id"),
                            group,
                            groupName,
                            resultSet.getString("file_name"),
                            resultSet.getString("content_type"),
                            resultSet.getLong("file_size"),
                            preview
                    ));
                }
            }
        }
        return files;
    }

    private ResultStats progressStats(Connection connection, TaskRecord record) throws SQLException {
        ResultStats generationStats = generationResultStats(connection, record.id());
        int analysisTotal = countStoredImages(connection, record.id());
        int expectedGenerationTotal = positive(record.payload().mainImageCount()) + positive(record.payload().introImageCount());
        int generationTotal = Math.max(generationStats.total(), expectedGenerationTotal);
        int analysisCompleted = analysisCompletedCount(connection, record, analysisTotal);
        int total = analysisTotal + generationTotal;
        int completed = analysisCompleted + generationStats.completed();
        if ("COMPLETED".equals(record.status())) {
            completed = total;
        }
        return new ResultStats(Math.min(completed, total), total);
    }

    private ResultStats generationResultStats(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select
                  count(*) as total_count,
                  sum(case when status = 'COMPLETED' then 1 else 0 end) as completed_count
                from image_task_results where task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new ResultStats(resultSet.getInt("completed_count"), resultSet.getInt("total_count"));
                }
            }
        }
        return new ResultStats(0, 0);
    }

    private int analysisCompletedCount(Connection connection, TaskRecord record, int analysisTotal) throws SQLException {
        if (analysisTotal == 0) {
            return 0;
        }
        if ("GENERATING".equals(record.status()) || "COMPLETED".equals(record.status())) {
            return analysisTotal;
        }
        return Math.min(analysisTotal, analyzedStoredImageCount(connection, record.id(), record.analysis()));
    }

    private int analyzedStoredImageCount(Connection connection, String taskId, Map<String, String> analysis) throws SQLException {
        if (analysis == null || analysis.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, String> entry : analysis.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String fileGroup = fileGroupCode(entry.getKey());
            if (!fileGroup.isBlank()) {
                count += countStoredImages(connection, taskId, fileGroup);
            }
        }
        return count;
    }

    private int countStoredImages(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from image_task_files where task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private int countStoredImages(Connection connection, String taskId, String fileGroup) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from image_task_files where task_id = ? and file_group = ?
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, fileGroup);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private List<StoredUploadImage> readStoredImages(String taskId, String fileGroup) {
        try (Connection connection = dataSource.getConnection()) {
            return readStoredImages(connection, taskId, fileGroup);
        } catch (SQLException ex) {
            throw new IllegalStateException("读取任务素材失败：" + taskId + "/" + fileGroup, ex);
        }
    }

    private List<StoredUploadImage> readAllStoredImages(String taskId) {
        try (Connection connection = dataSource.getConnection()) {
            List<StoredUploadImage> images = new ArrayList<>();
            images.addAll(readStoredImages(connection, taskId, "realPhoto"));
            images.addAll(readStoredImages(connection, taskId, "packageImage"));
            images.addAll(readStoredImages(connection, taskId, "template"));
            images.addAll(readStoredImages(connection, taskId, "logo"));
            images.addAll(readStoredImages(connection, taskId, "wallpaper"));
            return images;
        } catch (SQLException ex) {
            throw new IllegalStateException("读取任务原图失败：" + taskId, ex);
        }
    }

    private List<StoredUploadImage> readStoredImages(Connection connection, String taskId, String fileGroup) throws SQLException {
        List<StoredUploadImage> images = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select file_name, content_type, content from image_task_files
                where task_id = ? and file_group = ?
                order by sort_order asc, id asc
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, fileGroup);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    images.add(new StoredUploadImage(
                            resultSet.getString("file_name"),
                            resultSet.getString("content_type"),
                            resultSet.getBytes("content")
                    ));
                }
            }
        }
        return images;
    }

    private void saveAnalysisAndPrompts(
            String taskId,
            Map<String, String> analysis,
            String finalMainPrompt,
            String finalIntroPrompt
    ) {
        try (Connection connection = dataSource.getConnection()) {
            saveAnalysisAndPrompts(connection, taskId, analysis, finalMainPrompt, finalIntroPrompt);
        } catch (SQLException ex) {
            throw new IllegalStateException("保存深析结果和最终提示词失败：" + taskId, ex);
        }
    }

    private void savePartialAnalysis(String taskId, Map<String, String> analysis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update image_tasks
                     set analysis_json = ?, updated_at = current_timestamp(3)
                     where id = ?
                     """)) {
            statement.setString(1, toJson(analysis));
            statement.setString(2, taskId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("保存深析进度失败：" + taskId, ex);
        }
    }

    private void saveAnalysisAndPrompts(
            Connection connection,
            String taskId,
            Map<String, String> analysis,
            String finalMainPrompt,
            String finalIntroPrompt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_tasks
                set analysis_json = ?, final_main_prompt = ?, final_intro_prompt = ?, updated_at = current_timestamp(3)
                where id = ?
                """)) {
            statement.setString(1, toJson(analysis));
            statement.setString(2, finalMainPrompt);
            statement.setString(3, finalIntroPrompt);
            statement.setString(4, taskId);
            statement.executeUpdate();
        }
    }

    private void clearResults(String taskId) {
        try (Connection connection = dataSource.getConnection()) {
            clearResults(connection, taskId);
        } catch (SQLException ex) {
            throw new IllegalStateException("清理旧生成结果失败：" + taskId, ex);
        }
    }

    private void clearResults(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("delete from image_task_results where task_id = ?")) {
            statement.setString(1, taskId);
            statement.executeUpdate();
        }
    }

    private long insertResult(String taskId, String resultType, int index, String prompt, String status) {
        try (Connection connection = dataSource.getConnection()) {
            return insertResult(connection, taskId, resultType, index, prompt, status);
        } catch (SQLException ex) {
            throw new IllegalStateException("创建生成结果记录失败：" + taskId, ex);
        }
    }

    private long insertResult(Connection connection, String taskId, String resultType, int index, String prompt, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into image_task_results (task_id, result_type, item_index, status, prompt)
                values (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, taskId);
            statement.setString(2, resultType);
            statement.setInt(3, index);
            statement.setString(4, status);
            statement.setString(5, prompt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new IllegalStateException("创建生成结果记录失败");
    }

    private void markResultGenerating(long resultId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update image_task_results
                     set status = 'GENERATING', updated_at = current_timestamp(3)
                     where id = ? and status = 'QUEUED'
                     """)) {
            statement.setLong(1, resultId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalStateException("生成结果状态不可用，请重试：" + resultId);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("更新生成结果状态失败：" + resultId, ex);
        }
    }

    private boolean completeResult(
            long resultId,
            ImageGenerationService.GeneratedImage generatedImage
    ) {
        try (Connection connection = dataSource.getConnection()) {
            return completeResult(connection, resultId, generatedImage);
        } catch (SQLException ex) {
            throw new IllegalStateException("保存生成结果失败：" + resultId, ex);
        }
    }

    private boolean completeResult(
            Connection connection,
            long resultId,
            ImageGenerationService.GeneratedImage generatedImage
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_task_results
                set status = 'COMPLETED', image_url = ?, image_base64 = ?, revised_prompt = ?, raw_response = ?,
                    updated_at = current_timestamp(3)
                where id = ? and status = 'GENERATING'
                """)) {
            statement.setString(1, generatedImage.imageUrl());
            statement.setString(2, generatedImage.imageBase64());
            statement.setString(3, generatedImage.revisedPrompt());
            statement.setString(4, generatedImage.rawResponse());
            statement.setLong(5, resultId);
            return statement.executeUpdate() > 0;
        }
    }

    private void failResult(long resultId, Exception ex) {
        try (Connection connection = dataSource.getConnection()) {
            failResult(connection, resultId, ex);
        } catch (SQLException sqlEx) {
            LOG.error("image.task.result-fail-state.failed resultId={}", resultId, sqlEx);
        }
    }

    private void failResult(Connection connection, long resultId, Exception ex) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_task_results
                set status = 'FAILED', error_message = ?, updated_at = current_timestamp(3)
                where id = ? and status not in ('COMPLETED', 'PAUSED')
                """)) {
            statement.setString(1, abbreviate(ex.getMessage(), 4000));
            statement.setLong(2, resultId);
            statement.executeUpdate();
        }
    }

    private void updateTaskState(
            String taskId,
            String status,
            String errorMessage,
            boolean markStarted,
            boolean markCompleted
    ) {
        try (Connection connection = dataSource.getConnection()) {
            updateTaskState(connection, taskId, status, errorMessage, markStarted, markCompleted);
        } catch (SQLException ex) {
            throw new IllegalStateException("更新任务状态失败：" + taskId, ex);
        }
    }

    private void updateTaskState(
            Connection connection,
            String taskId,
            String status,
            String errorMessage,
            boolean markStarted,
            boolean markCompleted
    ) throws SQLException {
        String sql = """
                update image_tasks
                set status = ?, error_message = ?, updated_at = current_timestamp(3),
                    started_at = case when ? then coalesce(started_at, current_timestamp(3)) else started_at end,
                    completed_at = case when ? then current_timestamp(3) else completed_at end
                where id = ?
                  and (
                    (? = 'FAILED' and status <> 'PAUSED')
                    or
                    (? <> 'FAILED' and status not in ('FAILED', 'PAUSED'))
                  )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, errorMessage);
            statement.setBoolean(3, markStarted);
            statement.setBoolean(4, markCompleted);
            statement.setString(5, taskId);
            statement.setString(6, status);
            statement.setString(7, status);
            statement.executeUpdate();
        }
    }

    private void resetTaskForRetry(Connection connection, String taskId) throws SQLException {
        clearResults(connection, taskId);
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_tasks
                set status = 'QUEUED',
                    error_message = null,
                    analysis_json = null,
                    final_main_prompt = null,
                    final_intro_prompt = null,
                    started_at = null,
                    completed_at = null,
                    updated_at = current_timestamp(3)
                where id = ?
                """)) {
            statement.setString(1, taskId);
            statement.executeUpdate();
        }
    }

    private void failTask(String taskId, Exception ex) {
        try (Connection connection = dataSource.getConnection()) {
            updateTaskState(connection, taskId, "FAILED", abbreviate(ex.getMessage(), 4000), false, true);
        } catch (SQLException sqlEx) {
            LOG.error("image.task.fail-state.failed taskId={}", taskId, sqlEx);
        }
    }

    private void ensureTables() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists image_tasks (
                      id varchar(64) primary key,
                      product_name varchar(255) not null,
                      status varchar(32) not null,
                      payload_json longtext not null,
                      analysis_json longtext null,
                      final_main_prompt longtext null,
                      final_intro_prompt longtext null,
                      thumbnail longblob null,
                      thumbnail_content_type varchar(128) null,
                      thumbnail_file_name varchar(255) null,
                      real_photo_count int not null default 0,
                      package_image_count int not null default 0,
                      template_count int not null default 0,
                      error_message text null,
                      created_at timestamp(3) not null default current_timestamp(3),
                      updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
                      started_at timestamp(3) null,
                      completed_at timestamp(3) null
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
            statement.executeUpdate("""
                    create table if not exists image_task_files (
                      id bigint primary key auto_increment,
                      task_id varchar(64) not null,
                      file_group varchar(32) not null,
                      file_name varchar(255) not null,
                      content_type varchar(128) not null,
                      file_size bigint not null,
                      content longblob not null,
                      sort_order int not null default 0,
                      created_at timestamp(3) not null default current_timestamp(3),
                      index idx_image_task_files_task_group (task_id, file_group),
                      constraint fk_image_task_files_task foreign key (task_id) references image_tasks(id) on delete cascade
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
            statement.executeUpdate("""
                    create table if not exists image_task_results (
                      id bigint primary key auto_increment,
                      task_id varchar(64) not null,
                      result_type varchar(32) not null,
                      item_index int not null,
                      status varchar(32) not null,
                      prompt longtext not null,
                      image_url text null,
                      image_base64 longtext null,
                      revised_prompt longtext null,
                      raw_response longtext null,
                      error_message text null,
                      created_at timestamp(3) not null default current_timestamp(3),
                      updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
                      index idx_image_task_results_task (task_id),
                      constraint fk_image_task_results_task foreign key (task_id) references image_tasks(id) on delete cascade
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("初始化任务队列表失败", ex);
        }
    }

    private Map<String, String> parseAnalysis(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化任务数据失败", ex);
        }
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

    private int countGroup(List<StoredTaskFile> files, String fileGroup) {
        return (int) files.stream().filter(file -> fileGroup.equals(file.fileGroup())).count();
    }

    private Map<String, Integer> fileSummary(TaskRecord record) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("实拍图", record.realPhotoCount());
        summary.put("包装图", record.packageImageCount());
        summary.put("模板图", record.templateCount());
        return summary;
    }

    private String fileGroupName(String fileGroup) {
        return switch (fileGroup == null ? "" : fileGroup) {
            case "realPhoto" -> "实拍图";
            case "packageImage" -> "包装图";
            case "template" -> "模板图";
            case "logo" -> "Logo图";
            case "wallpaper" -> "壁纸图";
            default -> fileGroup;
        };
    }

    private String fileGroupCode(String label) {
        return switch (label == null ? "" : label) {
            case "实拍图" -> "realPhoto";
            case "包装图" -> "packageImage";
            case "模板图" -> "template";
            case "Logo图" -> "logo";
            case "壁纸图" -> "wallpaper";
            default -> "";
        };
    }

    private String thumbnailDataUrl(TaskRecord record) {
        if (record.thumbnail() == null || record.thumbnail().length == 0) {
            return "";
        }
        String contentType = normalizeText(record.thumbnailContentType(), "image/jpeg");
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(record.thumbnail());
    }

    private String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "QUEUED" -> "待生成";
            case "ANALYZING" -> "正在深析";
            case "GENERATING" -> "正在生图";
            case "PAUSED" -> "已暂停";
            case "COMPLETED" -> "已完成";
            case "FAILED" -> "失败";
            default -> status;
        };
    }

    private boolean isRunningStatus(String status) {
        return "QUEUED".equals(status) || "ANALYZING".equals(status) || "GENERATING".equals(status);
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "未选择";
        }
        return String.join("、", values.stream().filter(value -> value != null && !value.isBlank()).toList());
    }

    private String joinKitSpecs(List<ImageTaskKitSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "未选择";
        }
        List<String> items = specs.stream()
                .filter(spec -> spec != null && spec.name() != null && !spec.name().isBlank())
                .filter(spec -> positive(spec.quantity()) > 0)
                .map(spec -> spec.name() + " x " + positive(spec.quantity()))
                .toList();
        return items.isEmpty() ? "未选择" : String.join("、", items);
    }

    private void appendLogoContext(StringBuilder builder, ImageTaskPayload payload, Map<String, String> analysis) {
        boolean hasLogoImage = analysis.containsKey("Logo图");
        String logoName = normalizeNullable(payload.logoName());
        if (logoName.isEmpty() && !hasLogoImage) {
            return;
        }
        builder.append("【Logo】");
        if (!logoName.isEmpty()) {
            builder.append(logoName);
            if (hasLogoImage) {
                builder.append("，参考上传Logo图识别");
            }
        } else {
            builder.append("按上传图识别");
        }
        builder.append("\n");
    }

    private void appendWallpaperContext(StringBuilder builder, ImageTaskPayload payload, Map<String, String> analysis) {
        boolean hasWallpaperImage = analysis.containsKey("壁纸图");
        String wallpaperName = normalizeNullable(payload.wallpaperName());
        if (wallpaperName.isEmpty() && !hasWallpaperImage) {
            return;
        }
        builder.append("【壁纸】");
        if (!wallpaperName.isEmpty()) {
            builder.append(wallpaperName);
            if (hasWallpaperImage) {
                builder.append("，参考上传壁纸图匹配");
            }
        } else {
            builder.append("按上传图或需求匹配");
        }
        builder.append("\n");
    }

    private String productTypeText(ImageTaskPayload payload) {
        int hdQuantity = Boolean.TRUE.equals(payload.hdEnabled()) ? positive(payload.hdQuantity()) : 0;
        int privacyQuantity = Boolean.TRUE.equals(payload.privacyEnabled()) ? positive(payload.privacyQuantity()) : 0;
        List<String> items = new ArrayList<>();
        if (hdQuantity > 0) {
            items.add("高清 x " + hdQuantity);
        }
        if (privacyQuantity > 0) {
            items.add("防窥 x " + privacyQuantity);
        }
        return items.isEmpty() ? "未选择" : String.join("、", items);
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String generationBasePrompt(String value, String fallback, String analysisPrompt) {
        String normalized = normalizeText(value, fallback);
        if (normalized.equals(normalizeText(analysisPrompt, "")) || looksLikeAnalysisPrompt(normalized)) {
            return fallback;
        }
        return normalized;
    }

    private Long positiveId(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private boolean looksLikeAnalysisPrompt(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "");
        return normalized.contains("请分析上传图片")
                || normalized.contains("深析上传图")
                || normalized.contains("只分析图片中与手机膜产品相关的内容")
                || (normalized.contains("输出要求") && normalized.contains("不要写设计建议"));
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String randomProductName() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private String formatTime(Timestamp timestamp) {
        return timestamp == null
                ? ""
                : timestamp.toLocalDateTime()
                .atZone(ZoneOffset.UTC)
                .withZoneSameInstant(DISPLAY_ZONE)
                .format(TIME_FORMATTER);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private record StoredTaskFile(
            String fileGroup,
            String fileName,
            String contentType,
            byte[] bytes,
            int sortOrder
    ) {
    }

    private record Thumbnail(
            byte[] bytes,
            String contentType,
            String fileName
    ) {
    }

    private record GenerationJob(
            long resultId,
            String resultType,
            int index,
            String prompt,
            TargetTemplateService.TargetTemplateRecord targetTemplate
    ) {
    }

    private record TaskRecord(
            String id,
            String productName,
            String status,
            String payloadJson,
            ImageTaskPayload payload,
            Map<String, String> analysis,
            String finalMainPrompt,
            String finalIntroPrompt,
            byte[] thumbnail,
            String thumbnailContentType,
            String thumbnailFileName,
            int realPhotoCount,
            int packageImageCount,
            int templateCount,
            String errorMessage,
            String createdAt,
            String updatedAt,
            String startedAt,
            String completedAt
    ) {
    }

    private record ResultStats(
            int completed,
            int total
    ) {
    }
}
