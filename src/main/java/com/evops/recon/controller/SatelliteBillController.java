package com.evops.recon.controller;

import com.evops.common.ApiResponse;
import com.evops.recon.SatelliteBillService;
import com.evops.recon.dto.SatelliteBillReverseRequest;
import com.evops.recon.dto.SatelliteBillUploadRequest;
import com.evops.recon.entity.SatelliteBill;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/satellite-bills")
public class SatelliteBillController {

    private final SatelliteBillService billService;

    public SatelliteBillController(SatelliteBillService billService) {
        this.billService = billService;
    }

    /** 卫星轨迹核算平台账单录入（对方账）。 */
    @PostMapping
    public ApiResponse<SatelliteBill> upload(@Valid @RequestBody SatelliteBillUploadRequest request) {
        return ApiResponse.ok(billService.upload(request));
    }

    /** 卫星账单红冲：原账单置 REVERSED 并另开反向账单。 */
    @PostMapping("/{id}/reverse")
    public ApiResponse<SatelliteBill> reverse(@PathVariable Long id,
                                              @Valid @RequestBody SatelliteBillReverseRequest request) {
        return ApiResponse.ok(billService.reverse(id, request));
    }

    /** 按账单号查询。 */
    @GetMapping
    public ApiResponse<SatelliteBill> getByBillNo(@RequestParam String billNo) {
        return ApiResponse.ok(billService.findByBillNo(billNo));
    }

    /** 按作业单号查询双方对账用的账单列表。 */
    @GetMapping("/by-order")
    public ApiResponse<List<SatelliteBill>> listByOrderNo(@RequestParam String orderNo) {
        return ApiResponse.ok(billService.listByOrderNo(orderNo));
    }
}
