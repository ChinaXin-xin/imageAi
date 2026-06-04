package xin.students.imageaioriginal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.entity.TargetTemplateEntity;
import xin.students.imageaioriginal.mapper.TargetTemplateMapper;
import xin.students.imageaioriginal.model.StoredUploadImage;
import xin.students.imageaioriginal.model.TargetTemplateView;
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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

@Service
public class TargetTemplateService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int THUMB_MAX_EDGE = 360;

    private final DataSource dataSource;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final DefaultPromptSettingsService defaultPromptSettingsService;
    private final TargetTemplateMapper targetTemplateMapper;
    private volatile boolean tableEnsured;

    public TargetTemplateService(
            DataSource dataSource,
            UploadImageAnalysisService uploadImageAnalysisService,
            DefaultPromptSettingsService defaultPromptSettingsService,
            TargetTemplateMapper targetTemplateMapper
    ) {
        this.dataSource = dataSource;
        this.uploadImageAnalysisService = uploadImageAnalysisService;
        this.defaultPromptSettingsService = defaultPromptSettingsService;
        this.targetTemplateMapper = targetTemplateMapper;
    }

    @PostConstruct
    public void initialize() {
        ensureTable();
    }

    public List<TargetTemplateView> listTemplates() {
        ensureTable();
        return targetTemplateMapper.selectList(new LambdaQueryWrapper<TargetTemplateEntity>()
                        .select(
                                TargetTemplateEntity::getId,
                                TargetTemplateEntity::getTemplateType,
                                TargetTemplateEntity::getName,
                                TargetTemplateEntity::getFileName,
                                TargetTemplateEntity::getContentType,
                                TargetTemplateEntity::getFileSize,
                                TargetTemplateEntity::getThumbnail,
                                TargetTemplateEntity::getThumbnailContentType,
                                TargetTemplateEntity::getStyleAnalysis,
                                TargetTemplateEntity::getModel,
                                TargetTemplateEntity::getCreatedAt,
                                TargetTemplateEntity::getUpdatedAt
                        )
                        .orderByAsc(TargetTemplateEntity::getTemplateType)
                        .orderByDesc(TargetTemplateEntity::getCreatedAt)
                        .orderByDesc(TargetTemplateEntity::getId))
                .stream()
                .map(this::toRecord)
                .map(this::toView)
                .toList();
    }

    public TargetTemplateView createTemplate(String templateType, String name, MultipartFile file) {
        ensureTable();
        String normalizedType = normalizeType(templateType);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请先上传参考风格图");
        }
        try {
            byte[] content = file.getBytes();
            String fileName = normalizeText(file.getOriginalFilename(), "target-template.jpg");
            String contentType = normalizeText(file.getContentType(), "image/jpeg");
            StoredUploadImage storedImage = new StoredUploadImage(fileName, contentType, content);
            UploadImageAnalysis analysis = uploadImageAnalysisService.analyzeStyleStored(
                    templateTypeText(normalizedType) + "参考风格图",
                    defaultPromptSettingsService.getSettings().targetTemplatePrompt(),
                    List.of(storedImage)
            );
            Thumbnail thumbnail = createThumbnail(storedImage);

            TargetTemplateEntity entity = new TargetTemplateEntity();
            entity.setTemplateType(normalizedType);
            entity.setName(normalizeText(name, randomTemplateName(normalizedType)));
            entity.setFileName(fileName);
            entity.setContentType(contentType);
            entity.setFileSize((long) content.length);
            entity.setContent(content);
            entity.setThumbnail(thumbnail.bytes());
            entity.setThumbnailContentType(thumbnail.contentType());
            entity.setStyleAnalysis(analysis.result());
            entity.setModel(analysis.model());
            targetTemplateMapper.insert(entity);

            TargetTemplateRecord record = findRecord(entity.getId());
            if (record == null) {
                throw new IllegalStateException("保存参考风格图后读取失败：" + entity.getId());
            }
            return toView(record);
        } catch (IOException ex) {
            throw new IllegalStateException("读取参考风格图失败", ex);
        }
    }

    public void deleteTemplate(long id) {
        ensureTable();
        if (targetTemplateMapper.deleteById(id) == 0) {
            throw new IllegalArgumentException("参考风格图不存在：" + id);
        }
    }

    public TargetTemplateRecord findRecord(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        ensureTable();
        TargetTemplateEntity entity = targetTemplateMapper.selectById(id);
        return entity == null ? null : toRecord(entity);
    }

    public TargetTemplateRecord findMetadataRecord(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        ensureTable();
        TargetTemplateEntity entity = targetTemplateMapper.selectOne(new LambdaQueryWrapper<TargetTemplateEntity>()
                .select(
                        TargetTemplateEntity::getId,
                        TargetTemplateEntity::getTemplateType,
                        TargetTemplateEntity::getName,
                        TargetTemplateEntity::getFileName,
                        TargetTemplateEntity::getContentType,
                        TargetTemplateEntity::getFileSize,
                        TargetTemplateEntity::getThumbnail,
                        TargetTemplateEntity::getThumbnailContentType,
                        TargetTemplateEntity::getStyleAnalysis,
                        TargetTemplateEntity::getModel,
                        TargetTemplateEntity::getCreatedAt,
                        TargetTemplateEntity::getUpdatedAt
                )
                .eq(TargetTemplateEntity::getId, id)
                .last("limit 1"));
        return entity == null ? null : toRecord(entity);
    }

    private TargetTemplateView toView(TargetTemplateRecord record) {
        return new TargetTemplateView(
                record.id(),
                record.templateType(),
                templateTypeText(record.templateType()),
                record.name(),
                record.fileName(),
                record.contentType(),
                record.fileSize(),
                dataUrl(record.thumbnail(), record.thumbnailContentType()),
                record.styleAnalysis(),
                record.model(),
                formatTime(record.createdAt()),
                formatTime(record.updatedAt())
        );
    }

    private TargetTemplateRecord toRecord(TargetTemplateEntity entity) {
        return new TargetTemplateRecord(
                entity.getId(),
                entity.getTemplateType(),
                entity.getName(),
                entity.getFileName(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getContent(),
                entity.getThumbnail(),
                entity.getThumbnailContentType(),
                entity.getStyleAnalysis(),
                entity.getModel(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void ensureTable() {
        if (tableEnsured) {
            return;
        }
        synchronized (this) {
            if (tableEnsured) {
                return;
            }
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    create table if not exists target_templates (
                      id bigint primary key auto_increment,
                      template_type varchar(16) not null,
                      name varchar(255) not null,
                      file_name varchar(255) not null,
                      content_type varchar(128) not null,
                      file_size bigint not null,
                      content longblob not null,
                      thumbnail longblob null,
                      thumbnail_content_type varchar(128) null,
                      style_analysis longtext not null,
                      model varchar(128) null,
                      created_at timestamp(3) not null default current_timestamp(3),
                      updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
                      index idx_target_templates_type_created (template_type, created_at)
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
                addColumnIfMissing(connection, "template_type", "varchar(16) not null default 'MAIN'");
                addColumnIfMissing(connection, "name", "varchar(255) not null default ''");
                addColumnIfMissing(connection, "file_name", "varchar(255) not null default ''");
                addColumnIfMissing(connection, "content_type", "varchar(128) not null default 'image/jpeg'");
                addColumnIfMissing(connection, "file_size", "bigint not null default 0");
                addColumnIfMissing(connection, "content", "longblob null");
                addColumnIfMissing(connection, "thumbnail", "longblob null");
                addColumnIfMissing(connection, "thumbnail_content_type", "varchar(128) null");
                addColumnIfMissing(connection, "style_analysis", "longtext null");
                addColumnIfMissing(connection, "model", "varchar(128) null");
                addColumnIfMissing(connection, "created_at", "timestamp(3) not null default current_timestamp(3)");
                addColumnIfMissing(connection, "updated_at", "timestamp(3) not null default current_timestamp(3) on update current_timestamp(3)");
                tableEnsured = true;
            } catch (SQLException ex) {
                throw new IllegalStateException("初始化参考风格图表失败", ex);
            }
        }
    }

    private void addColumnIfMissing(Connection connection, String columnName, String definition) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "target_templates", columnName)) {
            if (columns.next()) {
                return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("alter table target_templates add column " + columnName + " " + definition);
        }
    }

    private Thumbnail createThumbnail(StoredUploadImage image) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            if (source == null) {
                return new Thumbnail(image.bytes(), image.contentType());
            }
            BufferedImage resized = resizeToRgb(source);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam params = writer.getDefaultWriteParam();
                if (params.canWriteCompressed()) {
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    params.setCompressionQuality(0.78f);
                }
                writer.write(null, new IIOImage(resized, null, null), params);
            } finally {
                writer.dispose();
            }
            return new Thumbnail(output.toByteArray(), "image/jpeg");
        } catch (IOException ex) {
            return new Thumbnail(image.bytes(), image.contentType());
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

    private String dataUrl(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return "data:" + normalizeText(contentType, "image/jpeg") + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
    }

    private String normalizeType(String value) {
        String normalized = normalizeText(value, "").toUpperCase();
        if ("MAIN".equals(normalized) || "INTRO".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("参考风格图类型只能是 MAIN 或 INTRO");
    }

    private String templateTypeText(String value) {
        return "INTRO".equals(value) ? "介绍图" : "主图";
    }

    private String randomTemplateName(String templateType) {
        String prefix = "INTRO".equals(templateType) ? "介绍图参考风格" : "主图参考风格";
        return prefix + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
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

    public record TargetTemplateRecord(
            Long id,
            String templateType,
            String name,
            String fileName,
            String contentType,
            Long fileSize,
            byte[] content,
            byte[] thumbnail,
            String thumbnailContentType,
            String styleAnalysis,
            String model,
            Timestamp createdAt,
            Timestamp updatedAt
    ) {
    }

    private record Thumbnail(byte[] bytes, String contentType) {
    }
}
