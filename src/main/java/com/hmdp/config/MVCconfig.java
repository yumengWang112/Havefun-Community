package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;
import java.lang.ref.PhantomReference;

/**
 * @author wangyumeng
 * @version 1.0
 */
@Configuration
public class MVCconfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //添加拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //需要配置拦截哪些路径 有点请求不需要拦截器   跟用户有关的
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "voucher/**",
                        "upload/**",
                        "/user/code",
                        "/user/login"


                ).order(1);
        //拦所有请求  order控制执行顺序
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
