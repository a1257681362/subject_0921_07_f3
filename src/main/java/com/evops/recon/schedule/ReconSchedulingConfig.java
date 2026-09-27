package com.evops.recon.schedule;

import com.evops.config.ReconcileProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.MethodInvokingJobDetailFactoryBean;

/**
 * 勾稽定时作业装配：只声明 JobDetail 与 Trigger，Scheduler 由 Spring Boot 按 spring.quartz 自动装配
 * 并自动收集 Trigger。JobDetail concurrent=false，上一趟没跑完时同实例不会重入；
 * 每日一个批次的库约束是跨实例/跨触发方式的最终防线。
 * 测试环境用 reconcile.scheduling-enabled=false 关闭。
 */
@Configuration
@ConditionalOnProperty(prefix = "reconcile", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class ReconSchedulingConfig {

    @Bean
    public MethodInvokingJobDetailFactoryBean reconJobDetail(ReconScheduler reconScheduler) {
        MethodInvokingJobDetailFactoryBean factory = new MethodInvokingJobDetailFactoryBean();
        factory.setTargetObject(reconScheduler);
        factory.setTargetMethod("runDaily");
        factory.setConcurrent(false);
        factory.setName("reconDailyJobDetail");
        return factory;
    }

    @Bean
    public CronTriggerFactoryBean reconTrigger(MethodInvokingJobDetailFactoryBean reconJobDetail,
                                               ReconcileProperties properties) {
        CronTriggerFactoryBean factory = new CronTriggerFactoryBean();
        factory.setJobDetail(reconJobDetail.getObject());
        factory.setCronExpression(properties.getCron());
        factory.setName("reconDailyTrigger");
        return factory;
    }
}
