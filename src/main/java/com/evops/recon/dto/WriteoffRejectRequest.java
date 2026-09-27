package com.evops.recon.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 核销驳回：差异保持 OPEN，可重新发起核销。 */
@Data
public class WriteoffRejectRequest {
    @NotBlank(message = "驳回原因不能为空")
    private String rejectReason;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
