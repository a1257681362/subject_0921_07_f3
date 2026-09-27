package com.evops.workorder.exchange.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入结果。逐行给结论，失败行可凭 lineNo 定位回原文件；
 * 重复导入同一文件时，已建单全部 SKIPPED，库内数据不变（幂等）。
 */
@Data
public class ImportResult {
    /** 导入批次号（IMP- 开头），行级请求号 = 批次号-行号，落 t_request_record 台账。 */
    private String batchNo;
    /** 数据记录总数（不含表头与空行）。 */
    private int totalRows;
    private int successCount;
    private int skippedCount;
    private int failedCount;
    private List<ImportRowResult> rows = new ArrayList<>();

    public void addRow(ImportRowResult row) {
        rows.add(row);
        totalRows++;
        if (ImportRowResult.SUCCESS.equals(row.getResult())) {
            successCount++;
        } else if (ImportRowResult.SKIPPED.equals(row.getResult())) {
            skippedCount++;
        } else {
            failedCount++;
        }
    }
}
