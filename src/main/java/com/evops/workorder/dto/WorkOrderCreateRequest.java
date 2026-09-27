package com.evops.workorder.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class WorkOrderCreateRequest {
    @NotBlank(message = "作业单号不能为空")
    private String orderNo;
    @NotNull(message = "农机不能为空")
    private Long machineId;
    @NotBlank(message = "作业类型不能为空")
    private String operationType;
    private String plotName;
    private String remark;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
