package com.evops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 卫星轨迹账勾稽配置（application.yml 中 reconcile 前缀）。 */
@Component
@ConfigurationProperties(prefix = "reconcile")
public class ReconcileProperties {

    /** 定时调度开关，测试环境关闭。 */
    private boolean schedulingEnabled = true;
    /** Quartz cron，默认每日 02:10。 */
    private String cron = "0 10 2 * * ?";
    /** 匹配容差（亩）：|本地-对方| <= tolerance 视为一致。 */
    private BigDecimal tolerance = new BigDecimal("0.05");
    /** PARALLEL 新旧并跑 / NEW 仅新引擎 / LEGACY 仅旧引擎。 */
    private String engineMode = "PARALLEL";
    /** 切换前置条件：最近 N 个完成批次双引擎结果一致。 */
    private int cutoverBatchSize = 2;

    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean schedulingEnabled) { this.schedulingEnabled = schedulingEnabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public BigDecimal getTolerance() { return tolerance; }
    public void setTolerance(BigDecimal tolerance) { this.tolerance = tolerance; }
    public String getEngineMode() { return engineMode; }
    public void setEngineMode(String engineMode) { this.engineMode = engineMode; }
    public int getCutoverBatchSize() { return cutoverBatchSize; }
    public void setCutoverBatchSize(int cutoverBatchSize) { this.cutoverBatchSize = cutoverBatchSize; }
}
