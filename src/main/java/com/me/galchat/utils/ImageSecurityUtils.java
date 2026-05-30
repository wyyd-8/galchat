package com.me.galchat.utils;

import com.me.galchat.constant.ImageConstant;
import com.me.galchat.exception.UserRequestException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

public final class ImageSecurityUtils {

    private ImageSecurityUtils() {
    }

    public static void validateUploadImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new UserRequestException("请选择要上传的图片");
        }
        if (file.getSize() > ImageConstant.MAX_IMAGE_SIZE) {
            throw new UserRequestException("图片大小不能超过4MB");
        }

        String extension = getLowercaseExtension(file.getOriginalFilename());
        String contentType = file.getContentType();
        if (!ImageConstant.ALLOWED_CONTENT_TYPES.get(extension).contains(contentType)) {
            throw new UserRequestException("仅支持 JPG、PNG、GIF、WEBP、BMP 图片");
        }

        byte[] header = file.getInputStream().readNBytes(12);
        if (!matchesImageSignature(extension, header)) {
            throw new UserRequestException("文件内容不是有效图片");
        }
    }

    public static String getLowercaseExtension(String filename) {
        if (filename == null) {
            throw new UserRequestException("文件名不能为空");
        }
        int lastDotIndex = filename.lastIndexOf(".");
        if (lastDotIndex < 0 || lastDotIndex == filename.length() - 1) {
            throw new UserRequestException("图片文件必须包含有效后缀");
        }
        String extension = filename.substring(lastDotIndex).toLowerCase(Locale.ROOT);
        if (!ImageConstant.ALLOWED_CONTENT_TYPES.containsKey(extension)) {
            throw new UserRequestException("仅支持 JPG、PNG、GIF、WEBP、BMP 图片");
        }
        return extension;
    }

    public static String normalizeOssImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return "";
        }
        String normalized = imageUrl.trim();
        if (!ImageConstant.OSS_IMAGE_URL_PATTERN.matcher(normalized).matches()) {
            throw new UserRequestException("图片地址必须来自指定上传路径");
        }
        return normalized;
    }

    private static boolean matchesImageSignature(String extension, byte[] header) {
        return switch (extension) {
            case ".jpg", ".jpeg" -> header.length >= 3
                    && (header[0] & 0xff) == 0xff
                    && (header[1] & 0xff) == 0xd8
                    && (header[2] & 0xff) == 0xff;
            case ".png" -> header.length >= 8
                    && (header[0] & 0xff) == 0x89
                    && header[1] == 0x50
                    && header[2] == 0x4e
                    && header[3] == 0x47
                    && header[4] == 0x0d
                    && header[5] == 0x0a
                    && header[6] == 0x1a
                    && header[7] == 0x0a;
            case ".gif" -> header.length >= 6
                    && header[0] == 0x47
                    && header[1] == 0x49
                    && header[2] == 0x46
                    && header[3] == 0x38
                    && (header[4] == 0x37 || header[4] == 0x39)
                    && header[5] == 0x61;
            case ".webp" -> header.length >= 12
                    && header[0] == 0x52
                    && header[1] == 0x49
                    && header[2] == 0x46
                    && header[3] == 0x46
                    && header[8] == 0x57
                    && header[9] == 0x45
                    && header[10] == 0x42
                    && header[11] == 0x50;
            case ".bmp" -> header.length >= 2
                    && header[0] == 0x42
                    && header[1] == 0x4d;
            default -> false;
        };
    }
}
