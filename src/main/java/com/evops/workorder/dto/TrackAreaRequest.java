package com.evops.workorder.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 轨迹核算面积上报——作业面积的权威来源。 */
@Data
public class TrackAreaRequest {
    @NotNull(message = "轨迹核算面积不能为空")
    @DecimalMin(value = "0.01", message = "轨迹核算面积必须大于 0")
    private BigDecimal trackArea;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
