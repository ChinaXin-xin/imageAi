package xin.students.imageaioriginal.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
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
import java.util.List;

@Service
public class TargetTemplateService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int THUMB_MAX_EDGE = 360;

    private final DataSource dataSource;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final DefaultPromptSettingsService defaultPromptSettingsService;

    public TargetTemplateService(
            DataSource dataSource,
            UploadImageAnalysisService uploadImageAnalysisService,
            DefaultPromptSettingsService defaultPromptSettingsService
    ) {
        this.dataSource = dataSource;
        this.uploadImageAnalysisService = uploadImageAnalysisService;
        this.defaultPromptSettingsService = defaultPromptSettingsService;
    }

    @PostConstruct
    public void initialize() {
        ensureTable();
    }

    public List<TargetTemplateView> listTemplates() {
        ensureTable();
        List<TargetTemplateView> templates = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from target_templates order by template_type asc, created_at desc, id desc
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                templates.add(toView(readRecord(resultSet)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取排版模板失败", ex);
        }
        return templates;
    }

    public TargetTemplateView createTemplate(String templateType, String name, MultipartFile file) {
        ensureTable();
        String normalizedType = normalizeType(templateType);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请先上传排版图");
        }
        try {
            byte[] content = file.getBytes();
            String fileName = normalizeText(file.getOriginalFilename(), "target-template.jpg");
            String contentType = normalizeText(file.getContentType(), "image/jpeg");
            StoredUploadImage storedImage = new StoredUploadImage(fileName, contentType, content);
            UploadImageAnalysis analysis = uploadImageAnalysisService.analyzeStyleStored(
                    templateTypeText(normalizedType) + "排版模板",
                    defaultPromptSettingsService.getSettings().targetTemplatePrompt(),
                    List.of(storedImage)
            );
            Thumbnail thumbnail = createThumbnail(storedImage);
            long id;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         insert into target_templates (
                           template_type, name, file_name, content_type, file_size, content,
                           thumbnail, thumbnail_content_type, style_analysis, model
                         ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, normalizedType);
                statement.setString(2, normalizeText(name, fileName));
                statement.setString(3, fileName);
                statement.setString(4, contentType);
                statement.setLong(5, content.length);
                statement.setBytes(6, content);
                statement.setBytes(7, thumbnail.bytes());
                statement.setString(8, thumbnail.contentType());
                statement.setString(9, analysis.result());
                statement.setString(10, analysis.model());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("保存排版模板失败：未返回模板 ID");
                    }
                    id = keys.getLong(1);
                }
            }
            TargetTemplateRecord record = findRecord(id);
            if (record == null) {
                throw new IllegalStateException("保存排版模板后读取失败：" + id);
            }
            return toView(record);
        } catch (IOException ex) {
            throw new IllegalStateException("读取排版图失败", ex);
        } catch (SQLException ex) {
            throw new IllegalStateException("保存排版模板失败", ex);
        }
    }

    public void deleteTemplate(long id) {
        ensureTable();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("delete from target_templates where id = ?")) {
            statement.setLong(1, id);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("排版模板不存在：" + id);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("删除排版模板失败：" + id, ex);
        }
    }

    public TargetTemplateRecord findRecord(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        ensureTable();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select * from target_templates where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return readRecord(resultSet);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取排版模板失败：" + id, ex);
        }
        return null;
    }

    public StoredUploadImage toStoredImage(TargetTemplateRecord record) {
        return new StoredUploadImage(record.fileName(), record.contentType(), record.content());
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

    private TargetTemplateRecord readRecord(ResultSet resultSet) throws SQLException {
        return new TargetTemplateRecord(
                resultSet.getLong("id"),
                resultSet.getString("template_type"),
                resultSet.getString("name"),
                resultSet.getString("file_name"),
                resultSet.getString("content_type"),
                resultSet.getLong("file_size"),
                resultSet.getBytes("content"),
                resultSet.getBytes("thumbnail"),
                resultSet.getString("thumbnail_content_type"),
                resultSet.getString("style_analysis"),
                resultSet.getString("model"),
                resultSet.getTimestamp("created_at"),
                resultSet.getTimestamp("updated_at")
        );
    }

    private void ensureTable() {
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
        } catch (SQLException ex) {
            throw new IllegalStateException("初始化排版模板表失败", ex);
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
        throw new IllegalArgumentException("排版模板类型只能是 MAIN 或 INTRO");
    }

    private String templateTypeText(String value) {
        return "INTRO".equals(value) ? "介绍图" : "主图";
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
