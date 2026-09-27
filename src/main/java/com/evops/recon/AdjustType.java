package com.evops.recon;

/** 手工调整（核销）类型。 */
public final class AdjustType {

    /** 本地多记：冲减本地账。 */
    public static final String LOCAL_OVER = "LOCAL_OVER";
    /** 本地少记：补记本地账。 */
    public static final String LOCAL_SHORT = "LOCAL_SHORT";
    /** 对方多记：联系对方冲减，本侧核销挂账。 */
    public static final String REMOTE_OVER = "REMOTE_OVER";
    /** 对方少记：对方补账，本侧核销挂账。 */
    public static final String REMOTE_SHORT = "REMOTE_SHORT";
    /** 口径差异（含红冲口径）：按权威轨迹面积确认核销。 */
    public static final String CALIBER_DIFF = "CALIBER_DIFF";

    private AdjustType() {
    }
}
