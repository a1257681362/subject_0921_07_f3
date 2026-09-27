package com.evops.recon.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 卫星轨迹核算平台账单录入（对方账推送/导入）。 */
@Data
public class SatelliteBillUploadRequest {
    @NotBlank(message = "卫星账单号不能为空")
    private String billNo;
    @NotBlank(message = "作业单号不能为空")
    private String orderNo;
    private Long machineId;
    @NotNull(message = "核算面积不能为空")
    @DecimalMin(value = "0.01", message = "核算面积必须大于 0")
    private BigDecimal trackArea;
    @NotNull(message = "账单时间不能为空")
    private LocalDateTime billTime;
    private String remark;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
