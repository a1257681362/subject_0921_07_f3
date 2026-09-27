package com.evops.recon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 核销（手工调整）台账：申请 → 审批/驳回。
 * 审批与差异清零同一事务；驳回只留痕，差异仍 OPEN。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recon_writeoff")
public class ReconWriteoff extends BaseEntity {
    private String writeoffNo;
    private Long detailId;
    private LocalDate reconcileDate;
    private String orderNo;
    /** 核销时的差异类型（用于结转判定）。 */
    private String diffType;
    /** LOCAL_OVER / LOCAL_SHORT / REMOTE_OVER / REMOTE_SHORT / CALIBER_DIFF。 */
    private String adjustType;
    private BigDecimal diffArea;
    /** 申请时双方明细快照（JSON 字符串），便于事后还原。 */
    private String localSideSnapshot;
    private String remoteSideSnapshot;
    private String reason;
    /** APPLIED / APPROVED / REJECTED。 */
    private String status;
    private String approver;
    private LocalDateTime approveTime;
    private String approveRemark;
    private String rejectReason;
    private String requestNo;

    @Version
    private Integer version = 0;
}
