package com.evops.workorder.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class StatusTransitRequest {
    @NotBlank(message = "目标状态不能为空")
    private String targetStatus;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
