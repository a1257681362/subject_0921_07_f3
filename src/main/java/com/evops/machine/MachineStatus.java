package com.evops.machine;

/** 农机状态。历史数据不物理删除，停用即终态。 */
public final class MachineStatus {
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    private MachineStatus() {
    }
}
