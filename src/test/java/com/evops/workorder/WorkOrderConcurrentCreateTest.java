package com.evops.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.request.entity.RequestRecord;
import com.evops.request.mapper.RequestRecordMapper;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 同一作业单号同时两笔建档：只能成功一条。
 * 两笔请求各带不同请求号，靠 uk_work_order_no 唯一约束在事务层兜底，
 * 失败方连请求号台账一起整体回退。
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkOrderConcurrentCreateTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private WorkOrderMapper workOrderMapper;
    @Autowired
    private MachineMapper machineMapper;
    @Autowired
    private RequestRecordMapper requestRecordMapper;

    @Test
    void sameOrderNo_concurrentCreate_onlyOneSucceeds() throws Exception {
        Machine machine = new Machine();
        machine.setMachineCode("M-CONC-" + SEQ.incrementAndGet());
        machine.setMachineName("并发测试农机");
        machine.setStatus("ACTIVE");
        machineMapper.insert(machine);

        String orderNo = "WO-CONC-" + SEQ.incrementAndGet();
        String requestNo1 = "REQ-CONC-" + SEQ.incrementAndGet();
        String requestNo2 = "REQ-CONC-" + SEQ.incrementAndGet();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Thread t1 = new Thread(() -> runCreate(orderNo, machine.getId(), requestNo1, ready, start, success, rejected));
        Thread t2 = new Thread(() -> runCreate(orderNo, machine.getId(), requestNo2, ready, start, success, rejected));
        t1.start();
        t2.start();
        ready.await();
        start.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertEquals(1, success.get(), "同一作业单号并发建档必须只有一笔成功");
        assertEquals(1, rejected.get(), "另一笔必须被唯一约束拒绝");

        Long count = workOrderMapper.selectCount(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        assertEquals(1L, count, "库里只能有一条该单号的作业单");

        // 被拒绝的事务整体回退：只应有一笔请求号留下台账
        Long reqCount = requestRecordMapper.selectCount(
                new LambdaQueryWrapper<RequestRecord>().in(RequestRecord::getRequestNo, requestNo1, requestNo2));
        assertEquals(1L, reqCount, "失败请求的请求号台账必须随事务回退");
    }

    private void runCreate(String orderNo, Long machineId, String requestNo,
                           CountDownLatch ready, CountDownLatch start,
                           AtomicInteger success, AtomicInteger rejected) {
        try {
            ready.countDown();
            start.await();
            workOrderService.create(buildRequest(orderNo, machineId, requestNo));
            success.incrementAndGet();
        } catch (BusinessException e) {
            rejected.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private WorkOrderCreateRequest buildRequest(String orderNo, Long machineId, String requestNo) {
        WorkOrderCreateRequest req = new WorkOrderCreateRequest();
        req.setOrderNo(orderNo);
        req.setMachineId(machineId);
        req.setOperationType("收割");
        req.setRequestNo(requestNo);
        return req;
    }
}
