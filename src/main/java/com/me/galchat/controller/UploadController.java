package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.utils.AliyunOSSOperator;
import com.me.galchat.utils.ImageSecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Slf4j
public class UploadController {

    /*本地磁盘存储方法
    @PostMapping("/upload")
    public Result upload(String name, Integer age, MultipartFile file) throws IOException {
        log.info("接收参数:{}, {}, {}", name, age, file);
        //获取原始文件名
        String fileName = file.getOriginalFilename();
        //获取文件后缀
        String extension = fileName.substring(fileName.lastIndexOf("."));
        //保存文件
        file.transferTo(new File("D:/Project/" + UUID.randomUUID() + extension));
        return Result.success();
    }*/

    /**
     * 阿里云存储方法
     */
    @Autowired
    private AliyunOSSOperator ossClient;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result upload(@RequestParam("file") MultipartFile file) throws Exception {
        log.info("接收参数:{}", file == null ? null : file.getOriginalFilename());
        ImageSecurityUtils.validateUploadImage(file);
        //传递文件字节流
        String url = ossClient.upload(file.getInputStream().readAllBytes(), file.getOriginalFilename());
        log.info("图片上传成功:{}", url);
        return Result.success(url);
    }
}
