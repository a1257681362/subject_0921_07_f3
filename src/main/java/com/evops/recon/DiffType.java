package com.evops.recon;

/** 差异类型：一笔对一笔勾稽后的三种不一致 + 红冲对不平。 */
public final class DiffType {

    /** 本地有、对方无。 */
    public static final String LOCAL_ONLY = "LOCAL_ONLY";
    /** 对方有、本地无。 */
    public static final String REMOTE_ONLY = "REMOTE_ONLY";
    /** 双方都有但面积差超容差。 */
    public static final String AMOUNT_DIFF = "AMOUNT_DIFF";
    /** 红冲口径对不平（旧引擎把红冲当普通账导致的结构性差异）。 */
    public static final String REVERSAL_DIFF = "REVERSAL_DIFF";

    private DiffType() {
    }
}
