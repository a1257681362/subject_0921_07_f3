package com.evops.workorder.exchange;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 作业单交换文件列定义。导出、模板下载、导入三处共用同一份常量，
 * 保证"导出文件可直接再导入"的往返口径一致。列顺序即文件列顺序，不可随意调整。
 */
public final class WorkOrderCsvFormat {

    /** 交换列（顺序固定）：农机用业务编码 machineCode，不用内部自增 ID，保证跨环境可交换。 */
    public static final List<String> HEADERS = Collections.unmodifiableList(
            Arrays.asList("orderNo", "machineCode", "operationType", "plotName", "remark"));

    /** 表头行文本（导入时按此精确校验，防列错位）。 */
    public static final String HEADER_LINE = "orderNo,machineCode,operationType,plotName,remark";

    /** 字段长度上限，与 t_work_order / t_machine 列宽对齐，导入侧先校验、不落库报错。 */
    public static final int MAX_ORDER_NO = 40;
    public static final int MAX_MACHINE_CODE = 40;
    public static final int MAX_OPERATION_TYPE = 40;
    public static final int MAX_PLOT_NAME = 80;
    public static final int MAX_REMARK = 255;

    /** 单次导出数据行上限，超出提示先按条件过滤。 */
    public static final int EXPORT_MAX_ROWS = 10000;

    /** 单次导入数据行上限（不含表头），防止超大文件拖垮服务。 */
    public static final int IMPORT_MAX_ROWS = 5000;

    private WorkOrderCsvFormat() {
    }
}
