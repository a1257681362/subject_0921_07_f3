package com.evops.workorder;

import com.evops.common.BusinessException;
import com.evops.machine.MachineService;
import com.evops.machine.MachineStatus;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.dto.MachineDisableRequest;
import com.evops.machine.dto.MachineUpdateRequest;
import com.evops.machine.entity.Machine;
import com.evops.workorder.dto.ManualAreaRequest;
import com.evops.workorder.dto.ReverseRequest;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.mapper.WorkOrderMapper;
import com.evops.workorder.vo.WorkOrderDetail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class WorkOrderMainFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MachineService machineService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Test
    void fullLifecycle_createTransitTrackAreaComplete() {
        Machine machine = createMachine();
        WorkOrder order = createOrder(machine.getId(), "WO-LIFE-");

        // CREATED 不能直接完工
        assertThrows(BusinessException.class,
                () -> workOrderService.transit(order.getId(), transitReq(WorkOrderStatus.COMPLETED)));

        // 只有人工上报面积：可以开工，但不能完工
        ManualAreaRequest manual = new ManualAreaRequest();
        manual.setManualArea(new BigDecimal("12.50"));
        manual.setReporter("张三");
        manual.setRequestNo(uniq("REQ-MANUAL-"));
        WorkOrder withManual = workOrderService.reportManualArea(order.getId(), manual);
        assertEquals(new BigDecimal("12.50"), withManual.getManualArea());
        WorkOrder inProgressEarly = workOrderService.transit(order.getId(), transitReq(WorkOrderStatus.IN_PROGRESS));
        assertEquals(WorkOrderStatus.IN_PROGRESS, inProgressEarly.getStatus());
        BusinessException noTrack = assertThrows(BusinessException.class,
                () -> workOrderService.transit(order.getId(), transitReq(WorkOrderStatus.COMPLETED)));
        assertTrue(noTrack.getMessage().contains("轨迹"));

        // 轨迹核算面积到位后才能完工
        TrackAreaRequest track = new TrackAreaRequest();
        track.setTrackArea(new BigDecimal("10.00"));
        track.setRequestNo(uniq("REQ-TRACK-"));
        WorkOrder withTrack = workOrderService.reportTrackArea(order.getId(), track);
        assertEquals(new BigDecimal("10.00"), withTrack.getTrackArea());
        assertNotNull(withTrack.getTrackTime());
        assertTrue(withTrack.getVersion() >= 1, "变更必须落版本");

        WorkOrder completed = workOrderService.transit(order.getId(), transitReq(WorkOrderStatus.COMPLETED));
        assertEquals(WorkOrderStatus.COMPLETED, completed.getStatus());

        WorkOrderDetail detail = workOrderService.findByOrderNo(order.getOrderNo());
        assertEquals(order.getId(), detail.getOrder().getId());
        assertEquals(3, detail.getStatusLogs().size(), "建档/开工/完工三条流转");
    }

    @Test
    void duplicateOrderNoAndRequestNo_rejected() {
        Machine machine = createMachine();
        WorkOrderCreateRequest req1 = createReq("WO-DUP", machine.getId(), "REQ-DUP-1");
        workOrderService.create(req1);

        // 同作业单号再来一笔（不同请求号）→ 拒绝
        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.create(createReq("WO-DUP", machine.getId(), "REQ-DUP-2")));
        assertTrue(ex.getMessage().contains("已存在"));

        // 同一请求号重放 → 拒绝
        assertThrows(BusinessException.class,
                () -> workOrderService.create(createReq("WO-OTHER", machine.getId(), "REQ-DUP-1")));
    }

    @Test
    void reversal_createsReverseOrder_andKeepsHistory() {
        Machine machine = createMachine();
        WorkOrder origin = createOrder(machine.getId(), "WO-REV-");
        workOrderService.reportTrackArea(origin.getId(),
                trackArea(new BigDecimal("8.80"), "REQ-REV-TRACK"));
        workOrderService.transit(origin.getId(), transitReq(WorkOrderStatus.IN_PROGRESS));
        workOrderService.transit(origin.getId(), transitReq(WorkOrderStatus.COMPLETED));

        ReverseRequest reverseReq = new ReverseRequest();
        reverseReq.setRequestNo(uniq("REQ-REV-"));
        reverseReq.setRemark("误录冲销");
        WorkOrder reverse = workOrderService.reverse(origin.getId(), reverseReq);

        assertEquals("1", reverse.getReversalFlag());
        assertEquals(origin.getId(), reverse.getOriginOrderId());
        assertEquals(origin.getOrderNo() + "-R", reverse.getOrderNo());
        assertEquals(0, new BigDecimal("-8.80").compareTo(reverse.getTrackArea()));
        assertEquals(WorkOrderStatus.REVERSED, reverse.getStatus());

        WorkOrder reloaded = workOrderMapper.selectById(origin.getId());
        assertEquals(WorkOrderStatus.REVERSED, reloaded.getStatus(), "原单置为已红冲但记录保留");

        // 反向单不能再次红冲
        assertThrows(BusinessException.class,
                () -> workOrderService.reverse(reverse.getId(), reverseReq("REQ-REV-AGAIN")));
        // 已红冲原单不能重复红冲（同一请求号也会先被拦）
        assertThrows(BusinessException.class,
                () -> workOrderService.reverse(origin.getId(), reverseReq("REQ-REV-ONCE-MORE")));

        // 原单仍可按单号检索，历史完整
        WorkOrderDetail detail = workOrderService.findByOrderNo(origin.getOrderNo());
        assertEquals(WorkOrderStatus.REVERSED, detail.getOrder().getStatus());
        assertTrue(detail.getStatusLogs().size() >= 4);
    }

    @Test
    void disabledMachine_cannotCreateOrder() {
        Machine machine = createMachine();
        MachineDisableRequest disable = new MachineDisableRequest();
        disable.setRequestNo(uniq("REQ-DISABLE-"));
        Machine disabled = machineService.disable(machine.getId(), disable);
        assertEquals(MachineStatus.DISABLED, disabled.getStatus());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createOrder(machine.getId(), "WO-DISABLED-"));
        assertTrue(ex.getMessage().contains("停用"));
    }

    @Test
    void optimisticLock_staleVersionRejected() {
        Machine machine = createMachine();
        MachineUpdateRequest req = new MachineUpdateRequest();
        req.setMachineName("新名称");
        req.setVersion(999);
        req.setRequestNo(uniq("REQ-STALE-"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> machineService.update(machine.getId(), req));
        assertTrue(ex.getMessage().contains("刷新"));
    }

    private Machine createMachine() {
        MachineCreateRequest req = new MachineCreateRequest();
        req.setMachineCode(uniq("M-FLOW-"));
        req.setMachineName("测试拖拉机");
        req.setMachineType("拖拉机");
        req.setRequestNo(uniq("REQ-MACHINE-"));
        return machineService.create(req);
    }

    private WorkOrder createOrder(Long machineId, String orderNoPrefix) {
        return workOrderService.create(createReq(uniq(orderNoPrefix), machineId, uniq("REQ-ORDER-")));
    }

    private WorkOrderCreateRequest createReq(String orderNo, Long machineId, String requestNo) {
        WorkOrderCreateRequest req = new WorkOrderCreateRequest();
        req.setOrderNo(orderNo);
        req.setMachineId(machineId);
        req.setOperationType("收割");
        req.setPlotName("一号地块");
        req.setRequestNo(requestNo);
        return req;
    }

    private StatusTransitRequest transitReq(String target) {
        StatusTransitRequest req = new StatusTransitRequest();
        req.setTargetStatus(target);
        req.setRequestNo(uniq("REQ-TRANSIT-"));
        return req;
    }

    private TrackAreaRequest trackArea(BigDecimal area, String requestNoPrefix) {
        TrackAreaRequest req = new TrackAreaRequest();
        req.setTrackArea(area);
        req.setRequestNo(uniq(requestNoPrefix));
        return req;
    }

    private ReverseRequest reverseReq(String prefix) {
        ReverseRequest req = new ReverseRequest();
        req.setRequestNo(uniq(prefix));
        return req;
    }

    private String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }
}
