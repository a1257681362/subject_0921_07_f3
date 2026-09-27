package com.evops.recon;

/** 勾稽相关状态常量。 */
public final class ReconConstants {

    /** 引擎模式。 */
    public static final String MODE_PARALLEL = "PARALLEL";
    public static final String MODE_NEW = "NEW";
    public static final String MODE_LEGACY = "LEGACY";

    /** 引擎标识（明细 engine 列）。 */
    public static final String ENGINE_NEW = "NEW";
    public static final String ENGINE_LEGACY = "LEGACY";

    /** 批次状态。 */
    public static final String BATCH_RUNNING = "RUNNING";
    public static final String BATCH_DONE = "DONE";
    public static final String BATCH_FAILED = "FAILED";

    /** 差异明细状态。 */
    public static final String DIFF_OPEN = "OPEN";
    public static final String DIFF_WRITTEN_OFF = "WRITTEN_OFF";

    /** 双引擎比对结果。 */
    public static final String COMPARE_MATCH = "MATCH";
    public static final String COMPARE_MISMATCH = "MISMATCH";

    /** 核销状态。 */
    public static final String WRITEOFF_APPLIED = "APPLIED";
    public static final String WRITEOFF_APPROVED = "APPROVED";
    public static final String WRITEOFF_REJECTED = "REJECTED";

    /** 触发方式。 */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    private ReconConstants() {
    }
}
