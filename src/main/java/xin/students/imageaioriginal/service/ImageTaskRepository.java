package xin.students.imageaioriginal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xin.students.imageaioriginal.entity.ImageTaskEntity;
import xin.students.imageaioriginal.entity.ImageTaskFileEntity;
import xin.students.imageaioriginal.entity.ImageTaskResultEntity;
import xin.students.imageaioriginal.mapper.ImageTaskFileMapper;
import xin.students.imageaioriginal.mapper.ImageTaskMapper;
import xin.students.imageaioriginal.mapper.ImageTaskResultMapper;
import xin.students.imageaioriginal.model.DefaultPromptSettings;
import xin.students.imageaioriginal.model.ImageTaskDetail;
import xin.students.imageaioriginal.model.ImageTaskFileView;
import xin.students.imageaioriginal.model.ImageTaskPayload;
import xin.students.imageaioriginal.model.ImageTaskResultView;
import xin.students.imageaioriginal.model.ImageTaskSceneView;
import xin.students.imageaioriginal.model.ImageTaskSummary;
import xin.students.imageaioriginal.model.StoredUploadImage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImageTaskRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ImageTaskRepository.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final DefaultPromptSettingsService defaultPromptSettingsService;
    private final ImageTaskFileService imageTaskFileService;
    private final ImageTaskPromptBuilder imageTaskPromptBuilder;
    private final TargetTemplateService targetTemplateService;
    private final ImageTaskResultStorageService resultStorageService;
    private final ImageTaskMapper taskMapper;
    private final ImageTaskFileMapper fileMapper;
    private final ImageTaskResultMapper resultMapper;
    private volatile boolean tablesEnsured;

    public ImageTaskRepository(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DefaultPromptSettingsService defaultPromptSettingsService,
            ImageTaskFileService imageTaskFileService,
            ImageTaskPromptBuilder imageTaskPromptBuilder,
            TargetTemplateService targetTemplateService,
            ImageTaskResultStorageService resultStorageService,
            ImageTaskMapper taskMapper,
            ImageTaskFileMapper fileMapper,
            ImageTaskResultMapper resultMapper
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.defaultPromptSettingsService = defaultPromptSettingsService;
        this.imageTaskFileService = imageTaskFileService;
        this.imageTaskPromptBuilder = imageTaskPromptBuilder;
        this.targetTemplateService = targetTemplateService;
        this.resultStorageService = resultStorageService;
        this.taskMapper = taskMapper;
        this.fileMapper = fileMapper;
        this.resultMapper = resultMapper;
    }

    @Transactional
    public void createTask(String taskId, ImageTaskPayload payload, List<StoredTaskFile> files, Thumbnail thumbnail) {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId(taskId);
        task.setProductName(payload.productName());
        task.setStatus("QUEUED");
        task.setPayloadJson(toJson(payload));
        task.setThumbnail(thumbnail.bytes());
        task.setThumbnailContentType(thumbnail.contentType());
        task.setThumbnailFileName(thumbnail.fileName());
        task.setRealPhotoCount(countGroup(files, "realPhoto"));
        task.setPackageImageCount(0);
        task.setTemplateCount(countGroup(files, "template"));
        taskMapper.insert(task);

        for (StoredTaskFile file : files) {
            ImageTaskFileEntity entity = new ImageTaskFileEntity();
            entity.setTaskId(taskId);
            entity.setFileGroup(file.fileGroup());
            entity.setFileName(file.fileName());
            entity.setContentType(file.contentType());
            entity.setFileSize((long) file.bytes().length);
            entity.setContent(file.bytes());
            entity.setSortOrder(file.sortOrder());
            fileMapper.insert(entity);
        }
    }

    public List<ImageTaskSummary> listTasks() {
        List<TaskRecord> records = taskMapper.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
                        .select(
                                ImageTaskEntity::getId,
                                ImageTaskEntity::getProductName,
                                ImageTaskEntity::getStatus,
                                ImageTaskEntity::getPayloadJson,
                                ImageTaskEntity::getThumbnail,
                                ImageTaskEntity::getThumbnailContentType,
                                ImageTaskEntity::getThumbnailFileName,
                                ImageTaskEntity::getRealPhotoCount,
                                ImageTaskEntity::getPackageImageCount,
                                ImageTaskEntity::getTemplateCount,
                                ImageTaskEntity::getErrorMessage,
                                ImageTaskEntity::getCreatedAt,
                                ImageTaskEntity::getUpdatedAt,
                                ImageTaskEntity::getStartedAt,
                                ImageTaskEntity::getCompletedAt
                        )
                        .orderByDesc(ImageTaskEntity::getCreatedAt))
                .stream()
                .map(this::toTaskRecord)
                .toList();
        Map<String, ResultStats> generationStats = generationResultStats(records.stream()
                .map(TaskRecord::id)
                .toList());
        return records.stream()
                .map(record -> toSummary(record, generationStats.getOrDefault(record.id(), ResultStats.empty())))
                .toList();
    }

    public ImageTaskDetail getTask(String taskId) {
        TaskRecord record = findTask(taskId);
        if (record == null) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        return toDetail(record);
    }

    public ImageTaskSummary getTaskSummary(String taskId) {
        TaskRecord record = findTask(taskId);
        if (record == null) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        return toSummary(record);
    }

    public TaskRecord findTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return toTaskRecord(taskMapper.selectById(taskId));
    }

    public List<TaskRecord> tasksByStatus(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
                        .in(ImageTaskEntity::getStatus, statuses)
                        .orderByAsc(ImageTaskEntity::getCreatedAt))
                .stream()
                .map(this::toTaskRecord)
                .toList();
    }

    public List<String> staleGeneratingTaskIds(int staleSeconds) {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(staleSeconds));
        List<String> taskIds = resultMapper.selectList(new LambdaQueryWrapper<ImageTaskResultEntity>()
                        .eq(ImageTaskResultEntity::getStatus, "GENERATING")
                        .lt(ImageTaskResultEntity::getUpdatedAt, cutoff))
                .stream()
                .map(ImageTaskResultEntity::getTaskId)
                .distinct()
                .filter(taskId -> taskId != null && !taskId.isBlank())
                .toList();
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
                        .select(ImageTaskEntity::getId)
                        .in(ImageTaskEntity::getId, taskIds)
                        .eq(ImageTaskEntity::getStatus, "GENERATING"))
                .stream()
                .map(ImageTaskEntity::getId)
                .toList();
    }

    @Transactional
    public void pauseTask(String taskId, String message) {
        resultMapper.update(null, new LambdaUpdateWrapper<ImageTaskResultEntity>()
                .set(ImageTaskResultEntity::getStatus, "PAUSED")
                .set(ImageTaskResultEntity::getErrorMessage, message)
                .set(ImageTaskResultEntity::getUpdatedAt, now())
                .eq(ImageTaskResultEntity::getTaskId, taskId)
                .in(ImageTaskResultEntity::getStatus, List.of("QUEUED", "ANALYZING", "GENERATING")));
        taskMapper.update(null, new LambdaUpdateWrapper<ImageTaskEntity>()
                .set(ImageTaskEntity::getStatus, "PAUSED")
                .set(ImageTaskEntity::getErrorMessage, message)
                .set(ImageTaskEntity::getUpdatedAt, now())
                .eq(ImageTaskEntity::getId, taskId)
                .in(ImageTaskEntity::getStatus, List.of("QUEUED", "ANALYZING", "GENERATING")));
    }

    @Transactional
    public void failStaleRunningTask(String taskId, String message) {
        resultMapper.update(null, new LambdaUpdateWrapper<ImageTaskResultEntity>()
                .set(ImageTaskResultEntity::getStatus, "FAILED")
                .set(ImageTaskResultEntity::getErrorMessage, message)
                .set(ImageTaskResultEntity::getUpdatedAt, now())
                .eq(ImageTaskResultEntity::getTaskId, taskId)
                .in(ImageTaskResultEntity::getStatus, List.of("GENERATING", "QUEUED")));
        taskMapper.update(null, new LambdaUpdateWrapper<ImageTaskEntity>()
                .set(ImageTaskEntity::getStatus, "FAILED")
                .set(ImageTaskEntity::getErrorMessage, message)
                .set(ImageTaskEntity::getCompletedAt, now())
                .set(ImageTaskEntity::getUpdatedAt, now())
                .eq(ImageTaskEntity::getId, taskId)
                .eq(ImageTaskEntity::getStatus, "GENERATING"));
    }

    public ResultRecord findResult(String taskId, long resultId) {
        return toResultRecord(resultMapper.selectOne(new LambdaQueryWrapper<ImageTaskResultEntity>()
                .eq(ImageTaskResultEntity::getTaskId, taskId)
                .eq(ImageTaskResultEntity::getId, resultId)
                .last("limit 1")));
    }

    public List<ResultRecord> completedResults(String taskId) {
        return resultMapper.selectList(new LambdaQueryWrapper<ImageTaskResultEntity>()
                        .select(
                                ImageTaskResultEntity::getId,
                                ImageTaskResultEntity::getTaskId,
                                ImageTaskResultEntity::getResultType,
                                ImageTaskResultEntity::getItemIndex,
                                ImageTaskResultEntity::getParentResultId,
                                ImageTaskResultEntity::getVersionIndex,
                                ImageTaskResultEntity::getStatus,
                                ImageTaskResultEntity::getPrompt,
                                ImageTaskResultEntity::getImageUrl,
                                ImageTaskResultEntity::getImagePath,
                                ImageTaskResultEntity::getEditSuggestion,
                                ImageTaskResultEntity::getErrorMessage
                        )
                        .eq(ImageTaskResultEntity::getTaskId, taskId)
                        .eq(ImageTaskResultEntity::getStatus, "COMPLETED")
                        .orderByAsc(ImageTaskResultEntity::getResultType)
                        .orderByAsc(ImageTaskResultEntity::getItemIndex)
                        .orderByAsc(ImageTaskResultEntity::getVersionIndex)
                        .orderByAsc(ImageTaskResultEntity::getId))
                .stream()
                .map(this::toResultRecord)
                .toList();
    }

    public int nextVersionIndex(String taskId, String resultType, int itemIndex) {
        return resultMapper.selectList(new LambdaQueryWrapper<ImageTaskResultEntity>()
                        .eq(ImageTaskResultEntity::getTaskId, taskId)
                        .eq(ImageTaskResultEntity::getResultType, resultType)
                        .eq(ImageTaskResultEntity::getItemIndex, itemIndex))
                .stream()
                .map(ImageTaskResultEntity::getVersionIndex)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .map(value -> Math.max(2, value + 1))
                .orElse(2);
    }

    public int countStoredImages(String taskId) {
        return Math.toIntExact(fileMapper.selectCount(new LambdaQueryWrapper<ImageTaskFileEntity>()
                .eq(ImageTaskFileEntity::getTaskId, taskId)));
    }

    public int countStoredImages(String taskId, String fileGroup) {
        return Math.toIntExact(fileMapper.selectCount(new LambdaQueryWrapper<ImageTaskFileEntity>()
                .eq(ImageTaskFileEntity::getTaskId, taskId)
                .eq(ImageTaskFileEntity::getFileGroup, fileGroup)));
    }

    public List<StoredUploadImage> readStoredImages(String taskId, String fileGroup) {
        return fileMapper.selectList(new LambdaQueryWrapper<ImageTaskFileEntity>()
                        .eq(ImageTaskFileEntity::getTaskId, taskId)
                        .eq(ImageTaskFileEntity::getFileGroup, fileGroup)
                        .orderByAsc(ImageTaskFileEntity::getSortOrder)
                        .orderByAsc(ImageTaskFileEntity::getId))
                .stream()
                .map(entity -> new StoredUploadImage(entity.getFileName(), entity.getContentType(), entity.getContent()))
                .toList();
    }

    public ImageTaskPreviewFile taskFilePreview(String taskId, long fileId) {
        ImageTaskFileEntity entity = fileMapper.selectOne(new LambdaQueryWrapper<ImageTaskFileEntity>()
                .eq(ImageTaskFileEntity::getTaskId, taskId)
                .eq(ImageTaskFileEntity::getId, fileId)
                .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("任务文件不存在：" + fileId);
        }
        Thumbnail thumbnail = cachedFileThumbnail(entity);
        return new ImageTaskPreviewFile(
                normalizeText(entity.getFileName(), "preview.jpg"),
                normalizeText(thumbnail.contentType(), "image/jpeg"),
                thumbnail.bytes() == null ? new byte[0] : thumbnail.bytes()
        );
    }

    public void saveAnalysisAndPrompts(String taskId, Map<String, String> analysis, String finalMainPrompt, String finalIntroPrompt) {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId(taskId);
        task.setAnalysisJson(toJson(analysis));
        task.setFinalMainPrompt(finalMainPrompt);
        task.setFinalIntroPrompt(finalIntroPrompt);
        task.setUpdatedAt(now());
        taskMapper.updateById(task);
    }

    public void saveScenePrompts(
            String taskId,
            List<ImageScenePromptService.ScenePrompt> mainScenes,
            List<ImageScenePromptService.ScenePrompt> introScenes
    ) {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId(taskId);
        task.setMainScenesJson(toJson(toSceneViews(mainScenes)));
        task.setIntroScenesJson(toJson(toSceneViews(introScenes)));
        task.setUpdatedAt(now());
        taskMapper.updateById(task);
    }

    public void savePartialAnalysis(String taskId, Map<String, String> analysis) {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId(taskId);
        task.setAnalysisJson(toJson(analysis));
        task.setUpdatedAt(now());
        taskMapper.updateById(task);
    }

    public void clearResults(String taskId) {
        resultMapper.delete(new LambdaQueryWrapper<ImageTaskResultEntity>()
                .eq(ImageTaskResultEntity::getTaskId, taskId));
        resultStorageService.deleteTaskImages(taskId);
    }

    public long insertResult(String taskId, String resultType, int index, String prompt, String status) {
        return insertResult(taskId, resultType, index, prompt, status, null, 1, null);
    }

    public long insertResult(
            String taskId,
            String resultType,
            int index,
            String prompt,
            String status,
            Long parentResultId,
            int versionIndex,
            String editSuggestion
    ) {
        ImageTaskResultEntity result = new ImageTaskResultEntity();
        result.setTaskId(taskId);
        result.setResultType(resultType);
        result.setItemIndex(index);
        result.setStatus(status);
        result.setPrompt(prompt);
        result.setParentResultId(parentResultId);
        result.setVersionIndex(Math.max(1, versionIndex));
        result.setEditSuggestion(editSuggestion);
        resultMapper.insert(result);
        return result.getId();
    }

    public void markResultGenerating(long resultId) {
        int updated = resultMapper.update(null, new LambdaUpdateWrapper<ImageTaskResultEntity>()
                .set(ImageTaskResultEntity::getStatus, "GENERATING")
                .set(ImageTaskResultEntity::getUpdatedAt, now())
                .eq(ImageTaskResultEntity::getId, resultId)
                .eq(ImageTaskResultEntity::getStatus, "QUEUED"));
        if (updated == 0) {
            throw new IllegalStateException("生成结果状态不可用，请重试：" + resultId);
        }
    }

    public boolean completeResult(long resultId, ImageGenerationService.GeneratedImage generatedImage) {
        ImageTaskResultEntity result = resultMapper.selectById(resultId);
        if (result == null || "PAUSED".equals(result.getStatus())) {
            return false;
        }
        ImageTaskResultStorageService.StoredResultImage storedImage = resultStorageService.saveGeneratedImage(
                result.getTaskId(),
                resultId,
                generatedImage
        );
        String imageUrl = storedImage == null
                ? generatedImage.imageUrl()
                : resultImageUrl(result.getTaskId(), resultId);
        return resultMapper.update(null, new LambdaUpdateWrapper<ImageTaskResultEntity>()
                .set(ImageTaskResultEntity::getStatus, "COMPLETED")
                .set(ImageTaskResultEntity::getImageUrl, imageUrl)
                .set(ImageTaskResultEntity::getImageBase64, null)
                .set(ImageTaskResultEntity::getImagePath, storedImage == null ? result.getImagePath() : storedImage.relativePath())
                .set(ImageTaskResultEntity::getRevisedPrompt, generatedImage.revisedPrompt())
                .set(ImageTaskResultEntity::getRawResponse, generatedImage.rawResponse())
                .set(ImageTaskResultEntity::getErrorMessage, null)
                .set(ImageTaskResultEntity::getUpdatedAt, now())
                .eq(ImageTaskResultEntity::getId, resultId)
                .ne(ImageTaskResultEntity::getStatus, "PAUSED")) > 0;
    }

    public void failResult(long resultId, Exception ex) {
        resultMapper.update(null, new LambdaUpdateWrapper<ImageTaskResultEntity>()
                .set(ImageTaskResultEntity::getStatus, "FAILED")
                .set(ImageTaskResultEntity::getErrorMessage, abbreviate(ex.getMessage(), 4000))
                .set(ImageTaskResultEntity::getUpdatedAt, now())
                .eq(ImageTaskResultEntity::getId, resultId)
                .notIn(ImageTaskResultEntity::getStatus, List.of("COMPLETED", "PAUSED")));
    }

    public void updateTaskState(String taskId, String status, String errorMessage, boolean markStarted, boolean markCompleted) {
        TaskRecord record = findTask(taskId);
        if (record == null) {
            return;
        }
        if ("FAILED".equals(status) && "PAUSED".equals(record.status())) {
            return;
        }
        if ("PAUSED".equals(record.status())) {
            return;
        }
        if (!"FAILED".equals(status) && !"COMPLETED".equals(status) && "FAILED".equals(record.status())) {
            return;
        }
        Timestamp now = now();
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId(taskId);
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(now);
        if (markStarted && (record.startedAt() == null || record.startedAt().isBlank())) {
            task.setStartedAt(now);
        }
        if (markCompleted) {
            task.setCompletedAt(now);
        }
        taskMapper.updateById(task);
    }

    @Transactional
    public void resetTaskForRetry(String taskId) {
        clearResults(taskId);
        taskMapper.update(null, new LambdaUpdateWrapper<ImageTaskEntity>()
                .set(ImageTaskEntity::getStatus, "QUEUED")
                .set(ImageTaskEntity::getErrorMessage, null)
                .set(ImageTaskEntity::getAnalysisJson, null)
                .set(ImageTaskEntity::getFinalMainPrompt, null)
                .set(ImageTaskEntity::getFinalIntroPrompt, null)
                .set(ImageTaskEntity::getStartedAt, null)
                .set(ImageTaskEntity::getCompletedAt, null)
                .set(ImageTaskEntity::getUpdatedAt, now())
                .eq(ImageTaskEntity::getId, taskId));
    }

    public void failTask(String taskId, Exception ex) {
        try {
            updateTaskState(taskId, "FAILED", abbreviate(ex.getMessage(), 4000), false, true);
        } catch (Exception sqlEx) {
            LOG.error("image.task.fail-state.failed taskId={}", taskId, sqlEx);
        }
    }

    public void deleteTask(String taskId) {
        taskMapper.deleteById(taskId);
        resultStorageService.deleteTaskImages(taskId);
    }

    public void ensureTables() {
        if (tablesEnsured) {
            return;
        }
        synchronized (this) {
            if (tablesEnsured) {
                return;
            }
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    create table if not exists image_tasks (
                      id varchar(64) primary key,
                      product_name varchar(255) not null,
                      status varchar(32) not null,
                      payload_json longtext not null,
                      analysis_json longtext null,
                      main_scenes_json longtext null,
                      intro_scenes_json longtext null,
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
                addColumnIfMissing(connection, "image_tasks", "main_scenes_json", "longtext null");
                addColumnIfMissing(connection, "image_tasks", "intro_scenes_json", "longtext null");
                statement.executeUpdate("""
                    create table if not exists image_task_files (
                      id bigint primary key auto_increment,
                      task_id varchar(64) not null,
                      file_group varchar(32) not null,
                      file_name varchar(255) not null,
                      content_type varchar(128) not null,
                      file_size bigint not null,
                      content longblob not null,
                      thumbnail longblob null,
                      thumbnail_content_type varchar(128) null,
                      sort_order int not null default 0,
                      created_at timestamp(3) not null default current_timestamp(3),
                      index idx_image_task_files_task_group (task_id, file_group),
                      constraint fk_image_task_files_task foreign key (task_id) references image_tasks(id) on delete cascade
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
                addColumnIfMissing(connection, "image_task_files", "thumbnail", "longblob null");
                addColumnIfMissing(connection, "image_task_files", "thumbnail_content_type", "varchar(128) null");
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
                      image_path varchar(500) null,
                      revised_prompt longtext null,
                      raw_response longtext null,
                      error_message text null,
                      created_at timestamp(3) not null default current_timestamp(3),
                      updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
                      index idx_image_task_results_task (task_id),
                      constraint fk_image_task_results_task foreign key (task_id) references image_tasks(id) on delete cascade
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
                addColumnIfMissing(connection, "image_task_results", "parent_result_id", "bigint null");
                addColumnIfMissing(connection, "image_task_results", "version_index", "int not null default 1");
                addColumnIfMissing(connection, "image_task_results", "edit_suggestion", "text null");
                addColumnIfMissing(connection, "image_task_results", "image_path", "varchar(500) null");
                tablesEnsured = true;
            } catch (SQLException ex) {
                throw new IllegalStateException("初始化任务队列表失败", ex);
            }
        }
    }

    private TaskRecord toTaskRecord(ImageTaskEntity entity) {
        if (entity == null) {
            return null;
        }
        return new TaskRecord(
                entity.getId(),
                entity.getProductName(),
                entity.getStatus(),
                entity.getPayloadJson(),
                parsePayload(entity.getPayloadJson()),
                parseAnalysis(entity.getAnalysisJson()),
                parseSceneViews(entity.getMainScenesJson()),
                parseSceneViews(entity.getIntroScenesJson()),
                entity.getFinalMainPrompt(),
                entity.getFinalIntroPrompt(),
                entity.getThumbnail(),
                entity.getThumbnailContentType(),
                entity.getThumbnailFileName(),
                valueOrZero(entity.getRealPhotoCount()),
                valueOrZero(entity.getPackageImageCount()),
                valueOrZero(entity.getTemplateCount()),
                entity.getErrorMessage(),
                formatTime(entity.getCreatedAt()),
                formatTime(entity.getUpdatedAt()),
                formatTime(entity.getStartedAt()),
                formatTime(entity.getCompletedAt())
        );
    }

    private ResultRecord toResultRecord(ImageTaskResultEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ResultRecord(
                entity.getId(),
                entity.getTaskId(),
                entity.getResultType(),
                valueOrZero(entity.getItemIndex()),
                entity.getParentResultId(),
                Math.max(1, valueOrZero(entity.getVersionIndex())),
                entity.getStatus(),
                entity.getPrompt(),
                entity.getImageUrl(),
                entity.getImageBase64(),
                entity.getImagePath(),
                entity.getEditSuggestion(),
                entity.getErrorMessage()
        );
    }

    private ImageTaskSummary toSummary(TaskRecord record) {
        return toSummary(record, progressStats(record));
    }

    private ImageTaskSummary toSummary(TaskRecord record, ResultStats generationStats) {
        ResultStats stats = progressStats(record, generationStats);
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

    private ImageTaskDetail toDetail(TaskRecord record) {
        ResultStats stats = progressStats(record);
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
                listTaskFiles(record.id()),
                record.analysis(),
                record.mainScenes(),
                record.introScenes(),
                detailFinalPrompt(record, "主图"),
                detailFinalPrompt(record, "介绍图"),
                listResults(record.id()),
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
                ? imageTaskPromptBuilder.generationBasePrompt(record.payload().mainPrompt(), settings.mainPrompt(), settings.analysisPrompt())
                : imageTaskPromptBuilder.generationBasePrompt(record.payload().introPrompt(), settings.introPrompt(), settings.analysisPrompt());
        TargetTemplateService.TargetTemplateRecord targetTemplate = "主图".equals(imageType)
                ? resolveTargetTemplate(record.payload().mainTargetTemplateId(), "MAIN", "主图")
                : resolveTargetTemplate(record.payload().introTargetTemplateId(), "INTRO", "介绍图");
        return imageTaskPromptBuilder.buildGenerationPrompt(imageType, basePrompt, record.payload(), analysis, UploadMaterialContext.unknown(), targetTemplate);
    }

    private List<ImageTaskResultView> listResults(String taskId) {
        return resultMapper.selectList(new LambdaQueryWrapper<ImageTaskResultEntity>()
                        .select(
                                ImageTaskResultEntity::getId,
                                ImageTaskResultEntity::getTaskId,
                                ImageTaskResultEntity::getResultType,
                                ImageTaskResultEntity::getItemIndex,
                                ImageTaskResultEntity::getParentResultId,
                                ImageTaskResultEntity::getVersionIndex,
                                ImageTaskResultEntity::getStatus,
                                ImageTaskResultEntity::getPrompt,
                                ImageTaskResultEntity::getImageUrl,
                                ImageTaskResultEntity::getImagePath,
                                ImageTaskResultEntity::getEditSuggestion,
                                ImageTaskResultEntity::getErrorMessage,
                                ImageTaskResultEntity::getCreatedAt,
                                ImageTaskResultEntity::getUpdatedAt
                        )
                        .eq(ImageTaskResultEntity::getTaskId, taskId)
                        .orderByAsc(ImageTaskResultEntity::getResultType)
                        .orderByAsc(ImageTaskResultEntity::getItemIndex)
                        .orderByAsc(ImageTaskResultEntity::getVersionIndex)
                        .orderByAsc(ImageTaskResultEntity::getId))
                .stream()
                .map(result -> new ImageTaskResultView(
                        result.getId(),
                        result.getResultType(),
                        valueOrZero(result.getItemIndex()),
                        result.getParentResultId(),
                        valueOrDefault(result.getVersionIndex(), 1),
                        result.getStatus(),
                        statusText(result.getStatus()),
                        result.getPrompt(),
                        resultImageUrl(result),
                        null,
                        null,
                        result.getEditSuggestion(),
                        result.getErrorMessage(),
                        formatTime(result.getCreatedAt()),
                        formatTime(result.getUpdatedAt())
                ))
                .toList();
    }

    private Map<String, List<ImageTaskFileView>> listTaskFiles(String taskId) {
        Map<String, List<ImageTaskFileView>> files = new LinkedHashMap<>();
        files.put("实拍图", new ArrayList<>());
        files.put("排版图", new ArrayList<>());
        files.put("Logo图", new ArrayList<>());
        files.put("壁纸图", new ArrayList<>());
        for (ImageTaskFileEntity entity : fileMapper.selectList(new LambdaQueryWrapper<ImageTaskFileEntity>()
                .select(
                        ImageTaskFileEntity::getId,
                        ImageTaskFileEntity::getFileGroup,
                        ImageTaskFileEntity::getFileName,
                        ImageTaskFileEntity::getContentType,
                        ImageTaskFileEntity::getFileSize
                )
                .eq(ImageTaskFileEntity::getTaskId, taskId)
                .orderByAsc(ImageTaskFileEntity::getFileGroup)
                .orderByAsc(ImageTaskFileEntity::getSortOrder)
                .orderByAsc(ImageTaskFileEntity::getId))) {
            String group = entity.getFileGroup();
            String groupName = fileGroupName(group);
            files.computeIfAbsent(groupName, ignored -> new ArrayList<>()).add(new ImageTaskFileView(
                    entity.getId(),
                    group,
                    groupName,
                    entity.getFileName(),
                    entity.getContentType(),
                    valueOrZero(entity.getFileSize()),
                    filePreviewUrl(taskId, entity.getId())
            ));
        }
        return files;
    }

    public ImageTaskPreviewFile resultImage(String taskId, long resultId) {
        ImageTaskResultEntity result = resultMapper.selectOne(new LambdaQueryWrapper<ImageTaskResultEntity>()
                .eq(ImageTaskResultEntity::getTaskId, taskId)
                .eq(ImageTaskResultEntity::getId, resultId)
                .last("limit 1"));
        if (result == null) {
            throw new IllegalArgumentException("生成结果不存在：" + resultId);
        }
        String fileName = resultFileName(result);
        ImageTaskPreviewFile storedFile = resultStorageService.readStoredImage(result.getImagePath(), fileName);
        if (storedFile != null) {
            return storedFile;
        }
        DecodedImage decoded = resultStorageService.decodeImageBase64(result.getImageBase64());
        if (decoded.bytes().length == 0) {
            throw new IllegalStateException("生成结果没有可用的本地图片文件：" + resultId);
        }
        ImageGenerationService.GeneratedImage generatedImage = new ImageGenerationService.GeneratedImage(
                result.getImageUrl(),
                result.getImageBase64(),
                result.getRevisedPrompt(),
                result.getRawResponse()
        );
        ImageTaskResultStorageService.StoredResultImage storedImage = resultStorageService.saveGeneratedImage(taskId, resultId, generatedImage);
        resultMapper.update(null, new LambdaUpdateWrapper<ImageTaskResultEntity>()
                .set(ImageTaskResultEntity::getImageUrl, resultImageUrl(taskId, resultId))
                .set(ImageTaskResultEntity::getImageBase64, null)
                .set(ImageTaskResultEntity::getImagePath, storedImage.relativePath())
                .eq(ImageTaskResultEntity::getId, resultId));
        return new ImageTaskPreviewFile(fileName, decoded.contentType(), decoded.bytes());
    }

    private ResultStats progressStats(TaskRecord record) {
        return progressStats(record, generationResultStats(record.id()));
    }

    private ResultStats progressStats(TaskRecord record, ResultStats generationStats) {
        if (generationStats == null) {
            generationStats = ResultStats.empty();
        }
        int analysisTotal = record.realPhotoCount() + record.templateCount();
        int expectedGenerationTotal = positive(record.payload().mainImageCount()) + positive(record.payload().introImageCount());
        int generationTotal = Math.max(generationStats.total(), expectedGenerationTotal);
        int analysisCompleted = analysisCompletedCount(record, analysisTotal);
        int total = analysisTotal + generationTotal;
        int completed = analysisCompleted + generationStats.completed();
        if ("COMPLETED".equals(record.status())) {
            completed = total;
        }
        return new ResultStats(Math.min(completed, total), total);
    }

    private Map<String, ResultStats> generationResultStats(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ResultStats> stats = new LinkedHashMap<>();
        for (String taskId : taskIds) {
            stats.put(taskId, ResultStats.empty());
        }
        List<ImageTaskResultEntity> results = resultMapper.selectList(new LambdaQueryWrapper<ImageTaskResultEntity>()
                .select(ImageTaskResultEntity::getTaskId, ImageTaskResultEntity::getStatus)
                .in(ImageTaskResultEntity::getTaskId, taskIds));
        for (ImageTaskResultEntity result : results) {
            String taskId = result.getTaskId();
            if (taskId == null) {
                continue;
            }
            ResultStats current = stats.getOrDefault(taskId, ResultStats.empty());
            int completed = current.completed() + ("COMPLETED".equals(result.getStatus()) ? 1 : 0);
            stats.put(taskId, new ResultStats(completed, current.total() + 1));
        }
        return stats;
    }

    private ResultStats generationResultStats(String taskId) {
        List<ImageTaskResultEntity> results = resultMapper.selectList(new LambdaQueryWrapper<ImageTaskResultEntity>()
                .select(ImageTaskResultEntity::getStatus)
                .eq(ImageTaskResultEntity::getTaskId, taskId));
        int completed = (int) results.stream().filter(result -> "COMPLETED".equals(result.getStatus())).count();
        return new ResultStats(completed, results.size());
    }

    private int analysisCompletedCount(TaskRecord record, int analysisTotal) {
        if (analysisTotal == 0) {
            return 0;
        }
        if ("GENERATING".equals(record.status()) || "COMPLETED".equals(record.status())) {
            return analysisTotal;
        }
        return Math.min(analysisTotal, analyzedStoredImageCount(record, record.analysis()));
    }

    private int analyzedStoredImageCount(TaskRecord record, Map<String, String> analysis) {
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
                count += storedImageCount(record, fileGroup);
            }
        }
        return count;
    }

    private int storedImageCount(TaskRecord record, String fileGroup) {
        return switch (fileGroup == null ? "" : fileGroup) {
            case "realPhoto" -> record.realPhotoCount();
            case "template" -> record.templateCount();
            default -> countStoredImages(record.id(), fileGroup);
        };
    }

    private void addColumnIfMissing(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, tableName, null)) {
            while (columns.next()) {
                if (columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("alter table " + tableName + " add column " + columnName + " " + definition);
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

    private List<ImageTaskSceneView> parseSceneViews(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<ImageTaskSceneView> scenes = objectMapper.readValue(value, new TypeReference<>() {
            });
            return scenes == null ? List.of() : scenes;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private List<ImageTaskSceneView> toSceneViews(List<ImageScenePromptService.ScenePrompt> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return List.of();
        }
        return scenes.stream()
                .map(scene -> new ImageTaskSceneView(scene.index(), scene.sceneTitle(), scene.prompt()))
                .toList();
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

    private TargetTemplateService.TargetTemplateRecord resolveTargetTemplate(Long templateId, String expectedType, String label) {
        TargetTemplateService.TargetTemplateRecord template = targetTemplateService.findMetadataRecord(templateId);
        if (template == null) {
            return null;
        }
        if (!expectedType.equals(template.templateType())) {
            throw new IllegalArgumentException(label + "只能选择" + label + "参考风格图：" + template.name());
        }
        return template;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化任务数据失败", ex);
        }
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private int countGroup(List<StoredTaskFile> files, String fileGroup) {
        return (int) files.stream().filter(file -> fileGroup.equals(file.fileGroup())).count();
    }

    private Map<String, Integer> fileSummary(TaskRecord record) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("实拍图", record.realPhotoCount());
        summary.put("排版图", record.templateCount());
        return summary;
    }

    private String fileGroupName(String fileGroup) {
        return switch (fileGroup == null ? "" : fileGroup) {
            case "realPhoto" -> "实拍图";
            case "template" -> "排版图";
            case "logo" -> "Logo图";
            case "wallpaper" -> "壁纸图";
            default -> fileGroup;
        };
    }

    private String fileGroupCode(String label) {
        return switch (label == null ? "" : label) {
            case "实拍图" -> "realPhoto";
            case "排版图" -> "template";
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

    private Thumbnail cachedFileThumbnail(ImageTaskFileEntity entity) {
        if (entity.getThumbnail() != null && entity.getThumbnail().length > 0) {
            return new Thumbnail(entity.getThumbnail(), entity.getThumbnailContentType(), entity.getFileName());
        }
        if (entity.getContent() == null || entity.getContent().length == 0) {
            return new Thumbnail(null, null, entity.getFileName());
        }
        StoredTaskFile storedFile = new StoredTaskFile(
                entity.getFileGroup(),
                entity.getFileName(),
                entity.getContentType(),
                entity.getContent(),
                valueOrZero(entity.getSortOrder())
        );
        Thumbnail thumbnail = imageTaskFileService.createThumbnail(List.of(storedFile));
        if (thumbnail.bytes() != null && thumbnail.bytes().length > 0) {
            fileMapper.update(null, new LambdaUpdateWrapper<ImageTaskFileEntity>()
                    .set(ImageTaskFileEntity::getThumbnail, thumbnail.bytes())
                    .set(ImageTaskFileEntity::getThumbnailContentType, thumbnail.contentType())
                    .eq(ImageTaskFileEntity::getId, entity.getId()));
        }
        return thumbnail;
    }

    private String filePreviewUrl(String taskId, Long fileId) {
        if (fileId == null) {
            return "";
        }
        return "/api/tasks/" + taskId + "/files/" + fileId + "/preview";
    }

    private String resultImageUrl(ImageTaskResultEntity result) {
        if (result.getImageUrl() != null && !result.getImageUrl().isBlank() && result.getImagePath() == null) {
            return result.getImageUrl();
        }
        if (result.getId() == null || result.getTaskId() == null || result.getTaskId().isBlank()) {
            return result.getImageUrl();
        }
        if (result.getImagePath() != null && !result.getImagePath().isBlank()) {
            return resultImageUrl(result.getTaskId(), result.getId());
        }
        if (result.getImageBase64() != null && !result.getImageBase64().isBlank()) {
            return resultImageUrl(result.getTaskId(), result.getId());
        }
        return result.getImageUrl();
    }

    private String resultImageUrl(String taskId, long resultId) {
        return "/api/tasks/" + taskId + "/results/" + resultId + "/image";
    }

    private String resultFileName(ImageTaskResultEntity result) {
        String extension = "png";
        if (result.getImagePath() != null && result.getImagePath().contains(".")) {
            extension = result.getImagePath().substring(result.getImagePath().lastIndexOf('.') + 1);
        }
        return result.getResultType() + "-" + valueOrZero(result.getItemIndex()) + "-v"
                + valueOrDefault(result.getVersionIndex(), 1) + "." + extension;
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

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private int positive(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
}
