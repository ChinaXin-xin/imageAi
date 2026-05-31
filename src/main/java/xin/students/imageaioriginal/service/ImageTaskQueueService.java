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
import xin.students.imageaioriginal.model.DefaultPromptSettings;
import xin.students.imageaioriginal.model.ImageTaskDetail;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ImageTaskQueueService {

    private static final Logger LOG = LoggerFactory.getLogger(ImageTaskQueueService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int THUMB_MAX_EDGE = 320;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final DefaultPromptSettingsService defaultPromptSettingsService;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final ImageGenerationService imageGenerationService;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "image-task-worker");
        thread.setDaemon(true);
        return thread;
    });

    public ImageTaskQueueService(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DefaultPromptSettingsService defaultPromptSettingsService,
            UploadImageAnalysisService uploadImageAnalysisService,
            ImageGenerationService imageGenerationService
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.defaultPromptSettingsService = defaultPromptSettingsService;
        this.uploadImageAnalysisService = uploadImageAnalysisService;
        this.imageGenerationService = imageGenerationService;
    }

    @PostConstruct
    public void initialize() {
        ensureTables();
        resumeUnfinishedTasks();
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    public ImageTaskDetail createTask(
            String payloadJson,
            List<MultipartFile> realPhotoFiles,
            List<MultipartFile> packageImageFiles,
            List<MultipartFile> templateFiles
    ) {
        ensureTables();
        ImageTaskPayload payload = normalizePayload(parsePayload(payloadJson));
        String taskId = UUID.randomUUID().toString();
        List<StoredTaskFile> files = new ArrayList<>();
        files.addAll(toStoredTaskFiles("realPhoto", realPhotoFiles));
        files.addAll(toStoredTaskFiles("packageImage", packageImageFiles));
        files.addAll(toStoredTaskFiles("template", templateFiles));
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

    private void startProcessing(String taskId) {
        CompletableFuture.runAsync(() -> processTask(taskId), executorService);
    }

    private void resumeUnfinishedTasks() {
        List<String> taskIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select id from image_tasks where status in ('QUEUED', 'ANALYZING', 'GENERATING')
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

    private void processTask(String taskId) {
        try {
            TaskRecord record = findTask(taskId);
            if (record == null || "COMPLETED".equals(record.status()) || "FAILED".equals(record.status())) {
                return;
            }
            updateTaskState(taskId, "ANALYZING", null, true, false);

            Map<String, String> analysis = analyzeUploadedFiles(taskId);
            if (requiresGeneration(record.payload()) && analysis.isEmpty()) {
                throw new IllegalStateException("生成主图或介绍图前必须先深析上传图，请至少上传实拍图、包装图或模板图。");
            }

            DefaultPromptSettings settings = defaultPromptSettingsService.getSettings();
            String finalMainPrompt = buildGenerationPrompt(
                    "主图",
                    normalizeText(record.payload().mainPrompt(), settings.mainPrompt()),
                    record.payload(),
                    analysis
            );
            String finalIntroPrompt = buildGenerationPrompt(
                    "介绍图",
                    normalizeText(record.payload().introPrompt(), settings.introPrompt()),
                    record.payload(),
                    analysis
            );
            saveAnalysisAndPrompts(taskId, analysis, finalMainPrompt, finalIntroPrompt);
            clearResults(taskId);
            updateTaskState(taskId, "GENERATING", null, false, false);

            int total = 0;
            int mainCount = positive(record.payload().mainImageCount());
            int introCount = positive(record.payload().introImageCount());
            for (int index = 1; index <= mainCount; index++) {
                total++;
                generateOne(taskId, "主图", index, mainCount, finalMainPrompt, record.payload());
            }
            for (int index = 1; index <= introCount; index++) {
                total++;
                generateOne(taskId, "介绍图", index, introCount, finalIntroPrompt, record.payload());
            }

            if (total == 0) {
                LOG.info("image.task.no-generation taskId={}", taskId);
            }
            updateTaskState(taskId, "COMPLETED", null, false, true);
        } catch (Exception ex) {
            LOG.error("image.task.failed taskId={} message={}", taskId, ex.getMessage(), ex);
            failTask(taskId, ex);
        }
    }

    private void generateOne(
            String taskId,
            String resultType,
            int index,
            int total,
            String basePrompt,
            ImageTaskPayload payload
    ) {
        String prompt = basePrompt + "\n\n【当前生成】" + resultType + "第 " + index + " / " + total + " 张。";
        long resultId = insertResult(taskId, resultType, index, prompt);
        try {
            ImageGenerationService.GeneratedImage generatedImage = imageGenerationService.generate(
                    taskId,
                    resultType,
                    index,
                    prompt,
                    positiveOrDefault(payload.customWidth(), 1500),
                    positiveOrDefault(payload.customHeight(), 1500)
            );
            completeResult(resultId, generatedImage);
        } catch (Exception ex) {
            failResult(resultId, ex);
            throw ex;
        }
    }

    private Map<String, String> analyzeUploadedFiles(String taskId) {
        Map<String, String> analysis = new LinkedHashMap<>();
        String prompt = defaultPromptSettingsService.getSettings().analysisPrompt();
        analyzeGroup(taskId, "realPhoto", "实拍图", prompt, analysis);
        analyzeGroup(taskId, "packageImage", "包装图", prompt, analysis);
        analyzeGroup(taskId, "template", "模板图", prompt, analysis);
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
    }

    private String buildGenerationPrompt(
            String imageType,
            String basePrompt,
            ImageTaskPayload payload,
            Map<String, String> analysis
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(normalizeText(basePrompt, "生成跨境电商图片。")).append("\n\n");
        builder.append("【任务类型】").append(imageType).append("\n");
        builder.append("【商品名称】").append(normalizeText(payload.productName(), "未命名商品")).append("\n");
        builder.append("【平台】").append(normalizeText(payload.platform(), "Amazon")).append("\n");
        builder.append("【尺寸】")
                .append(positiveOrDefault(payload.customWidth(), 1500))
                .append("x")
                .append(positiveOrDefault(payload.customHeight(), 1500))
                .append("\n");
        builder.append("【语言】").append(normalizeText(payload.language(), "英文")).append("\n");
        builder.append("【机型】").append(normalizeText(payload.model(), "根据上传图自动识别")).append("\n");
        builder.append("【手机颜色】").append(normalizeText(payload.phoneColor(), "自动")).append("\n");
        builder.append("【设计风格】").append(normalizeText(payload.style(), "自动")).append("\n");
        builder.append("【布局模式】").append(normalizeText(payload.layout(), "自动")).append("\n");
        builder.append("【Logo】").append(normalizeText(payload.logoName(), "按上传图或品牌名识别")).append("\n");
        builder.append("【壁纸】").append(normalizeText(payload.wallpaperName(), "按上传图或需求匹配")).append("\n");
        builder.append("【卖点】").append(joinList(payload.sellingPoints())).append("\n");
        builder.append("【套装规格】").append(joinKitSpecs(payload.kitSpecs())).append("\n");
        builder.append("【产品类型】高清=").append(Boolean.TRUE.equals(payload.hdEnabled()))
                .append("，高清数量=").append(positive(payload.hdQuantity()))
                .append("；防窥=").append(Boolean.TRUE.equals(payload.privacyEnabled()))
                .append("，防窥数量=").append(positive(payload.privacyQuantity()))
                .append("\n\n");
        builder.append("【生成前强制深析上传图结果】\n");
        analysis.forEach((label, result) -> builder.append("[").append(label).append("]\n").append(result).append("\n"));
        builder.append("\n必须严格结合以上深析结果生成，不要遗漏可见细节，不要编造深析结果中没有的信息。");
        return builder.toString();
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
        return new ImageTaskPayload(
                productName,
                normalizeNullable(payload.model()),
                normalizeText(payload.platform(), "Amazon"),
                normalizeText(payload.ratio(), "1500:1500"),
                positiveOrDefault(payload.customWidth(), 1500),
                positiveOrDefault(payload.customHeight(), 1500),
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
                payload.kitSpecs() == null ? List.of() : payload.kitSpecs()
        );
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
        ResultStats stats = resultStats(connection, record.id());
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
        ResultStats stats = resultStats(connection, record.id());
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
                record.analysis(),
                record.finalMainPrompt(),
                record.finalIntroPrompt(),
                listResults(connection, record.id()),
                stats.completed(),
                stats.total(),
                record.errorMessage()
        );
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
                            resultSet.getString("revised_prompt"),
                            resultSet.getString("error_message"),
                            formatTime(resultSet.getTimestamp("created_at")),
                            formatTime(resultSet.getTimestamp("updated_at"))
                    ));
                }
            }
        }
        return results;
    }

    private ResultStats resultStats(Connection connection, String taskId) throws SQLException {
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

    private List<StoredUploadImage> readStoredImages(String taskId, String fileGroup) {
        try (Connection connection = dataSource.getConnection()) {
            return readStoredImages(connection, taskId, fileGroup);
        } catch (SQLException ex) {
            throw new IllegalStateException("读取任务素材失败：" + taskId + "/" + fileGroup, ex);
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

    private long insertResult(String taskId, String resultType, int index, String prompt) {
        try (Connection connection = dataSource.getConnection()) {
            return insertResult(connection, taskId, resultType, index, prompt);
        } catch (SQLException ex) {
            throw new IllegalStateException("创建生成结果记录失败：" + taskId, ex);
        }
    }

    private long insertResult(Connection connection, String taskId, String resultType, int index, String prompt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into image_task_results (task_id, result_type, item_index, status, prompt)
                values (?, ?, ?, 'GENERATING', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, taskId);
            statement.setString(2, resultType);
            statement.setInt(3, index);
            statement.setString(4, prompt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new IllegalStateException("创建生成结果记录失败");
    }

    private void completeResult(
            long resultId,
            ImageGenerationService.GeneratedImage generatedImage
    ) {
        try (Connection connection = dataSource.getConnection()) {
            completeResult(connection, resultId, generatedImage);
        } catch (SQLException ex) {
            throw new IllegalStateException("保存生成结果失败：" + resultId, ex);
        }
    }

    private void completeResult(
            Connection connection,
            long resultId,
            ImageGenerationService.GeneratedImage generatedImage
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update image_task_results
                set status = 'COMPLETED', image_url = ?, image_base64 = ?, revised_prompt = ?, raw_response = ?,
                    updated_at = current_timestamp(3)
                where id = ?
                """)) {
            statement.setString(1, generatedImage.imageUrl());
            statement.setString(2, generatedImage.imageBase64());
            statement.setString(3, generatedImage.revisedPrompt());
            statement.setString(4, generatedImage.rawResponse());
            statement.setLong(5, resultId);
            statement.executeUpdate();
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
                where id = ?
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
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, errorMessage);
            statement.setBoolean(3, markStarted);
            statement.setBoolean(4, markCompleted);
            statement.setString(5, taskId);
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
                .map(spec -> spec.name() + " x " + positive(spec.quantity()))
                .toList();
        return items.isEmpty() ? "未选择" : String.join("、", items);
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

    private String formatTime(Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDateTime().format(TIME_FORMATTER);
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
