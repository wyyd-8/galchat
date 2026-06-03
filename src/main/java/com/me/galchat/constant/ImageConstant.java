package com.me.galchat.constant;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ImageConstant {

    public static final long MAX_IMAGE_SIZE = 4 * 1024 * 1024;

    public static final Pattern OSS_IMAGE_URL_PATTERN = Pattern.compile(
            "^https://galchat\\.oss-cn-beijing\\.aliyuncs\\.com/[0-9]{4}/[0-9]{2}/[0-9a-fA-F-]+\\.(jpg|jpeg|png|gif|webp|bmp)$"
    );

    public static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES = Map.of(
            ".jpg", Set.of("image/jpeg"),
            ".jpeg", Set.of("image/jpeg"),
            ".png", Set.of("image/png"),
            ".gif", Set.of("image/gif"),
            ".webp", Set.of("image/webp"),
            ".bmp", Set.of("image/bmp", "image/x-ms-bmp")
    );

    private ImageConstant() {
    }
}
