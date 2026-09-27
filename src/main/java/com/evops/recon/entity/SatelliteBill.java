package com.evops.recon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 卫星轨迹核算平台账单（对方账），不物理删除，红冲只改状态并另开反向账单。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_satellite_bill")
public class SatelliteBill extends BaseEntity {
    private String billNo;
    private String orderNo;
    private Long machineId;
    private BigDecimal trackArea;
    private LocalDateTime billTime;
    private String status;
    /** 0 正常账单 / 1 红冲反向账单。 */
    private String reversalFlag;
    /** 反向账单指向被红冲账单 ID。 */
    private Long reversesBillId;
    private String remark;

    @Version
    private Integer version = 0;
}
