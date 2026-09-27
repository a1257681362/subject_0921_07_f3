package com.evops.recon.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 核销审批：通过后差异即时清零。 */
@Data
public class WriteoffApproveRequest {
    @NotBlank(message = "审批人不能为空")
    private String approver;
    private String approveRemark;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
