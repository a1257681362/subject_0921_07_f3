package com.evops.recon.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 在线调整匹配容差（亩）。 */
@Data
public class ToleranceUpdateRequest {
    @NotNull(message = "匹配容差不能为空")
    @DecimalMin(value = "0.00", message = "匹配容差不能为负")
    private BigDecimal tolerance;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
