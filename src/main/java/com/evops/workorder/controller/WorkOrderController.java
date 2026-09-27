package com.evops.workorder.controller;

import com.evops.common.ApiResponse;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.dto.ManualAreaRequest;
import com.evops.workorder.dto.ReverseRequest;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.vo.WorkOrderDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    public WorkOrderController(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    /** 作业单建档。 */
    @PostMapping
    public ApiResponse<WorkOrder> create(@Valid @RequestBody WorkOrderCreateRequest request) {
        return ApiResponse.ok(workOrderService.create(request));
    }

    /** 按作业单号检索。 */
    @GetMapping
    public ApiResponse<WorkOrderDetail> getByOrderNo(@RequestParam String orderNo) {
        return ApiResponse.ok(workOrderService.findByOrderNo(orderNo));
    }

    /** 状态流转。 */
    @PostMapping("/{id}/transit")
    public ApiResponse<WorkOrder> transit(@PathVariable Long id,
                                          @Valid @RequestBody StatusTransitRequest request) {
        return ApiResponse.ok(workOrderService.transit(id, request));
    }

    /** 轨迹核算面积（权威）。 */
    @PostMapping("/{id}/track-area")
    public ApiResponse<WorkOrder> reportTrackArea(@PathVariable Long id,
                                                  @Valid @RequestBody TrackAreaRequest request) {
        return ApiResponse.ok(workOrderService.reportTrackArea(id, request));
    }

    /** 人工上报面积（仅参考）。 */
    @PostMapping("/{id}/manual-area")
    public ApiResponse<WorkOrder> reportManualArea(@PathVariable Long id,
                                                   @Valid @RequestBody ManualAreaRequest request) {
        return ApiResponse.ok(workOrderService.reportManualArea(id, request));
    }

    /** 红冲：原单置 REVERSED 并另开反向单据。 */
    @PostMapping("/{id}/reverse")
    public ApiResponse<WorkOrder> reverse(@PathVariable Long id,
                                          @Valid @RequestBody ReverseRequest request) {
        return ApiResponse.ok(workOrderService.reverse(id, request));
    }
}
