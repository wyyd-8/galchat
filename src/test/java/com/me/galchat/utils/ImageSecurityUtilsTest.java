package com.me.galchat.utils;

import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageSecurityUtilsTest {

    @Test
    void validateUploadImageAcceptsRealJpegHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "cover.JPG",
                "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}
        );

        assertDoesNotThrow(() -> ImageSecurityUtils.validateUploadImage(file));
        assertEquals(".jpg", ImageSecurityUtils.getLowercaseExtension(file.getOriginalFilename()));
    }

    @Test
    void validateUploadImageRejectsOversizeImage() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "cover.png",
                "image/png",
                new byte[(int) ImageSecurityUtils.MAX_IMAGE_SIZE + 1]
        );

        assertThrows(UserRequestException.class, () -> ImageSecurityUtils.validateUploadImage(file));
    }

    @Test
    void validateUploadImageRejectsContentTypeAndSignatureMismatch() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "cover.png",
                "image/png",
                new byte[]{'n', 'o', 't', '-', 'i', 'm', 'g'}
        );

        assertThrows(UserRequestException.class, () -> ImageSecurityUtils.validateUploadImage(file));
    }

    @Test
    void normalizeOssImageUrlOnlyAcceptsConfiguredPath() {
        assertEquals(
                "https://galchat.oss-cn-beijing.aliyuncs.com/2026/05/550e8400-e29b-41d4-a716-446655440000.png",
                ImageSecurityUtils.normalizeOssImageUrl(
                        " https://galchat.oss-cn-beijing.aliyuncs.com/2026/05/550e8400-e29b-41d4-a716-446655440000.png "
                )
        );

        assertThrows(UserRequestException.class, () -> ImageSecurityUtils.normalizeOssImageUrl(
                "https://galchat.oss-cn-beijing.aliyuncs.com/2026/06/550e8400-e29b-41d4-a716-446655440000.png"
        ));
        assertThrows(UserRequestException.class, () -> ImageSecurityUtils.normalizeOssImageUrl(
                "https://example.com/2026/05/550e8400-e29b-41d4-a716-446655440000.png"
        ));
    }
}
