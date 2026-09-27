package com.evops.workorder.exchange;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.workorder.exchange.vo.ImportResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 作业单文件交换：CSV 导出、导入模板下载、批量导入。
 * 格式与转义规则见 docs/work-order-csv-exchange.md；值班处置见 docs/work-order-csv-runbook.md。
 */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderExchangeController {

    private static final MediaType CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final WorkOrderExchangeService exchangeService;

    public WorkOrderExchangeController(WorkOrderExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    /** 导出作业单 CSV（仅正常单；status 可选过滤）。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String status) {
        return csvAttachment("work-orders-export.csv", exchangeService.exportCsv(status));
    }

    /** 下载导入模板（仅表头）。 */
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return csvAttachment("work-order-import-template.csv", exchangeService.templateCsv());
    }

    /** 批量导入：multipart 上传 CSV，逐行独立事务，逐行返回结果，失败行可定位。 */
    @PostMapping("/import")
    public ApiResponse<ImportResult> importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("导入文件为空");
        }
        return ApiResponse.ok(exchangeService.importCsv(file.getBytes()));
    }

    private ResponseEntity<byte[]> csvAttachment(String filename, byte[] content) {
        return ResponseEntity.ok()
                .contentType(CSV_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .body(content);
    }
}
