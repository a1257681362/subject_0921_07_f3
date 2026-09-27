package com.evops.api;

import com.evops.workorder.exchange.CsvCodec;
import com.evops.workorder.exchange.WorkOrderCsv;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 文件交换接口层：模板下载、导出下载、multipart 批量导入。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderExchangeApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void importTemplate_downloadable() throws Exception {
        byte[] content = mockMvc.perform(get("/api/work-orders/import-template"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("template")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/csv")))
                .andReturn().getResponse().getContentAsByteArray();

        List<List<String>> records = CsvCodec.parse(new String(content, StandardCharsets.UTF_8));
        assertEquals(1, records.size(), "模板仅表头一行");
        assertEquals(WorkOrderCsv.EXCHANGE_HEADERS, records.get(0));
        assertTrue(new String(content, StandardCharsets.UTF_8).startsWith(CsvCodec.BOM), "带 BOM，Excel 打开不乱码");
    }

    @Test
    void exportAndImport_closedLoop() throws Exception {
        String machineCode = "M-XAPI-" + SEQ.incrementAndGet();
        mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineCode\":\"" + machineCode + "\",\"machineName\":\"交换接口机\","
                                + "\"requestNo\":\"REQ-XAPI-M-" + SEQ.incrementAndGet() + "\"}"))
                .andExpect(jsonPath("$.success").value(true));

        String orderNo = "WO-XAPI-" + SEQ.incrementAndGet();
        String csvText = CsvCodec.BOM
                + CsvCodec.encodeRecord(WorkOrderCsv.EXCHANGE_HEADERS) + CsvCodec.CRLF
                + CsvCodec.encodeRecord(java.util.Arrays.asList(orderNo, machineCode, "播种", "接口地块", "含,逗号"))
                + CsvCodec.CRLF;
        MockMultipartFile file = new MockMultipartFile("file", "orders.csv", "text/csv",
                csvText.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/work-orders/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalRows").value(1))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].lineNo").value(2))
                .andExpect(jsonPath("$.data.rows[0].result").value("SUCCESS"));

        // 重复导入同一文件 → 幂等跳过
        mockMvc.perform(multipart("/api/work-orders/import").file(file))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.skippedCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].result").value("SKIPPED"));

        // 导出包含该单，且可交换列一致
        byte[] exported = mockMvc.perform(get("/api/work-orders/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("export")))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> records = CsvCodec.parse(new String(exported, StandardCharsets.UTF_8));
        List<String> mine = null;
        for (List<String> record : records) {
            if (!record.isEmpty() && orderNo.equals(record.get(0))) {
                mine = record;
            }
        }
        assertTrue(mine != null, "导出必须包含导入的单");
        assertEquals(machineCode, mine.get(1));
        assertEquals("含,逗号", mine.get(4), "含逗号备注往返一致");
    }

    @Test
    void importEmptyFile_unifiedFail() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);
        mockMvc.perform(multipart("/api/work-orders/import").file(empty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("为空")));
    }
}
