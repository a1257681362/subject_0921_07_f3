package com.evops.api;

import com.evops.machine.MachineService;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.entity.Machine;
import com.evops.recon.SatelliteBillService;
import com.evops.recon.dto.SatelliteBillUploadRequest;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 勾稽接口闭环：触发批次 → 查差异 → 核销申请/审批 → 差异清零。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MachineService machineService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private SatelliteBillService billService;

    @Test
    void runRecon_thenWriteoff_clearsDiff() throws Exception {
        String tag = String.valueOf(SEQ.incrementAndGet());
        LocalDate date = LocalDate.now().plusDays(50 + SEQ.get());
        String orderNo = "WO-API-REC-" + tag;

        MachineCreateRequest mc = new MachineCreateRequest();
        mc.setMachineCode("M-API-REC-" + tag);
        mc.setMachineName("勾稽接口测试机");
        mc.setRequestNo("REQ-API-REC-M-" + tag);
        Machine machine = machineService.create(mc);

        WorkOrderCreateRequest wc = new WorkOrderCreateRequest();
        wc.setOrderNo(orderNo);
        wc.setMachineId(machine.getId());
        wc.setOperationType("收割");
        wc.setRequestNo("REQ-API-REC-W-" + tag);
        WorkOrder order = workOrderService.create(wc);
        TrackAreaRequest track = new TrackAreaRequest();
        track.setTrackArea(new BigDecimal("10.00"));
        track.setRequestNo("REQ-API-REC-T-" + tag);
        workOrderService.reportTrackArea(order.getId(), track);
        StatusTransitRequest ip = new StatusTransitRequest();
        ip.setTargetStatus(WorkOrderStatus.IN_PROGRESS);
        ip.setRequestNo("REQ-API-REC-I-" + tag);
        workOrderService.transit(order.getId(), ip);
        StatusTransitRequest done = new StatusTransitRequest();
        done.setTargetStatus(WorkOrderStatus.COMPLETED);
        done.setRequestNo("REQ-API-REC-C-" + tag);
        workOrderService.transit(order.getId(), done);

        SatelliteBillUploadRequest bill = new SatelliteBillUploadRequest();
        bill.setBillNo("SB-API-REC-" + tag);
        bill.setOrderNo(orderNo);
        bill.setTrackArea(new BigDecimal("10.80"));
        bill.setBillTime(LocalDateTime.now());
        bill.setRequestNo("REQ-API-REC-B-" + tag);
        billService.upload(bill);

        // 当前配置：容差 0.05、并跑
        mockMvc.perform(get("/api/recon/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tolerance").value(0.05))
                .andExpect(jsonPath("$.data.engineMode").value("PARALLEL"));

        // 触发勾稽
        String runBody = "{\"reconcileDate\":\"" + date + "\",\"requestNo\":\"REQ-API-REC-RUN-" + tag + "\"}";
        String runResp = mockMvc.perform(post("/api/recon/run")
                        .contentType(MediaType.APPLICATION_JSON).content(runBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.engAmountDiffCount").value(1))
                .andReturn().getResponse().getContentAsString();
        Long batchId = ((Number) com.jayway.jsonpath.JsonPath.read(runResp, "$.data.id")).longValue();

        // 批次详情能拿到带双方明细的差异（与其他测试类共享内存库，按单号过滤断言）
        mockMvc.perform(get("/api/recon/batches/" + batchId))
                .andExpect(jsonPath("$.data.newDetails[?(@.orderNo=='" + orderNo + "')].remoteBillNo")
                        .value(org.hamcrest.Matchers.hasItem("SB-API-REC-" + tag)));

        // 未平清单
        String openResp = mockMvc.perform(get("/api/recon/open-diffs").param("reconcileDate", date.toString()))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        java.util.List<Number> ids = com.jayway.jsonpath.JsonPath.read(
                openResp, "$.data[?(@.orderNo=='" + orderNo + "')].id");
        Long detailId = ids.get(0).longValue();

        // 核销申请 → 审批
        String applyBody = "{\"detailId\":" + detailId + ",\"adjustType\":\"CALIBER_DIFF\","
                + "\"reason\":\"接口测试核销\",\"requestNo\":\"REQ-API-REC-APPLY-" + tag + "\"}";
        String applyResp = mockMvc.perform(post("/api/recon/writeoffs")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andReturn().getResponse().getContentAsString();
        Long writeoffId = ((Number) com.jayway.jsonpath.JsonPath.read(applyResp, "$.data.id")).longValue();

        String approveBody = "{\"approver\":\"赵审批\",\"approveRemark\":\"同意\","
                + "\"requestNo\":\"REQ-API-REC-OK-" + tag + "\"}";
        mockMvc.perform(post("/api/recon/writeoffs/" + writeoffId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 差异即时清零：未平清单里没有这笔，批次内该笔明细已置 WRITTEN_OFF
        mockMvc.perform(get("/api/recon/open-diffs").param("reconcileDate", date.toString()))
                .andExpect(jsonPath("$.data[?(@.orderNo=='" + orderNo + "')]").isEmpty());
        mockMvc.perform(get("/api/recon/batches/" + batchId))
                .andExpect(jsonPath("$.data.newDetails[?(@.orderNo=='" + orderNo + "')].status")
                        .value(org.hamcrest.Matchers.hasItem("WRITTEN_OFF")));
    }
}
