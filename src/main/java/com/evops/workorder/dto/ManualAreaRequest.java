package com.evops.workorder.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 人工上报面积——仅作参考，不参与完工核算。 */
@Data
public class ManualAreaRequest {
    @NotNull(message = "人工上报面积不能为空")
    @DecimalMin(value = "0.01", message = "人工上报面积必须大于 0")
    private BigDecimal manualArea;
    @NotBlank(message = "上报人不能为空")
    private String reporter;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
