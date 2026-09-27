package com.evops;

import org.apache.shiro.spring.boot.autoconfigure.ShiroAnnotationProcessorAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 排除 Shiro 的注解处理器自动配置：它注册的 DefaultAdvisorAutoProxyCreator 走 JDK 动态代理，
 * 会把无接口的 Service 代理成 $Proxy 导致按具体类注入失败。
 * 项目暂未使用 @RequiresPermissions 等 Shiro 注解；事务代理由 Spring Boot 默认 CGLIB 承担。
 */
@SpringBootApplication(exclude = ShiroAnnotationProcessorAutoConfiguration.class)
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}
