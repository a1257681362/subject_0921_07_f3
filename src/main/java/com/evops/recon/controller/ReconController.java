package com.evops.recon.controller;

import com.evops.common.ApiResponse;
import com.evops.recon.ReconConfigService;
import com.evops.recon.ReconService;
import com.evops.recon.WriteoffService;
import com.evops.recon.dto.EngineModeUpdateRequest;
import com.evops.recon.dto.ReconRunRequest;
import com.evops.recon.dto.ToleranceUpdateRequest;
import com.evops.recon.dto.WriteoffApplyRequest;
import com.evops.recon.dto.WriteoffApproveRequest;
import com.evops.recon.dto.WriteoffRejectRequest;
import com.evops.recon.entity.ReconBatch;
import com.evops.recon.entity.ReconDetail;
import com.evops.recon.entity.ReconWriteoff;
import com.evops.recon.vo.BatchDetail;
import com.evops.recon.vo.CutoverCheck;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recon")
public class ReconController {

    private final ReconService reconService;
    private final ReconConfigService configService;
    private final WriteoffService writeoffService;

    public ReconController(ReconService reconService,
                           ReconConfigService configService,
                           WriteoffService writeoffService) {
        this.reconService = reconService;
        this.configService = configService;
        this.writeoffService = writeoffService;
    }

    /** 手工触发某日勾稽（不传日期默认前一日）。 */
    @PostMapping("/run")
    public ApiResponse<ReconBatch> run(@RequestBody(required = false) ReconRunRequest request) {
        LocalDate date = request == null ? null : request.getReconcileDate();
        String requestNo = request == null ? null : request.getRequestNo();
        return ApiResponse.ok(reconService.runReconciliation(date, "MANUAL", requestNo));
    }

    /** 最近批次列表。 */
    @GetMapping("/batches")
    public ApiResponse<List<ReconBatch>> batches(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(reconService.listRecentBatches(limit));
    }

    /** 批次详情：批次头 + 新旧引擎两份逐笔差异清单。 */
    @GetMapping("/batches/{id}")
    public ApiResponse<BatchDetail> batchDetail(@PathVariable Long id) {
        return ApiResponse.ok(reconService.getBatchDetail(id));
    }

    /** 未核销差异清单（带双方明细）；不传日期取最新批次。 */
    @GetMapping("/open-diffs")
    public ApiResponse<List<ReconDetail>> openDiffs(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso =
                    org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate reconcileDate) {
        return ApiResponse.ok(reconService.listOpenDiffs(reconcileDate));
    }

    /** 切换闸门检查：最近 N 批双引擎一致且差异已核销清零才可切。 */
    @GetMapping("/cutover-check")
    public ApiResponse<CutoverCheck> cutoverCheck() {
        return ApiResponse.ok(reconService.checkCutoverReady());
    }

    /** 当前容差与引擎模式。 */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        Map<String, Object> map = new HashMap<>();
        BigDecimal tolerance = configService.getTolerance();
        map.put("tolerance", tolerance);
        map.put("engineMode", configService.getEngineMode());
        return ApiResponse.ok(map);
    }

    /** 在线调整匹配容差。 */
    @PostMapping("/config/tolerance")
    public ApiResponse<Map<String, Object>> updateTolerance(@Valid @RequestBody ToleranceUpdateRequest request) {
        BigDecimal tolerance = configService.updateTolerance(request.getTolerance(), request.getRequestNo());
        Map<String, Object> map = new HashMap<>();
        map.put("tolerance", tolerance);
        return ApiResponse.ok(map);
    }

    /** 引擎模式切换（PARALLEL/NEW/LEGACY）。 */
    @PostMapping("/config/engine-mode")
    public ApiResponse<Map<String, Object>> updateEngineMode(@Valid @RequestBody EngineModeUpdateRequest request) {
        String mode = configService.updateEngineMode(request.getEngineMode(), request.getRequestNo());
        Map<String, Object> map = new HashMap<>();
        map.put("engineMode", mode);
        return ApiResponse.ok(map);
    }

    /** 发起核销（手工调整）申请。 */
    @PostMapping("/writeoffs")
    public ApiResponse<ReconWriteoff> applyWriteoff(@Valid @RequestBody WriteoffApplyRequest request) {
        return ApiResponse.ok(writeoffService.apply(request));
    }

    /** 审批通过：差异即时清零。 */
    @PostMapping("/writeoffs/{id}/approve")
    public ApiResponse<ReconWriteoff> approveWriteoff(@PathVariable Long id,
                                                      @Valid @RequestBody WriteoffApproveRequest request) {
        return ApiResponse.ok(writeoffService.approve(id, request));
    }

    /** 驳回：差异保持未平。 */
    @PostMapping("/writeoffs/{id}/reject")
    public ApiResponse<ReconWriteoff> rejectWriteoff(@PathVariable Long id,
                                                     @Valid @RequestBody WriteoffRejectRequest request) {
        return ApiResponse.ok(writeoffService.reject(id, request));
    }

    /** 核销记录（可按差异明细过滤）。 */
    @GetMapping("/writeoffs")
    public ApiResponse<List<ReconWriteoff>> listWriteoffs(@RequestParam(required = false) Long detailId) {
        return ApiResponse.ok(writeoffService.listByDetail(detailId));
    }
}
