package com.evops.recon.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 引擎模式切换：PARALLEL / NEW / LEGACY。 */
@Data
public class EngineModeUpdateRequest {
    @NotBlank(message = "引擎模式不能为空")
    private String engineMode;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
