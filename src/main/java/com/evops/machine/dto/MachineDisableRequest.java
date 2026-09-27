package com.evops.machine.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class MachineDisableRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
