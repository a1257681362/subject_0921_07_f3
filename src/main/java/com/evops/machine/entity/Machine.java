package com.evops.machine.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import com.evops.machine.MachineStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_machine")
public class Machine extends BaseEntity {
    private String machineCode;
    private String machineName;
    private String machineType;
    private String plateNo;
    /** ACTIVE 在用 / DISABLED 停用；不物理删除。 */
    private String status = MachineStatus.ACTIVE;
    private String remark;

    @Version
    private Integer version = 0;
}
