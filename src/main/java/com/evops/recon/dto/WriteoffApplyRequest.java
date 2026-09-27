package com.evops.recon.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 核销申请：针对一笔未平差异发起手工调整。 */
@Data
public class WriteoffApplyRequest {
    @NotNull(message = "差异明细 ID 不能为空")
    private Long detailId;
    @NotBlank(message = "调整类型不能为空")
    private String adjustType;
    @NotBlank(message = "核销原因不能为空")
    private String reason;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
