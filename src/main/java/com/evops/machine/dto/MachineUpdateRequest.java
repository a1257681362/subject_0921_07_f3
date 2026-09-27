package com.evops.machine.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class MachineUpdateRequest {
    @NotBlank(message = "农机名称不能为空")
    private String machineName;
    private String machineType;
    private String plateNo;
    private String remark;
    /** 建档/最近一次变更返回的版本号，防止并发覆盖。 */
    @NotNull(message = "版本号不能为空")
    private Integer version;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
