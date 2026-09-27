package com.evops.recon.snapshot;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 勾稽快照中的一笔账：本地作业单或卫星账单的统一视图。
 * 红冲单 reversalFlag=1、area 取负；被红冲原单 status=REVERSED 但金额保留。
 */
@Data
public class ReconEntry {
    /** 本地作业单 ID / 对方账单 ID。 */
    private Long id;
    /** 业务单号（红冲单为 原单号-R，两侧一致）。 */
    private String orderNo;
    /** 对方侧账单号，本地侧为空。 */
    private String refNo;
    private BigDecimal area;
    /** 本地 track_time / 对方 bill_time。 */
    private LocalDateTime bizTime;
    private String status;
    /** 0 正常 / 1 红冲反向。 */
    private String reversalFlag;

    public boolean isReversal() {
        return "1".equals(reversalFlag);
    }
}
