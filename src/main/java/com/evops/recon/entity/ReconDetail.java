package com.evops.recon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 勾稽差异明细：一笔对一笔，双方原始字段都落库，差异清单可直接给两边核账人看。
 * 核销后 status=WRITTEN_OFF 且 diff 视为 0（open 统计不再计入），记录保留不物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recon_detail")
public class ReconDetail extends BaseEntity {
    private Long batchId;
    private LocalDate reconcileDate;
    /** NEW / LEGACY。 */
    private String engine;
    /** LOCAL_ONLY / REMOTE_ONLY / AMOUNT_DIFF / REVERSAL_DIFF。 */
    private String diffType;
    private String orderNo;

    // 本地侧明细（作业单/红冲单）
    private Long localId;
    private String localOrderNo;
    private BigDecimal localTrackArea;
    private LocalDateTime localTrackTime;
    private String localStatus;
    private String localReversalFlag;

    // 对方侧明细（卫星账单/红冲账单）
    private Long remoteId;
    private String remoteBillNo;
    private String remoteOrderNo;
    private BigDecimal remoteTrackArea;
    private LocalDateTime remoteBillTime;
    private String remoteStatus;
    private String remoteReversalFlag;

    /** remote - local 的有符号差额。 */
    private BigDecimal diffArea;
    private BigDecimal absDiffArea;
    /** OPEN / WRITTEN_OFF。 */
    private String status;

    /** 上一批次已核销、本批快照仍对不平而结转的差异来源明细 ID。 */
    private Long carriedFromDetailId;
    private Long writeoffId;
    private LocalDateTime writeoffTime;
}
