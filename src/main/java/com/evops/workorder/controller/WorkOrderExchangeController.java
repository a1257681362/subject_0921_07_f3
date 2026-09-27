package com.evops.workorder.controller;

import com.evops.common.ApiResponse;
import com.evops.common.BusinessException;
import com.evops.workorder.exchange.WorkOrderExchangeService;
import com.evops.workorder.exchange.dto.ImportResult;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 作业单文件交换：CSV 导出、导入模板下载、批量导入。
 * 格式与转义规则见 docs/work-order-csv-exchange.md。
 */
@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderExchangeController {

    private static final MediaType TEXT_CSV_UTF8 =
            new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final WorkOrderExchangeService exchangeService;

    public WorkOrderExchangeController(WorkOrderExchangeService exchangeService) {
        this.exchangeService = exchangeService;
    }

    /** 导出作业单 CSV（与导入模板同列，可直接再导入）。可选过滤：orderNo/status/machineCode。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String orderNo,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String machineCode) {
        byte[] csv = exchangeService.exportCsv(orderNo, status, machineCode);
        String filename = "work-orders-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(TEXT_CSV_UTF8)
                .body(csv);
    }

    /** 导入模板下载：表头 + 示例行（演示转义写法），示例行导入前需删除。 */
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=work-order-import-template.csv")
                .contentType(TEXT_CSV_UTF8)
                .body(exchangeService.templateCsv());
    }

    /**
     * 批量导入。multipart 表单：file（CSV 文件）+ requestNo（批次请求号，防重复提交）。
     * 返回逐行结果：CREATED 新建 / SKIPPED 已存在跳过 / FAILED 失败（带行号与原因）。
     */
    @PostMapping("/import")
    public ApiResponse<ImportResult> importCsv(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "requestNo", required = false)
                                               String requestNo) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件为空");
        }
        try {
            return ApiResponse.ok(exchangeService.importCsv(
                    file.getBytes(), file.getOriginalFilename(), requestNo));
        } catch (IOException e) {
            throw new BusinessException("文件读取失败，请重新上传");
        }
    }
}
