package com.evops.recon.schedule;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开启调度开关时的装配验证：Quartz Scheduler 存在且挂上每日勾稽 Trigger；
 * 启动恢复（recoverOnStartup）也会在空库上补跑前一日，不报错即可。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recon_schedule;DB_CLOSE_DELAY=-1",
        "reconcile.scheduling-enabled=true"
})
@ActiveProfiles("test")
@DirtiesContext
class ReconSchedulingConfigTest {

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private List<Trigger> triggers;

    @Test
    void schedulerHasDailyReconTrigger() throws Exception {
        assertNotNull(scheduler);
        Trigger reconTrigger = triggers.stream()
                .filter(t -> "reconDailyTrigger".equals(t.getKey().getName()))
                .findFirst().orElse(null);
        assertNotNull(reconTrigger, "应装配每日勾稽 CronTrigger");
        assertTrue(scheduler.checkExists(reconTrigger.getKey()), "触发器应已注册到调度器");
        assertEquals("reconDailyJobDetail", reconTrigger.getJobKey().getName());
    }
}
