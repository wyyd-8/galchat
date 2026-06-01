package com.me.galchat.controller;

import com.me.galchat.constant.ImageConstant;
import com.me.galchat.domain.Result;
import com.me.galchat.utils.ImageSecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@RestController
@Slf4j
public class UploadController {

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestParam("file") MultipartFile file) throws Exception {
        log.info("接收参数:{}", file == null ? null : file.getOriginalFilename());
        ImageSecurityUtils.validateUploadImage(file);
        String extension = ImageSecurityUtils.getLowercaseExtension(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + extension;
        Path uploadDir = Path.of(ImageConstant.LOCAL_UPLOAD_DIR).toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
        Files.copy(file.getInputStream(), uploadDir.resolve(storedFileName), StandardCopyOption.REPLACE_EXISTING);
        String url = ImageConstant.LOCAL_UPLOAD_URL_PREFIX + storedFileName;
        log.info("图片上传成功:{}", url);
        return Result.success(url);
    }
}
