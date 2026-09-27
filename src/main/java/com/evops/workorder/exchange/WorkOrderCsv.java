package com.evops.workorder.exchange;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 作业单 CSV 文件交换的列定义。
 * 可交换列（模板列、导入识别列）固定 5 列；导出在其后追加参考列，导入时忽略参考列与未知列。
 * 列按表头名称匹配，顺序不敏感；表头名称以本类常量为准，改动需同步 docs/work-order-csv-exchange.md。
 */
public final class WorkOrderCsv {

    public static final String COL_ORDER_NO = "作业单号";
    public static final String COL_MACHINE_CODE = "农机编码";
    public static final String COL_OPERATION_TYPE = "作业类型";
    public static final String COL_PLOT_NAME = "地块名称";
    public static final String COL_REMARK = "备注";

    /** 参考列：仅导出展示，导入忽略。面积权威口径走轨迹核算接口，不允许经 CSV 写入。 */
    public static final String COL_STATUS = "状态";
    public static final String COL_TRACK_AREA = "轨迹核算面积";
    public static final String COL_MANUAL_AREA = "人工上报面积";

    /** 可交换列（导入模板 = 该 5 列表头）。 */
    public static final List<String> EXCHANGE_HEADERS = Collections.unmodifiableList(Arrays.asList(
            COL_ORDER_NO, COL_MACHINE_CODE, COL_OPERATION_TYPE, COL_PLOT_NAME, COL_REMARK));

    /** 导出列 = 可交换列 + 参考列。 */
    public static final List<String> EXPORT_HEADERS = Collections.unmodifiableList(Arrays.asList(
            COL_ORDER_NO, COL_MACHINE_CODE, COL_OPERATION_TYPE, COL_PLOT_NAME, COL_REMARK,
            COL_STATUS, COL_TRACK_AREA, COL_MANUAL_AREA));

    /** 导入必填列（表头必须出现，且每条记录不能为空）。 */
    public static final List<String> REQUIRED_HEADERS = Collections.unmodifiableList(Arrays.asList(
            COL_ORDER_NO, COL_MACHINE_CODE, COL_OPERATION_TYPE));

    /** 红冲反向单号保留后缀，导入单号不允许占用。 */
    public static final String REVERSAL_SUFFIX = "-R";

    private WorkOrderCsv() {
    }
}
