package com.evops.recon;

import com.evops.common.BusinessException;
import com.evops.machine.MachineService;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.entity.Machine;
import com.evops.recon.dto.SatelliteBillReverseRequest;
import com.evops.recon.dto.SatelliteBillUploadRequest;
import com.evops.recon.dto.WriteoffApplyRequest;
import com.evops.recon.dto.WriteoffApproveRequest;
import com.evops.recon.dto.WriteoffRejectRequest;
import com.evops.recon.entity.ReconBatch;
import com.evops.recon.entity.ReconDetail;
import com.evops.recon.entity.ReconWriteoff;
import com.evops.recon.entity.SatelliteBill;
import com.evops.recon.schedule.ReconScheduler;
import com.evops.recon.vo.BatchDetail;
import com.evops.recon.vo.CutoverCheck;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.dto.ReverseRequest;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卫星账勾稽主链路测试：并跑比对、容差、三类差异、红冲口径、核销即时清零与结转、切换闸门。
 * 独立内存库（DirtiesContext）避免与作业单测试类共享数据；方法固定顺序以构造多日批次序列。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recon_flow;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** 勾稽日都取未来日：测试数据产生于"此刻"，对这些日期都属于截至日内的账。 */
    private static final LocalDate D30 = LocalDate.now().plusDays(30);
    private static final LocalDate D31 = D30.plusDays(1);
    private static final LocalDate D32 = D30.plusDays(2);
    private static final LocalDate D40 = LocalDate.now().plusDays(40);

    @Autowired
    private ReconService reconService;
    @Autowired
    private ReconConfigService configService;
    @Autowired
    private WriteoffService writeoffService;
    @Autowired
    private SatelliteBillService billService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private MachineService machineService;
    @Autowired
    private ApplicationContext applicationContext;

    /** 闸门：连续两日双引擎逐笔一致且差异清零 → 可切；第三日出现差异 → 不可切。 */
    @Test
    @Order(1)
    void cutoverGate_requiresConsecutiveMatchBatches() {
        // 测试环境不启用定时调度
        assertEquals(0, applicationContext.getBeanNamesForType(ReconScheduler.class).length);

        Machine machine = createMachine();
        createCompletedOrder("WO-CUT-1", machine.getId(), new BigDecimal("10.00"));
        uploadBill("SB-CUT-1", "WO-CUT-1", new BigDecimal("10.00"));

        ReconBatch b30 = run(D30);
        assertEquals(ReconConstants.COMPARE_MATCH, b30.getEngineCompareResult());
        assertEquals(0, b30.getEngOpenDiffCount());
        ReconBatch b31 = run(D31);
        assertEquals(ReconConstants.COMPARE_MATCH, b31.getEngineCompareResult());

        CutoverCheck ready = reconService.checkCutoverReady();
        assertTrue(ready.isReady(), "连续两日双引擎一致且无未平差异应可切换: " + ready.getReasons());
        assertEquals(2, ready.getMatchedBatches());

        // 再来一批双引擎分歧的：差 0.03 在容差内——新引擎判一致，旧引擎分毫必较挂差异
        createCompletedOrder("WO-CUT-2", machine.getId(), new BigDecimal("10.00"));
        uploadBill("SB-CUT-2", "WO-CUT-2", new BigDecimal("10.03"));
        ReconBatch b32 = run(D32);
        assertEquals(ReconConstants.COMPARE_MISMATCH, b32.getEngineCompareResult());
        assertTrue(b32.getEngineCompareDetail().contains("WO-CUT-2"));

        CutoverCheck blocked = reconService.checkCutoverReady();
        assertFalse(blocked.isReady());
        assertTrue(blocked.getReasons().size() >= 1);
    }

    /** 完全对得上的单子不出现在任何一份差异清单里；同日不能勾两趟。 */
    @Test
    @Order(2)
    void matchedPair_notReported_andOncePerDay() {
        Machine machine = createMachine();
        createCompletedOrder("WO-PAR-1", machine.getId(), new BigDecimal("5.50"));
        uploadBill("SB-PAR-1", "WO-PAR-1", new BigDecimal("5.50"));

        ReconBatch batch = run(D40);
        BatchDetail detail = reconService.getBatchDetail(batch.getId());
        assertTrue(newDetailsFor(detail, "WO-PAR-1").isEmpty());
        assertTrue(legacyDetailsFor(detail, "WO-PAR-1").isEmpty());

        BusinessException ex = assertThrows(BusinessException.class, () -> run(D40));
        assertTrue(ex.getMessage().contains("每日只勾一次"));
    }

    /** 容差内新引擎判一致、旧引擎分毫必较仍报差异；调小容差后新引擎也出差异。 */
    @Test
    @Order(3)
    void tolerance_configurable() {
        Machine machine = createMachine();
        createCompletedOrder("WO-TOL-1", machine.getId(), new BigDecimal("10.00"));
        uploadBill("SB-TOL-1", "WO-TOL-1", new BigDecimal("10.03"));

        ReconBatch batch = run(D40.plusDays(1));
        BatchDetail detail = reconService.getBatchDetail(batch.getId());
        assertTrue(newDetailsFor(detail, "WO-TOL-1").isEmpty(), "差 0.03 在默认容差 0.05 内应判一致");
        List<ReconDetail> legacy = legacyDetailsFor(detail, "WO-TOL-1");
        assertEquals(1, legacy.size());
        assertEquals(DiffType.AMOUNT_DIFF, legacy.get(0).getDiffType());

        // 容差调小到 0.02
        assertEquals(new BigDecimal("0.02"),
                configService.updateTolerance(new BigDecimal("0.02"), uniq("REQ-TOL-")));
        ReconBatch stricter = run(D40.plusDays(2));
        BatchDetail strictDetail = reconService.getBatchDetail(stricter.getId());
        List<ReconDetail> nowDiff = newDetailsFor(strictDetail, "WO-TOL-1");
        assertEquals(1, nowDiff.size());
        assertEquals(0, new BigDecimal("0.03").compareTo(nowDiff.get(0).getDiffArea()));
        assertEquals(0, new BigDecimal("0.03").compareTo(nowDiff.get(0).getAbsDiffArea()));

        // 恢复默认容差
        configService.updateTolerance(new BigDecimal("0.05"), uniq("REQ-TOL-"));
    }

    /** 三类差异都带双方明细字段。 */
    @Test
    @Order(4)
    void threeDiffTypes_withBothSideDetails() {
        Machine machine = createMachine();
        createCompletedOrder("WO-LOCAL-ONLY", machine.getId(), new BigDecimal("7.00"));
        uploadBill("SB-REMOTE-ONLY", "WO-REMOTE-ONLY", new BigDecimal("7.00"));
        createCompletedOrder("WO-AMT-1", machine.getId(), new BigDecimal("10.00"));
        uploadBill("SB-AMT-1", "WO-AMT-1", new BigDecimal("10.80"));

        ReconBatch batch = run(D40.plusDays(3));
        BatchDetail detail = reconService.getBatchDetail(batch.getId());

        ReconDetail localOnly = sole(newDetailsFor(detail, "WO-LOCAL-ONLY"));
        assertEquals(DiffType.LOCAL_ONLY, localOnly.getDiffType());
        assertNotNull(localOnly.getLocalTrackArea());
        assertNull(localOnly.getRemoteBillNo());

        ReconDetail remoteOnly = sole(newDetailsFor(detail, "WO-REMOTE-ONLY"));
        assertEquals(DiffType.REMOTE_ONLY, remoteOnly.getDiffType());
        assertEquals("SB-REMOTE-ONLY", remoteOnly.getRemoteBillNo());
        assertNull(remoteOnly.getLocalId());

        ReconDetail amountDiff = sole(newDetailsFor(detail, "WO-AMT-1"));
        assertEquals(DiffType.AMOUNT_DIFF, amountDiff.getDiffType());
        assertEquals(0, new BigDecimal("0.80").compareTo(amountDiff.getDiffArea()));
        assertEquals(0, new BigDecimal("0.80").compareTo(amountDiff.getAbsDiffArea()));
        assertNotNull(amountDiff.getLocalTrackTime());
        assertNotNull(amountDiff.getRemoteBillTime());

        assertTrue(batch.getEngLocalOnlyCount() >= 1);
        assertTrue(batch.getEngRemoteOnlyCount() >= 1);
        assertTrue(batch.getEngAmountDiffCount() >= 1);

        // 未平差异清单可查
        List<ReconDetail> open = reconService.listOpenDiffs(batch.getReconcileDate());
        assertTrue(open.stream().anyMatch(d -> "WO-AMT-1".equals(d.getOrderNo())));
    }

    /** 核销：申请/重复申请拦截/驳回留痕/审批后差异即时清零，下批自动结转。 */
    @Test
    @Order(5)
    void writeoff_approveClearsDiff_rejectKeepsOpen_andCarriesForward() {
        ReconBatch prev = reconService.listRecentBatches(20).stream()
                .filter(b -> b.getReconcileDate().equals(D40.plusDays(3)))
                .findFirst().orElseThrow(IllegalStateException::new);
        BatchDetail prevDetail = reconService.getBatchDetail(prev.getId());
        ReconDetail amountDiff = sole(newDetailsFor(prevDetail, "WO-AMT-1"));
        ReconDetail localOnly = sole(newDetailsFor(prevDetail, "WO-LOCAL-ONLY"));

        // 驳回流程：差异仍 OPEN，驳回后可重新申请
        ReconWriteoff applied = writeoffService.apply(applyReq(localOnly.getId(), AdjustType.LOCAL_OVER));
        assertThrows(BusinessException.class,
                () -> writeoffService.apply(applyReq(localOnly.getId(), AdjustType.LOCAL_OVER)));
        ReconWriteoff rejected = writeoffService.reject(applied.getId(),
                rejectReq("依据不足"));
        assertEquals(ReconConstants.WRITEOFF_REJECTED, rejected.getStatus());
        assertEquals(ReconConstants.DIFF_OPEN,
                reconService.getBatchDetail(prev.getId()).getNewDetails().stream()
                        .filter(d -> d.getId().equals(localOnly.getId())).findFirst().get().getStatus());
        writeoffService.apply(applyReq(localOnly.getId(), AdjustType.LOCAL_OVER));

        // 审批金额差异：差异即时清零
        ReconWriteoff approve = writeoffService.apply(applyReq(amountDiff.getId(), AdjustType.CALIBER_DIFF));
        ReconWriteoff approved = writeoffService.approve(approve.getId(),
                approveReq("李会计", "按轨迹权威面积确认"));
        assertEquals(ReconConstants.WRITEOFF_APPROVED, approved.getStatus());
        assertEquals("李会计", approved.getApprover());

        ReconDetail cleared = reconService.getBatchDetail(prev.getId()).getNewDetails().stream()
                .filter(d -> d.getId().equals(amountDiff.getId())).findFirst().get();
        assertEquals(ReconConstants.DIFF_WRITTEN_OFF, cleared.getStatus());
        assertEquals(approved.getId(), cleared.getWriteoffId());
        assertNotNull(cleared.getWriteoffTime());
        assertTrue(reconService.listOpenDiffs(prev.getReconcileDate()).stream()
                .noneMatch(d -> d.getId().equals(amountDiff.getId())));

        // 快照依旧不平（对方还没补账），新批次该差异自动结转清零、挂原核销单
        ReconBatch next = run(D40.plusDays(4));
        List<ReconDetail> recurring = newDetailsFor(reconService.getBatchDetail(next.getId()), "WO-AMT-1");
        assertEquals(1, recurring.size());
        ReconDetail carried = recurring.get(0);
        assertEquals(ReconConstants.DIFF_WRITTEN_OFF, carried.getStatus());
        assertEquals(amountDiff.getId(), carried.getCarriedFromDetailId());
        assertEquals(approved.getId(), carried.getWriteoffId());
        assertTrue(reconService.listOpenDiffs(next.getReconcileDate()).stream()
                .noneMatch(d -> "WO-AMT-1".equals(d.getOrderNo())));

        // 已审批的核销单不能重复审批
        assertThrows(BusinessException.class,
                () -> writeoffService.approve(approved.getId(), approveReq("王复核", "重复审批")));
    }

    /** 红冲只在一侧完成时：新引擎报反向单边（单号-R），旧引擎报原单单边——口径差异被双跑抓到。 */
    @Test
    @Order(6)
    void reversalAsymmetry_distinguishedByEngines() {
        Machine machine = createMachine();
        WorkOrder origin = createCompletedOrder("WO-REV-L", machine.getId(), new BigDecimal("6.00"));
        uploadBill("SB-REV-L", "WO-REV-L", new BigDecimal("6.00"));

        // 只红冲本地作业单
        ReverseRequest reverseReq = new ReverseRequest();
        reverseReq.setRequestNo(uniq("REQ-REV-WO-"));
        WorkOrder reverse = workOrderService.reverse(origin.getId(), reverseReq);
        assertEquals("WO-REV-L-R", reverse.getOrderNo());

        ReconBatch batch = run(D40.plusDays(5));
        BatchDetail detail = reconService.getBatchDetail(batch.getId());

        // 新引擎：原单双方金额仍对得上；反向单本地有、对方无
        assertTrue(newDetailsFor(detail, "WO-REV-L").isEmpty());
        ReconDetail localReverse = sole(newDetailsFor(detail, "WO-REV-L-R"));
        assertEquals(DiffType.LOCAL_ONLY, localReverse.getDiffType());
        assertEquals("1", localReverse.getLocalReversalFlag());

        // 旧引擎：红冲账整笔剔除，原单本地被剔除而对方还在 → REMOTE_ONLY
        ReconDetail legacyOrigin = sole(legacyDetailsFor(detail, "WO-REV-L"));
        assertEquals(DiffType.REMOTE_ONLY, legacyOrigin.getDiffType());
        assertTrue(legacyDetailsFor(detail, "WO-REV-L-R").isEmpty());
    }

    /** 两侧都红冲时新引擎逐笔配对，无差异；卫星账单红冲后不能重复红冲。 */
    @Test
    @Order(7)
    void bothSidesReversed_matchesUnderNewEngine() {
        Machine machine = createMachine();
        WorkOrder origin = createCompletedOrder("WO-REV-BOTH", machine.getId(), new BigDecimal("9.00"));
        SatelliteBill bill = uploadBill("SB-REV-BOTH", "WO-REV-BOTH", new BigDecimal("9.00"));
        workOrderService.reverse(origin.getId(), reverseReq("REQ-REV-BOTH-WO"));
        SatelliteBillReverseRequest billReverse = new SatelliteBillReverseRequest();
        billReverse.setRequestNo(uniq("REQ-REV-BILL-"));
        SatelliteBill reverseBill = billService.reverse(bill.getId(), billReverse);
        assertEquals(0, new BigDecimal("-9.00").compareTo(reverseBill.getTrackArea()));
        assertEquals("1", reverseBill.getReversalFlag());
        SatelliteBillReverseRequest again = new SatelliteBillReverseRequest();
        again.setRequestNo(uniq("REQ-REV-BILL-AGAIN-"));
        assertThrows(BusinessException.class, () -> billService.reverse(bill.getId(), again));

        ReconBatch batch = run(D40.plusDays(6));
        BatchDetail detail = reconService.getBatchDetail(batch.getId());
        assertTrue(newDetailsFor(detail, "WO-REV-BOTH").isEmpty());
        assertTrue(newDetailsFor(detail, "WO-REV-BOTH-R").isEmpty());
    }

    // ---- helpers ----

    private ReconBatch run(LocalDate date) {
        return reconService.runReconciliation(date, ReconConstants.TRIGGER_MANUAL, uniq("REQ-RUN-"));
    }

    private Machine createMachine() {
        MachineCreateRequest req = new MachineCreateRequest();
        req.setMachineCode(uniq("M-REC-"));
        req.setMachineName("勾稽测试拖拉机");
        req.setRequestNo(uniq("REQ-M-"));
        return machineService.create(req);
    }

    private WorkOrder createCompletedOrder(String orderNo, Long machineId, BigDecimal area) {
        WorkOrderCreateRequest create = new WorkOrderCreateRequest();
        create.setOrderNo(orderNo);
        create.setMachineId(machineId);
        create.setOperationType("收割");
        create.setRequestNo(uniq("REQ-WO-"));
        WorkOrder order = workOrderService.create(create);

        TrackAreaRequest track = new TrackAreaRequest();
        track.setTrackArea(area);
        track.setRequestNo(uniq("REQ-TRACK-"));
        workOrderService.reportTrackArea(order.getId(), track);

        workOrderService.transit(order.getId(), transit(WorkOrderStatus.IN_PROGRESS));
        workOrderService.transit(order.getId(), transit(WorkOrderStatus.COMPLETED));
        return order;
    }

    private SatelliteBill uploadBill(String billNo, String orderNo, BigDecimal area) {
        SatelliteBillUploadRequest req = new SatelliteBillUploadRequest();
        req.setBillNo(billNo);
        req.setOrderNo(orderNo);
        req.setTrackArea(area);
        req.setBillTime(LocalDateTime.now());
        req.setRequestNo(uniq("REQ-BILL-"));
        return billService.upload(req);
    }

    private StatusTransitRequest transit(String target) {
        StatusTransitRequest req = new StatusTransitRequest();
        req.setTargetStatus(target);
        req.setRequestNo(uniq("REQ-TRANSIT-"));
        return req;
    }

    private ReverseRequest reverseReq(String prefix) {
        ReverseRequest req = new ReverseRequest();
        req.setRequestNo(uniq(prefix));
        return req;
    }

    private WriteoffApplyRequest applyReq(Long detailId, String adjustType) {
        WriteoffApplyRequest req = new WriteoffApplyRequest();
        req.setDetailId(detailId);
        req.setAdjustType(adjustType);
        req.setReason("测试核销原因");
        req.setRequestNo(uniq("REQ-WO-APPLY-"));
        return req;
    }

    private WriteoffApproveRequest approveReq(String approver, String remark) {
        WriteoffApproveRequest req = new WriteoffApproveRequest();
        req.setApprover(approver);
        req.setApproveRemark(remark);
        req.setRequestNo(uniq("REQ-WO-OK-"));
        return req;
    }

    private WriteoffRejectRequest rejectReq(String reason) {
        WriteoffRejectRequest req = new WriteoffRejectRequest();
        req.setRejectReason(reason);
        req.setRequestNo(uniq("REQ-WO-NO-"));
        return req;
    }

    private List<ReconDetail> newDetailsFor(BatchDetail detail, String orderNo) {
        return detail.getNewDetails().stream()
                .filter(d -> orderNo.equals(d.getOrderNo())).collect(Collectors.toList());
    }

    private List<ReconDetail> legacyDetailsFor(BatchDetail detail, String orderNo) {
        return detail.getLegacyDetails().stream()
                .filter(d -> orderNo.equals(d.getOrderNo())).collect(Collectors.toList());
    }

    private ReconDetail sole(List<ReconDetail> details) {
        assertEquals(1, details.size());
        return details.get(0);
    }

    private String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }
}
