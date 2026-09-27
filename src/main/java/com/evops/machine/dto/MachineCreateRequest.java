package com.evops.machine.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class MachineCreateRequest {
    @NotBlank(message = "农机编号不能为空")
    private String machineCode;
    @NotBlank(message = "农机名称不能为空")
    private String machineName;
    private String machineType;
    private String plateNo;
    private String remark;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
