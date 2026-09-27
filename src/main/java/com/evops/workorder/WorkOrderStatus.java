package com.evops.workorder;

/** 作业单状态机。 */
public final class WorkOrderStatus {
    public static final String CREATED = "CREATED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    /** 已红冲（原单与反向单都落此状态）。 */
    public static final String REVERSED = "REVERSED";

    private WorkOrderStatus() {
    }

    /** 合法的人工状态流转；完工必须先有轨迹核算面积。 */
    public static boolean canTransit(String from, String to) {
        if (CANCELLED.equals(to)) {
            return CREATED.equals(from) || IN_PROGRESS.equals(from);
        }
        switch (from) {
            case CREATED:
                return IN_PROGRESS.equals(to);
            case IN_PROGRESS:
                return COMPLETED.equals(to);
            default:
                return false;
        }
    }
}
