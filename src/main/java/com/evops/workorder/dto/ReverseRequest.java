package com.evops.workorder.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ReverseRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String remark;
}
