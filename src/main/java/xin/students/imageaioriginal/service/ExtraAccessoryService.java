package xin.students.imageaioriginal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.entity.ExtraAccessoryEntity;
import xin.students.imageaioriginal.mapper.ExtraAccessoryMapper;
import xin.students.imageaioriginal.model.ExtraAccessoryView;
import xin.students.imageaioriginal.model.StoredUploadImage;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

@Service
public class ExtraAccessoryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int THUMB_MAX_EDGE = 360;

    private final DataSource dataSource;
    private final ExtraAccessoryMapper extraAccessoryMapper;

    public ExtraAccessoryService(DataSource dataSource, ExtraAccessoryMapper extraAccessoryMapper) {
        this.dataSource = dataSource;
        this.extraAccessoryMapper = extraAccessoryMapper;
    }

    @PostConstruct
    public void initialize() {
        ensureTable();
    }

    public List<ExtraAccessoryView> listAccessories() {
        ensureTable();
        return extraAccessoryMapper.selectList(new LambdaQueryWrapper<ExtraAccessoryEntity>()
                        .orderByDesc(ExtraAccessoryEntity::getCreatedAt)
                        .orderByDesc(ExtraAccessoryEntity::getId))
                .stream()
                .map(this::toRecord)
                .map(this::toView)
                .toList();
    }

    public ExtraAccessoryView createAccessory(String name, MultipartFile file) {
        ensureTable();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请先上传配件图片");
        }
        try {
            byte[] content = file.getBytes();
            String fileName = normalizeText(file.getOriginalFilename(), "accessory.jpg");
            String contentType = normalizeText(file.getContentType(), "image/jpeg");
            StoredUploadImage storedImage = new StoredUploadImage(fileName, contentType, content);
            Thumbnail thumbnail = createThumbnail(storedImage);

            ExtraAccessoryEntity entity = new ExtraAccessoryEntity();
            entity.setName(normalizeText(name, fileName));
            entity.setFileName(fileName);
            entity.setContentType(contentType);
            entity.setFileSize((long) content.length);
            entity.setContent(content);
            entity.setThumbnail(thumbnail.bytes());
            entity.setThumbnailContentType(thumbnail.contentType());
            extraAccessoryMapper.insert(entity);

            ExtraAccessoryRecord record = findRecord(entity.getId());
            if (record == null) {
                throw new IllegalStateException("保存额外配件后读取失败：" + entity.getId());
            }
            return toView(record);
        } catch (IOException ex) {
            throw new IllegalStateException("读取配件图片失败", ex);
        }
    }

    public void deleteAccessory(long id) {
        ensureTable();
        if (extraAccessoryMapper.deleteById(id) == 0) {
            throw new IllegalArgumentException("额外配件不存在：" + id);
        }
    }

    public ExtraAccessoryRecord findRecord(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        ensureTable();
        ExtraAccessoryEntity entity = extraAccessoryMapper.selectById(id);
        return entity == null ? null : toRecord(entity);
    }

    public ExtraAccessoryRecord findRecordByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ensureTable();
        ExtraAccessoryEntity entity = extraAccessoryMapper.selectOne(new LambdaQueryWrapper<ExtraAccessoryEntity>()
                .eq(ExtraAccessoryEntity::getName, name.trim())
                .orderByDesc(ExtraAccessoryEntity::getId)
                .last("limit 1"));
        return entity == null ? null : toRecord(entity);
    }

    public StoredUploadImage toStoredImage(ExtraAccessoryRecord record) {
        return new StoredUploadImage(record.fileName(), record.contentType(), record.content());
    }

    private ExtraAccessoryView toView(ExtraAccessoryRecord record) {
        return new ExtraAccessoryView(
                record.id(),
                record.name(),
                record.fileName(),
                record.contentType(),
                record.fileSize(),
                dataUrl(record.thumbnail(), record.thumbnailContentType()),
                formatTime(record.createdAt()),
                formatTime(record.updatedAt())
        );
    }

    private ExtraAccessoryRecord toRecord(ExtraAccessoryEntity entity) {
        return new ExtraAccessoryRecord(
                entity.getId(),
                entity.getName(),
                entity.getFileName(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getContent(),
                entity.getThumbnail(),
                entity.getThumbnailContentType(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void ensureTable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists extra_accessories (
                      id bigint primary key auto_increment,
                      name varchar(255) not null,
                      file_name varchar(255) not null,
                      content_type varchar(128) not null,
                      file_size bigint not null,
                      content longblob not null,
                      thumbnail longblob null,
                      thumbnail_content_type varchar(128) null,
                      created_at timestamp(3) not null default current_timestamp(3),
                      updated_at timestamp(3) not null default current_timestamp(3) on update current_timestamp(3),
                      index idx_extra_accessories_created (created_at)
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
        } catch (SQLException ex) {
            throw new IllegalStateException("初始化额外配件表失败", ex);
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

    public record ExtraAccessoryRecord(
            Long id,
            String name,
            String fileName,
            String contentType,
            Long fileSize,
            byte[] content,
            byte[] thumbnail,
            String thumbnailContentType,
            Timestamp createdAt,
            Timestamp updatedAt
    ) {
    }

    private record Thumbnail(byte[] bytes, String contentType) {
    }
}
