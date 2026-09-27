package com.evops.workorder.exchange.vo;

import lombok.Data;

/**
 * 导入单行结果。行号为数据记录序号：表头占第 1 行，首条数据为第 2 行，
 * 与 Excel/文本编辑器中看到的行号一致（字段含换行的多行记录除外，此时以记录序号为准）。
 */
@Data
public class ImportRowResult {

    public static final String SUCCESS = "SUCCESS";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    private int lineNo;
    private String orderNo;
    /** SUCCESS 新建 / SKIPPED 已存在跳过（幂等） / FAILED 校验或落库失败。 */
    private String result;
    /** 失败原因或跳过说明；成功时为空。 */
    private String message;

    public ImportRowResult() {
    }

    public ImportRowResult(int lineNo, String orderNo, String result, String message) {
        this.lineNo = lineNo;
        this.orderNo = orderNo;
        this.result = result;
        this.message = message;
    }
}
