package com.me.galchat.utils;

import com.aliyun.dm20151123.Client;
import com.aliyun.dm20151123.models.SingleSendMailRequest;
import com.aliyun.dm20151123.models.SingleSendMailResponse;
import com.aliyun.tea.*;
import com.aliyun.teautil.models.RuntimeOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AliyunEmailSender {

    private final Client dmClient;

    @Value("${galchat.aliemail.account-name}")
    private String accountName;

    @Value("${galchat.aliemail.reply-to-address:false}")
    private boolean replyToAddress;

    public AliyunEmailSender(Client dmClient) {
        this.dmClient = dmClient;
    }

    /**
     * 发送纯文本邮件
     * @param toAddress 收件人地址
     * @param subject   邮件主题
     * @param textBody  纯文本内容
     * @return 发送结果（是否成功）
     */
    public boolean sendSimpleMail(String toAddress, String subject, String textBody) {
        SingleSendMailRequest request = new SingleSendMailRequest()
                .setAccountName(accountName)
                .setAddressType(1)
                .setReplyToAddress(replyToAddress)
                .setToAddress(toAddress)
                .setSubject(subject)
                .setTextBody(textBody)
                .setHtmlBody("");  // 纯文本模式，HtmlBody 为空

        RuntimeOptions runtime = new RuntimeOptions();
        try {
            SingleSendMailResponse response = dmClient.singleSendMailWithOptions(request, runtime);
            // 根据业务需要判断响应（通常 HTTP 200 即成功）
            return response.getStatusCode() == 200;
        } catch (TeaException e) {
            // 建议替换为日志框架
            System.err.println("邮件发送失败: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("未知错误: " + e.getMessage());
            return false;
        }
    }
}