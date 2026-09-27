package com.evops.recon.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 卫星账单红冲（对方账误录更正），另开反向账单。 */
@Data
public class SatelliteBillReverseRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String remark;
}
