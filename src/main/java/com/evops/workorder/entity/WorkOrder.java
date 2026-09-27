package com.evops.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_work_order")
public class WorkOrder extends BaseEntity {
    private String orderNo;
    private Long machineId;
    private String operationType;
    private String plotName;

    /** 轨迹核算面积——计费/完工的权威依据。 */
    private BigDecimal trackArea;
    private LocalDateTime trackTime;

    /** 人工上报面积——仅作参考，不参与权威核算。 */
    private BigDecimal manualArea;
    private String manualReporter;
    private LocalDateTime manualReportTime;

    private String status;
    /** 0 正常单 / 1 红冲反向单。 */
    private String reversalFlag;
    /** 反向单指向原作业单 ID。 */
    private Long originOrderId;
    private String remark;

    @Version
    private Integer version = 0;
}
