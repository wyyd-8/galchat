package com.me.galchat.config;

import com.me.galchat.interceptor.TokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

import static com.me.galchat.constant.ImageConstant.LOCAL_UPLOAD_DIR;
import static com.me.galchat.constant.ImageConstant.LOCAL_UPLOAD_URL_PREFIX;

/*
同时有拦截器和过滤器时，会先执行过滤器，再执行拦截器
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private TokenInterceptor tokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/**")//拦截所有请求
                .excludePathPatterns("/user/login", "/user/register", "/user/register/email-code",
                        LOCAL_UPLOAD_URL_PREFIX + "**");//不拦截/login的请求(excludePathPatterns优先级更高)
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadLocation = Path.of(LOCAL_UPLOAD_DIR).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler(LOCAL_UPLOAD_URL_PREFIX + "**")
                .addResourceLocations(uploadLocation);
    }

}
/*
addPathPatterns中:/*拦截一级路径，如/emps，/depts，不包括/emps/1
                  /**拦截任意级路径，包括/emps/1/2之类的
                  /depts/*拦截depts下的一级路径，不包括/depts本身
                  /depts/**拦截depts下的任意级路径，包括/depts本身
 */
