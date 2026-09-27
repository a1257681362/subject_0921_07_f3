package com.evops.workorder.exchange.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 作业单批量导入结果。逐行给出结论，失败行带物理行号（表头为第 1 行）、
 * 规范化单行表示与失败原因，可直接按行号回到源文件定位。
 */
public class ImportResult {

    /** 行处理结论。 */
    public static final String ROW_CREATED = "CREATED";
    public static final String ROW_SKIPPED = "SKIPPED";
    public static final String ROW_FAILED = "FAILED";

    /** 数据行总数（不含表头与空白行）。 */
    private int totalRows;
    private int createdCount;
    private int skippedCount;
    private int failedCount;
    private List<RowResult> rows = new ArrayList<>();

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getCreatedCount() { return createdCount; }
    public void setCreatedCount(int createdCount) { this.createdCount = createdCount; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public List<RowResult> getRows() { return rows; }
    public void setRows(List<RowResult> rows) { this.rows = rows; }

    /** 单行处理结果。 */
    public static class RowResult {
        /** 物理起始行号（表头为第 1 行，数据从第 2 行起）。 */
        private int lineNo;
        /** 行内作业单号（能解析到时）。 */
        private String orderNo;
        /** CREATED 新建 / SKIPPED 已存在且内容一致跳过 / FAILED 失败。 */
        private String status;
        /** 失败原因或跳过说明。 */
        private String message;
        /** 该行的规范化单行表示（截断展示），定位以 lineNo 为准。 */
        private String rawLine;

        public int getLineNo() { return lineNo; }
        public void setLineNo(int lineNo) { this.lineNo = lineNo; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getRawLine() { return rawLine; }
        public void setRawLine(String rawLine) { this.rawLine = rawLine; }
    }
}
