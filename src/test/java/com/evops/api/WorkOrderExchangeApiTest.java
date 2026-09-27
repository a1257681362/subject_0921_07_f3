package com.evops.api;

import com.evops.workorder.exchange.CsvCodec;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 作业单文件交换：模板下载、导出转义、批量导入、往返一致、幂等。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderExchangeApiTest {

    private static final String HEADER = "orderNo,machineCode,operationType,plotName,remark";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void template_downloadableAndParseable() throws Exception {
        byte[] body = mockMvc.perform(get("/api/work-orders/import-template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("work-order-import-template.csv")))
                .andReturn().getResponse().getContentAsByteArray();

        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.startsWith(CsvCodec.BOM), "模板应带 BOM");
        assertTrue(text.contains(HEADER), "模板应含标准表头");
        // 模板可被自身解析器读取：表头 + 2 行示例
        List<CsvCodec.CsvRecord> records = CsvCodec.parse(text);
        assertEquals(3, records.size());
        assertEquals(5, records.get(0).getFields().size());
    }

    @Test
    void export_escapesSpecialFields() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String machineCode = "M-EXP-" + suffix;
        long machineId = createMachine(machineCode);
        String orderNo = "WO-EXP-" + suffix;
        // plotName 含逗号、remark 含引号，验证 RFC 4180 转义
        createOrder(orderNo, machineId, "播种", "东,西地块", "说\"你好\"");

        byte[] body = mockMvc.perform(get("/api/work-orders/export").param("orderNo", orderNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String text = new String(body, StandardCharsets.UTF_8);
        String expected = CsvCodec.BOM + HEADER + "\r\n"
                + orderNo + "," + machineCode + ",播种,\"东,西地块\",\"说\"\"你好\"\"\"\r\n";
        assertEquals(expected, text);
    }

    @Test
    void importThenExport_roundTripIdentical() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String machineCode = "M-RT-" + suffix;
        createMachine(machineCode);

        // 构造 CSV：含空字段、逗号、引号、字段内换行；行按 orderNo 升序（与导出一致）
        String csv = CsvCodec.BOM + HEADER + "\r\n"
                + "WO-RT-" + suffix + "-01," + machineCode + ",播种,东地块1号,\r\n"
                + "WO-RT-" + suffix + "-02," + machineCode + ",\"收割,秸秆还田\",\"西地块, 示范\",\"说\"\"你好\"\"\"\r\n"
                + "WO-RT-" + suffix + "-03," + machineCode + ",耕地,,\"第一行\n第二行\"\r\n";

        mockMvc.perform(multipart("/api/work-orders/import")
                        .file(csvFile(csv))
                        .param("requestNo", "REQ-RT-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalRows").value(3))
                .andExpect(jsonPath("$.data.createdCount").value(3))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        // 导入后再导出，应与原文件逐字节一致
        byte[] exported = mockMvc.perform(get("/api/work-orders/export")
                        .param("machineCode", machineCode))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(csv, new String(exported, StandardCharsets.UTF_8));
    }

    @Test
    void exportImportExport_sameEnvironment_stableAndIdempotent() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        long machineId = createMachine("M-STB-" + suffix);
        createOrder("WO-STB-" + suffix, machineId, "播种", null, null);

        byte[] export1 = mockMvc.perform(get("/api/work-orders/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // 全量导出文件换个请求号再导入：全部已存在且内容一致 → 全 SKIPPED，零新建零失败
        mockMvc.perform(multipart("/api/work-orders/import")
                        .file(new MockMultipartFile("file", "export.csv", "text/csv", export1))
                        .param("requestNo", "REQ-STB-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.failedCount").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(
                        org.hamcrest.Matchers.greaterThan(0)));

        byte[] export2 = mockMvc.perform(get("/api/work-orders/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(export1, export2,
                "导出→导入→再导出应逐字节一致");
    }

    @Test
    void import_mixedRows_failedRowsLocatedByLineNo() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String machineCode = "M-IMP-" + suffix;
        long machineId = createMachine(machineCode);
        // 已存在单：一个内容将冲突、一个内容一致
        createOrder("WO-IMP-CF-" + suffix, machineId, "播种", null, null);
        createOrder("WO-IMP-SKIP-" + suffix, machineId, "播种", "东地块", null);

        String csv = HEADER + "\n"
                + "WO-IMP-NEW-" + suffix + "," + machineCode + ",播种,东地块,正常行\n"   // 行2 CREATED
                + "WO-IMP-NM-" + suffix + ",M-NONE-" + suffix + ",收割,,\n"             // 行3 农机不存在
                + "WO-IMP-CF-" + suffix + "," + machineCode + ",耕地,,\n"               // 行4 内容冲突
                + "WO-IMP-BC-" + suffix + "," + machineCode + ",播种,地块,备注,多一列\n" // 行5 列数不符
                + "," + machineCode + ",播种,,\n"                                       // 行6 单号为空
                + "WO-IMP-SKIP-" + suffix + "," + machineCode + ",播种,东地块,\n";      // 行7 SKIPPED

        String response = mockMvc.perform(multipart("/api/work-orders/import")
                        .file(csvFile(csv))
                        .param("requestNo", "REQ-IMP-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalRows").value(6))
                .andExpect(jsonPath("$.data.createdCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(4))
                .andExpect(jsonPath("$.data.rows[0].lineNo").value(2))
                .andExpect(jsonPath("$.data.rows[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.rows[1].lineNo").value(3))
                .andExpect(jsonPath("$.data.rows[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.rows[1].message").value(
                        org.hamcrest.Matchers.containsString("农机不存在")))
                .andExpect(jsonPath("$.data.rows[2].lineNo").value(4))
                .andExpect(jsonPath("$.data.rows[2].status").value("FAILED"))
                .andExpect(jsonPath("$.data.rows[2].message").value(
                        org.hamcrest.Matchers.containsString("内容冲突")))
                .andExpect(jsonPath("$.data.rows[3].lineNo").value(5))
                .andExpect(jsonPath("$.data.rows[3].status").value("FAILED"))
                .andExpect(jsonPath("$.data.rows[3].message").value(
                        org.hamcrest.Matchers.containsString("列数不符")))
                .andExpect(jsonPath("$.data.rows[4].lineNo").value(6))
                .andExpect(jsonPath("$.data.rows[4].status").value("FAILED"))
                .andExpect(jsonPath("$.data.rows[4].message").value(
                        org.hamcrest.Matchers.containsString("orderNo 不能为空")))
                .andExpect(jsonPath("$.data.rows[5].lineNo").value(7))
                .andExpect(jsonPath("$.data.rows[5].status").value("SKIPPED"))
                .andReturn().getResponse().getContentAsString();

        // 失败行 rawLine 非空，便于定位
        String rawLine = JsonPath.read(response, "$.data.rows[1].rawLine");
        assertTrue(rawLine.contains("WO-IMP-NM-" + suffix), "rawLine 应含行内容");
    }

    @Test
    void import_repeatedImport_idempotent() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String machineCode = "M-IDEM-" + suffix;
        createMachine(machineCode);
        String orderNo = "WO-IDEM-" + suffix;
        String csv = HEADER + "\n" + orderNo + "," + machineCode + ",播种,地块A,备注A\n";

        // 第一次导入：建档成功
        mockMvc.perform(multipart("/api/work-orders/import")
                        .file(csvFile(csv))
                        .param("requestNo", "REQ-IDEM-A-" + suffix))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.createdCount").value(1));

        // 同一请求号重放：整批拒绝
        mockMvc.perform(multipart("/api/work-orders/import")
                        .file(csvFile(csv))
                        .param("requestNo", "REQ-IDEM-A-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已处理")));

        // 换新请求号重复导入同一文件：全部 SKIPPED，不产生重复单
        mockMvc.perform(multipart("/api/work-orders/import")
                        .file(csvFile(csv))
                        .param("requestNo", "REQ-IDEM-B-" + suffix))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.skippedCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        // 库里仍是原来那条，内容未被改动
        mockMvc.perform(get("/api/work-orders").param("orderNo", orderNo))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.order.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.order.plotName").value("地块A"))
                .andExpect(jsonPath("$.data.order.version").value(0));
    }

    @Test
    void import_badHeader_rejected() throws Exception {
        String csv = "orderNo,machineCode,operationType,plotName\nWO-X,M-X,播种,地块\n";
        mockMvc.perform(multipart("/api/work-orders/import")
                        .file(csvFile(csv))
                        .param("requestNo", "REQ-HDR-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("表头与模板不一致")));
    }

    @Test
    void import_noDataRows_rejected() throws Exception {
        mockMvc.perform(multipart("/api/work-orders/import")
                        .file(csvFile(HEADER + "\n"))
                        .param("requestNo", "REQ-EMPTY-" + System.nanoTime()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("没有数据行")));
    }

    @Test
    void import_missingRequestNo_rejected() throws Exception {
        String csv = HEADER + "\nWO-NR,M-NR,播种,,\n";
        mockMvc.perform(multipart("/api/work-orders/import").file(csvFile(csv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("请求号不能为空")));
    }

    private long createMachine(String machineCode) throws Exception {
        String body = "{\"machineCode\":\"" + machineCode + "\",\"machineName\":\"交换测试机\","
                + "\"requestNo\":\"REQ-M-" + System.nanoTime() + "\"}";
        String response = mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.id")).longValue();
    }

    private void createOrder(String orderNo, long machineId, String operationType,
                             String plotName, String remark) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"orderNo\":\"").append(orderNo).append("\",\"machineId\":").append(machineId)
                .append(",\"operationType\":\"").append(operationType).append("\"");
        if (plotName != null) {
            body.append(",\"plotName\":\"").append(plotName).append("\"");
        }
        if (remark != null) {
            body.append(",\"remark\":\"").append(remark.replace("\"", "\\\"")).append("\"");
        }
        body.append(",\"requestNo\":\"REQ-W-").append(System.nanoTime()).append("\"}");
        mockMvc.perform(post("/api/work-orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(jsonPath("$.success").value(true));
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "orders.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
