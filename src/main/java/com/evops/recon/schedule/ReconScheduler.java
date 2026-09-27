package com.evops.recon.schedule;

import com.evops.recon.ReconConstants;
import com.evops.recon.ReconService;
import com.evops.recon.entity.ReconBatch;
import com.evops.recon.mapper.ReconBatchMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 勾稽定时触发入口。Quartz 通过方法调用触发（不并发）；异常只记录不外抛，
 * 次日可人工/启动补跑，不会让调度线程反复重试同一事务。
 */
@Component
@ConditionalOnProperty(prefix = "reconcile", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class ReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);

    private final ReconService reconService;
    private final ReconBatchMapper batchMapper;

    public ReconScheduler(ReconService reconService, ReconBatchMapper batchMapper) {
        this.reconService = reconService;
        this.batchMapper = batchMapper;
    }

    /** Quartz 每日调用：勾稽前一日的账。 */
    public void runDaily() {
        runFor(LocalDate.now().minusDays(1));
    }

    /**
     * 启动恢复：服务在勾稽时刻宕机/重启错过调度时，起来后补前一日。
     * 只补一天；每日一个批次的约束保证不会重复勾。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (!batchExists(yesterday)) {
            log.info("未发现 {} 的勾稽批次，启动补跑", yesterday);
            runFor(yesterday);
        }
    }

    private void runFor(LocalDate date) {
        try {
            ReconBatch batch = reconService.runReconciliation(date, ReconConstants.TRIGGER_SCHEDULED, null);
            log.info("勾稽定时执行完成：{}", batch.getBatchNo());
        } catch (Exception e) {
            log.error("勾稽定时执行失败，日期 {}：{}", date, e.getMessage(), e);
        }
    }

    private boolean batchExists(LocalDate date) {
        return batchMapper.selectCount(new LambdaQueryWrapper<ReconBatch>()
                .eq(ReconBatch::getReconcileDate, date)) > 0;
    }
}
