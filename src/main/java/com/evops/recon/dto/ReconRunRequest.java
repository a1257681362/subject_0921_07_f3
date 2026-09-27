package com.evops.recon.dto;

import lombok.Data;

import java.time.LocalDate;

/** 手工触发勾稽；不传日期默认勾稽前一日。 */
@Data
public class ReconRunRequest {
    private LocalDate reconcileDate;
    private String requestNo;
}
