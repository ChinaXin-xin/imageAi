package xin.students.imageaioriginal.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class ImageTaskFileService {

    private static final int THUMB_MAX_EDGE = 320;

    public List<StoredTaskFile> toStoredTaskFiles(String fileGroup, List<MultipartFile> files) {
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

    public Thumbnail createThumbnail(List<StoredTaskFile> files) {
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

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
