package com.evops.workorder.exchange;

import com.evops.common.BusinessException;
import com.evops.machine.MachineStatus;
import com.evops.machine.MachineService;
import com.evops.machine.dto.MachineCreateRequest;
import com.evops.machine.dto.MachineDisableRequest;
import com.evops.machine.entity.Machine;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.dto.ReverseRequest;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.exchange.vo.ImportResult;
import com.evops.workorder.exchange.vo.ImportRowResult;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 作业单 CSV 交换：批量导入、失败行定位、幂等、往返一致。 */
@SpringBootTest
@ActiveProfiles("test")
class WorkOrderExchangeTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private WorkOrderExchangeService exchangeService;
    @Autowired
    private MachineService machineService;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Test
    void importBatch_partialSuccess_failureRowsLocatable() {
        Machine active = createMachine("M-IMP-");
        Machine disabled = createMachine("M-IMP-DIS-");
        MachineDisableRequest disableReq = new MachineDisableRequest();
        disableReq.setRequestNo(uniq("REQ-XC-DIS-"));
        machineService.disable(disabled.getId(), disableReq);

        String okOrderNo = uniq("WO-IMP-OK-");
        String disabledOrderNo = uniq("WO-IMP-DIS-");
        List<List<String>> rows = new ArrayList<>();
        rows.add(WorkOrderCsv.EXCHANGE_HEADERS);                                            // 行 1 表头
        rows.add(Arrays.asList(okOrderNo, active.getMachineCode(), "播种", "一号地", "正常行")); // 行 2 成功
        rows.add(Arrays.asList("", active.getMachineCode(), "播种", "", ""));               // 行 3 缺单号
        rows.add(Arrays.asList(uniq("WO-IMP-NO-"), "M-NOT-EXIST", "播种", "", ""));         // 行 4 农机不存在
        rows.add(Arrays.asList(uniq("WO-IMP-R-") + "-R", active.getMachineCode(), "播种", "", "")); // 行 5 占用 -R 后缀
        rows.add(Arrays.asList(disabledOrderNo, disabled.getMachineCode(), "播种", "", "")); // 行 6 农机停用
        rows.add(Arrays.asList("", "", "", "", ""));                                        // 空行不计数
        ImportResult result = exchangeService.importCsv(csvBytes(rows));

        assertEquals(5, result.getTotalRows(), "空行不计入总数");
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getSkippedCount());
        assertEquals(4, result.getFailedCount());
        assertTrue(result.getBatchNo().startsWith("IMP-"));

        List<ImportRowResult> failures = result.getRows().stream()
                .filter(r -> ImportRowResult.FAILED.equals(r.getResult()))
                .collect(Collectors.toList());
        assertEquals(Arrays.asList(3, 4, 5, 6),
                failures.stream().map(ImportRowResult::getLineNo).collect(Collectors.toList()),
                "失败行号必须可定位回原文件");
        assertTrue(failures.get(0).getMessage().contains("作业单号不能为空"));
        assertTrue(failures.get(1).getMessage().contains("农机编码不存在"));
        assertTrue(failures.get(2).getMessage().contains("-R"));
        assertTrue(failures.get(3).getMessage().contains("停用"));

        // 成功行落库为 CREATED 正常单
        WorkOrder saved = workOrderService.findByOrderNo(okOrderNo).getOrder();
        assertEquals(WorkOrderStatus.CREATED, saved.getStatus());
        assertEquals("一号地", saved.getPlotName());
    }

    @Test
    void importRepeated_idempotent_allSkipped() {
        Machine machine = createMachine("M-IDEM-");
        List<List<String>> rows = new ArrayList<>();
        rows.add(WorkOrderCsv.EXCHANGE_HEADERS);
        String orderNo1 = uniq("WO-IDEM-");
        String orderNo2 = uniq("WO-IDEM-");
        rows.add(Arrays.asList(orderNo1, machine.getMachineCode(), "收割", "二块地", "首遍"));
        rows.add(Arrays.asList(orderNo2, machine.getMachineCode(), "收割", "", ""));

        ImportResult first = exchangeService.importCsv(csvBytes(rows));
        assertEquals(2, first.getSuccessCount());
        assertEquals(0, first.getFailedCount());

        ImportResult second = exchangeService.importCsv(csvBytes(rows));
        assertEquals(0, second.getSuccessCount(), "重复导入不得重复建档");
        assertEquals(2, second.getSkippedCount());
        assertEquals(0, second.getFailedCount());
        assertEquals(Arrays.asList(2, 3),
                second.getRows().stream().map(ImportRowResult::getLineNo).collect(Collectors.toList()));

        // 第三遍结果与第二遍一致：幂等口径稳定
        ImportResult third = exchangeService.importCsv(csvBytes(rows));
        assertEquals(2, third.getSkippedCount());
    }

    @Test
    void importDuplicateOrderNoInFile_firstSuccessSecondSkipped() {
        Machine machine = createMachine("M-DUPROW-");
        String orderNo = uniq("WO-DUPROW-");
        List<List<String>> rows = new ArrayList<>();
        rows.add(WorkOrderCsv.EXCHANGE_HEADERS);
        rows.add(Arrays.asList(orderNo, machine.getMachineCode(), "播种", "", ""));
        rows.add(Arrays.asList(orderNo, machine.getMachineCode(), "播种", "", ""));
        ImportResult result = exchangeService.importCsv(csvBytes(rows));

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(ImportRowResult.SUCCESS, result.getRows().get(0).getResult());
        assertEquals(ImportRowResult.SKIPPED, result.getRows().get(1).getResult());
    }

    @Test
    void importRowWithEmbeddedNewline_lineNoCountsRecords() {
        Machine machine = createMachine("M-NL-");
        List<List<String>> rows = new ArrayList<>();
        rows.add(WorkOrderCsv.EXCHANGE_HEADERS);
        // 备注含换行：物理上占两行，但仍是一条记录（行 2）
        rows.add(Arrays.asList(uniq("WO-NL-"), machine.getMachineCode(), "播种", "", "第一行\n第二行"));
        rows.add(Arrays.asList("", machine.getMachineCode(), "播种", "", "")); // 记录 3：缺单号
        ImportResult result = exchangeService.importCsv(csvBytes(rows));

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(3, result.getRows().get(1).getLineNo(), "行号按记录序号计，与字段内换行无关");
    }

    @Test
    void roundTrip_importExportImport_contentStable() {
        Machine machine = createMachine("M-RT-");
        String prefix = uniq("WO-RT-") + "-";
        List<List<String>> rows = new ArrayList<>();
        rows.add(WorkOrderCsv.EXCHANGE_HEADERS);
        rows.add(Arrays.asList(prefix + "1", machine.getMachineCode(), "播种", "东,地块", "含,逗号"));
        rows.add(Arrays.asList(prefix + "2", machine.getMachineCode(), "收割", "西\"地块", "含\"引号\""));
        rows.add(Arrays.asList(prefix + "3", machine.getMachineCode(), "植保", "南地块", "含\n换行"));
        rows.add(Arrays.asList(prefix + "4", machine.getMachineCode(), "深松", "", ""));
        ImportResult imported = exchangeService.importCsv(csvBytes(rows));
        assertEquals(4, imported.getSuccessCount());

        // 导出 → 可交换列与导入源逐字一致（含逗号/引号/换行的字段）
        byte[] exported1 = exchangeService.exportCsv(null);
        List<List<String>> parsed = CsvCodec.parse(new String(exported1, StandardCharsets.UTF_8));
        List<List<String>> mine = parsed.stream()
                .filter(r -> !r.isEmpty() && r.get(0).startsWith(prefix))
                .collect(Collectors.toList());
        assertEquals(4, mine.size());
        for (int i = 0; i < 4; i++) {
            List<String> expect = rows.get(i + 1);
            List<String> actual = mine.get(i);
            for (int c = 0; c < WorkOrderCsv.EXCHANGE_HEADERS.size(); c++) {
                String expectedValue = expect.get(c).trim();
                assertEquals(expectedValue, actual.get(c),
                        "可交换列往返必须一致: " + WorkOrderCsv.EXCHANGE_HEADERS.get(c));
            }
            assertEquals(WorkOrderStatus.CREATED, actual.get(5), "参考列如实导出状态");
        }

        // 导出文件再导入 → 全部 SKIPPED（幂等），无任何失败
        ImportResult reimport = exchangeService.importCsv(exported1);
        assertEquals(0, reimport.getFailedCount());
        assertEquals(0, reimport.getSuccessCount());
        assertTrue(reimport.getSkippedCount() >= 4);

        // 再导出 → 与第一次导出逐字节一致（往返内容一致）
        byte[] exported2 = exchangeService.exportCsv(null);
        assertEquals(new String(exported1, StandardCharsets.UTF_8),
                new String(exported2, StandardCharsets.UTF_8));
    }

    @Test
    void export_excludesReversalOrders() {
        Machine machine = createMachine("M-REVEXP-");
        WorkOrderCreateRequest createReq = new WorkOrderCreateRequest();
        String orderNo = uniq("WO-REVEXP-");
        createReq.setOrderNo(orderNo);
        createReq.setMachineId(machine.getId());
        createReq.setOperationType("收割");
        createReq.setRequestNo(uniq("REQ-REVEXP-C-"));
        WorkOrder order = workOrderService.create(createReq);

        TrackAreaRequest track = new TrackAreaRequest();
        track.setTrackArea(new BigDecimal("6.60"));
        track.setRequestNo(uniq("REQ-REVEXP-T-"));
        workOrderService.reportTrackArea(order.getId(), track);
        StatusTransitRequest transit = new StatusTransitRequest();
        transit.setTargetStatus(WorkOrderStatus.IN_PROGRESS);
        transit.setRequestNo(uniq("REQ-REVEXP-P-"));
        workOrderService.transit(order.getId(), transit);
        ReverseRequest reverse = new ReverseRequest();
        reverse.setRequestNo(uniq("REQ-REVEXP-R-"));
        workOrderService.reverse(order.getId(), reverse);

        List<List<String>> parsed = CsvCodec.parse(
                new String(exchangeService.exportCsv(null), StandardCharsets.UTF_8));
        List<String> orderNos = parsed.stream()
                .filter(r -> !r.isEmpty()).map(r -> r.get(0)).collect(Collectors.toList());
        assertTrue(orderNos.contains(orderNo), "红冲原单（正常单）仍导出");
        assertFalse(orderNos.contains(orderNo + "-R"), "红冲反向单不参与文件交换");
    }

    @Test
    void exportStatusFilter_andInvalidStatusRejected() {
        Machine machine = createMachine("M-FILTER-");
        WorkOrderCreateRequest createReq = new WorkOrderCreateRequest();
        String orderNo = uniq("WO-FILTER-");
        createReq.setOrderNo(orderNo);
        createReq.setMachineId(machine.getId());
        createReq.setOperationType("播种");
        createReq.setRequestNo(uniq("REQ-FILTER-"));
        workOrderService.create(createReq);

        String created = new String(exchangeService.exportCsv("CREATED"), StandardCharsets.UTF_8);
        assertTrue(created.contains(orderNo));
        String completed = new String(exchangeService.exportCsv("COMPLETED"), StandardCharsets.UTF_8);
        assertFalse(completed.contains(orderNo));

        assertThrows(BusinessException.class, () -> exchangeService.exportCsv("NOT_A_STATUS"));
    }

    @Test
    void template_onlyExchangeHeaders() {
        List<List<String>> records = CsvCodec.parse(
                new String(exchangeService.templateCsv(), StandardCharsets.UTF_8));
        assertEquals(1, records.size(), "模板仅表头一行");
        assertEquals(WorkOrderCsv.EXCHANGE_HEADERS, records.get(0));
    }

    @Test
    void importMissingRequiredHeader_rejected() {
        byte[] csv = ("作业单号,作业类型\r\nWO-X-1,播种\r\n").getBytes(StandardCharsets.UTF_8);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> exchangeService.importCsv(csv));
        assertTrue(ex.getMessage().contains("农机编码"));
    }

    @Test
    void importEmptyFile_rejected() {
        assertThrows(BusinessException.class, () -> exchangeService.importCsv(new byte[0]));
    }

    private byte[] csvBytes(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder(CsvCodec.BOM);
        for (List<String> row : rows) {
            sb.append(CsvCodec.encodeRecord(row)).append(CsvCodec.CRLF);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Machine createMachine(String codePrefix) {
        MachineCreateRequest req = new MachineCreateRequest();
        req.setMachineCode(uniq(codePrefix));
        req.setMachineName("交换测试机");
        // 请求号前缀独占 REQ-XC-：测试类共享内存库，请求号台账全局唯一，不能与其他测试类撞前缀。
        req.setRequestNo(uniq("REQ-XC-MACHINE-"));
        return machineService.create(req);
    }

    private String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }
}
