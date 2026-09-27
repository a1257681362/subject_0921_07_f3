package com.evops.recon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 勾稽批次：一次定时/手工勾稽一行，记录双引擎口径结果与比对结论。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recon_batch")
public class ReconBatch extends BaseEntity {
    private String batchNo;
    private LocalDate reconcileDate;
    /** SCHEDULED / MANUAL。 */
    private String triggeredBy;
    /** PARALLEL / NEW / LEGACY。 */
    private String engineMode;
    private BigDecimal tolerance;
    /** RUNNING / DONE / FAILED。 */
    private String status;

    // 新引擎口径
    private Integer engMatchedCount;
    private Integer engLocalOnlyCount;
    private Integer engRemoteOnlyCount;
    private Integer engAmountDiffCount;
    private Integer engDiffCount;
    private Integer engResolvedDiffCount;
    private Integer engOpenDiffCount;
    private BigDecimal engLocalTotal;
    private BigDecimal engRemoteTotal;
    private BigDecimal engDiffTotal;

    // 旧引擎口径（并跑期）
    private Integer legMatchedCount;
    private Integer legDiffCount;
    private Integer legOpenDiffCount;
    private BigDecimal legLocalTotal;
    private BigDecimal legRemoteTotal;
    private BigDecimal legDiffTotal;

    /** MATCH / MISMATCH。 */
    private String engineCompareResult;
    private String engineCompareDetail;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String remark;
}
