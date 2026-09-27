package com.evops.workorder.exchange;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.machine.MachineStatus;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.request.RequestRecordService;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.exchange.dto.ImportResult;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 作业单文件交换：CSV 导出、模板下载、批量导入。
 *
 * 口径（与 docs/work-order-csv-exchange.md 一致）：
 * - 导出文件与导入模板同列同序，导出文件可直接再导入，往返内容一致。
 * - 导入幂等：批次凭 requestNo 登记台账防重放；行级按 orderNo 判重，
 *   已存在且内容一致跳过（SKIPPED），内容冲突报失败（FAILED），不产生重复单。
 * - 行级独立事务：复用 {@link WorkOrderService#create}（跨 bean 调用事务生效），
 *   单行失败不影响其他行，失败行按物理行号定位。
 */
@Service
public class WorkOrderExchangeService {

    /** t_request_record.request_no 宽 64，行级请求号 = 批次请求号 + "#L" + 行号，预留 12 字符。 */
    private static final int BATCH_REQUEST_NO_MAX_LENGTH = 52;
    /** 行结果中原始行表示的截断长度。 */
    private static final int RAW_LINE_MAX_LENGTH = 200;

    private final WorkOrderMapper workOrderMapper;
    private final MachineMapper machineMapper;
    private final WorkOrderService workOrderService;
    private final RequestRecordService requestRecordService;

    public WorkOrderExchangeService(WorkOrderMapper workOrderMapper,
                                    MachineMapper machineMapper,
                                    WorkOrderService workOrderService,
                                    RequestRecordService requestRecordService) {
        this.workOrderMapper = workOrderMapper;
        this.machineMapper = machineMapper;
        this.workOrderService = workOrderService;
        this.requestRecordService = requestRecordService;
    }

    /**
     * 导出作业单 CSV：UTF-8 带 BOM、CRLF 行尾、按作业单号升序。
     * 可选过滤：orderNo 精确、status 精确、machineCode 精确；无过滤则全量（上限保护）。
     */
    public byte[] exportCsv(String orderNo, String status, String machineCode) {
        LambdaQueryWrapper<WorkOrder> wrapper = buildQuery(orderNo, status, machineCode);
        Long count = workOrderMapper.selectCount(wrapper);
        if (count != null && count > WorkOrderCsvFormat.EXPORT_MAX_ROWS) {
            throw new BusinessException("导出数据超过单次上限 " + WorkOrderCsvFormat.EXPORT_MAX_ROWS
                    + " 行，请按 orderNo/status/machineCode 过滤后再导出");
        }
        // 排序只能在 count 之后追加：H2 不允许 COUNT 查询带 ORDER BY
        wrapper.orderByAsc(WorkOrder::getOrderNo);
        List<WorkOrder> orders = workOrderMapper.selectList(wrapper);
        Map<Long, String> machineCodeById = loadMachineCodes(orders);

        StringBuilder sb = new StringBuilder();
        sb.append(CsvCodec.BOM).append(WorkOrderCsvFormat.HEADER_LINE).append("\r\n");
        for (WorkOrder order : orders) {
            sb.append(CsvCodec.encodeRecord(Arrays.asList(
                    order.getOrderNo(),
                    machineCodeById.get(order.getMachineId()),
                    order.getOperationType(),
                    order.getPlotName(),
                    order.getRemark()))).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 导入模板：表头 + 两行示例（第二行演示逗号/引号转义），示例行导入前需删除。 */
    public byte[] templateCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append(CsvCodec.BOM).append(WorkOrderCsvFormat.HEADER_LINE).append("\r\n");
        sb.append(CsvCodec.encodeRecord(Arrays.asList(
                "EXAMPLE-001", "M-001", "播种", "东地块1号", "示例行，导入前请删除"))).append("\r\n");
        sb.append(CsvCodec.encodeRecord(Arrays.asList(
                "EXAMPLE-002", "M-002", "收割,秸秆还田", "西地块（示范）",
                "备注含逗号, 与\"引号\"，演示转义"))).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 批量导入。批次请求号先登记台账（重复提交整批拒绝）；逐行独立处理，
     * 成功行落库、失败行带行号与原因返回，部分失败不回滚成功行。
     */
    public ImportResult importCsv(byte[] content, String fileName, String requestNo) {
        if (!StringUtils.hasText(requestNo)) {
            throw new BusinessException("请求号不能为空");
        }
        if (requestNo.length() > BATCH_REQUEST_NO_MAX_LENGTH) {
            throw new BusinessException("导入请求号长度不能超过 " + BATCH_REQUEST_NO_MAX_LENGTH
                    + " 字符（行级请求号需追加行号后缀）");
        }
        if (content == null || content.length == 0) {
            throw new BusinessException("文件为空");
        }
        // 批次级防重放：同一 requestNo 重复提交整批拒绝。登记后即使文件解析失败，
        // 该批次也已留痕，修正文件后需换新请求号重试。
        requestRecordService.register(requestNo, BizType.WORK_ORDER_IMPORT,
                fileName == null ? "" : fileName);

        List<CsvCodec.CsvRecord> records;
        try {
            records = CsvCodec.parse(new String(content, StandardCharsets.UTF_8));
        } catch (CsvCodec.CsvFormatException e) {
            throw new BusinessException("CSV 解析失败：" + e.getMessage());
        }
        if (records.isEmpty()) {
            throw new BusinessException("文件为空");
        }

        CsvCodec.CsvRecord header = records.get(0);
        if (!WorkOrderCsvFormat.HEADERS.equals(header.getFields())) {
            throw new BusinessException("表头与模板不一致，期望：" + WorkOrderCsvFormat.HEADER_LINE
                    + "；实际：" + CsvCodec.encodeRecord(header.getFields())
                    + "。请通过模板下载接口获取标准模板");
        }

        List<CsvCodec.CsvRecord> dataRecords = records.stream()
                .skip(1)
                .filter(r -> !r.isBlank())
                .collect(Collectors.toList());
        if (dataRecords.isEmpty()) {
            throw new BusinessException("文件没有数据行");
        }
        if (dataRecords.size() > WorkOrderCsvFormat.IMPORT_MAX_ROWS) {
            throw new BusinessException("单次导入最多 " + WorkOrderCsvFormat.IMPORT_MAX_ROWS
                    + " 行数据，当前 " + dataRecords.size() + " 行，请拆分文件");
        }

        ImportResult result = new ImportResult();
        Map<String, Machine> machineCache = new HashMap<>();
        for (CsvCodec.CsvRecord record : dataRecords) {
            ImportResult.RowResult row = processRow(record, requestNo, machineCache);
            result.getRows().add(row);
            if (ImportResult.ROW_CREATED.equals(row.getStatus())) {
                result.setCreatedCount(result.getCreatedCount() + 1);
            } else if (ImportResult.ROW_SKIPPED.equals(row.getStatus())) {
                result.setSkippedCount(result.getSkippedCount() + 1);
            } else {
                result.setFailedCount(result.getFailedCount() + 1);
            }
        }
        result.setTotalRows(result.getRows().size());
        return result;
    }

    /** 处理一行：校验 → 农机核对 → 单号判重（一致跳过/冲突失败）→ 复用建档主链路。 */
    private ImportResult.RowResult processRow(CsvCodec.CsvRecord record, String batchRequestNo,
                                              Map<String, Machine> machineCache) {
        ImportResult.RowResult row = new ImportResult.RowResult();
        row.setLineNo(record.getStartLine());
        row.setRawLine(abbreviate(CsvCodec.encodeRecord(record.getFields())));

        List<String> fields = record.getFields();
        if (fields.size() != WorkOrderCsvFormat.HEADERS.size()) {
            return failRow(row, "列数不符：期望 " + WorkOrderCsvFormat.HEADERS.size()
                    + " 列，实际 " + fields.size() + " 列");
        }
        String orderNo = fields.get(0);
        String machineCode = fields.get(1);
        String operationType = fields.get(2);
        String plotName = fields.get(3);
        String remark = fields.get(4);
        row.setOrderNo(orderNo);

        String validationError = validateRow(orderNo, machineCode, operationType, plotName, remark);
        if (validationError != null) {
            return failRow(row, validationError);
        }

        Machine machine = findMachine(machineCode, machineCache);

        WorkOrder existing = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        if (existing != null) {
            // 判重优先于农机状态校验：已有单不受农机停用影响，保证重复导入幂等
            if (machine == null) {
                return failRow(row, "农机不存在: " + machineCode + "，无法核对已有单内容");
            }
            List<String> diffs = diffFields(existing, machine.getId(), operationType, plotName, remark);
            if (diffs.isEmpty()) {
                row.setStatus(ImportResult.ROW_SKIPPED);
                row.setMessage("已存在且内容一致，跳过");
            } else {
                failRow(row, "作业单号已存在且内容冲突（字段: " + String.join(", ", diffs)
                        + "），请用 GET /api/work-orders?orderNo= 核对现有单");
            }
            return row;
        }

        if (machine == null) {
            return failRow(row, "农机不存在: " + machineCode);
        }
        if (!MachineStatus.ACTIVE.equals(machine.getStatus())) {
            return failRow(row, "农机已停用，不能建档: " + machineCode);
        }

        WorkOrderCreateRequest req = new WorkOrderCreateRequest();
        req.setOrderNo(orderNo);
        req.setMachineId(machine.getId());
        req.setOperationType(operationType);
        req.setPlotName(emptyToNull(plotName));
        req.setRemark(emptyToNull(remark));
        req.setRequestNo(batchRequestNo + "#L" + record.getStartLine());
        try {
            workOrderService.create(req);
            row.setStatus(ImportResult.ROW_CREATED);
            row.setMessage("建档成功");
        } catch (BusinessException e) {
            failRow(row, e.getMessage());
        } catch (RuntimeException e) {
            failRow(row, "系统处理失败，请核对本行数据后重试");
        }
        return row;
    }

    private String validateRow(String orderNo, String machineCode, String operationType,
                               String plotName, String remark) {
        if (isBlank(orderNo)) {
            return "orderNo 不能为空";
        }
        if (isBlank(machineCode)) {
            return "machineCode 不能为空";
        }
        if (isBlank(operationType)) {
            return "operationType 不能为空";
        }
        if (orderNo.length() > WorkOrderCsvFormat.MAX_ORDER_NO) {
            return "orderNo 长度超过 " + WorkOrderCsvFormat.MAX_ORDER_NO + " 字符";
        }
        if (machineCode.length() > WorkOrderCsvFormat.MAX_MACHINE_CODE) {
            return "machineCode 长度超过 " + WorkOrderCsvFormat.MAX_MACHINE_CODE + " 字符";
        }
        if (operationType.length() > WorkOrderCsvFormat.MAX_OPERATION_TYPE) {
            return "operationType 长度超过 " + WorkOrderCsvFormat.MAX_OPERATION_TYPE + " 字符";
        }
        if (plotName != null && plotName.length() > WorkOrderCsvFormat.MAX_PLOT_NAME) {
            return "plotName 长度超过 " + WorkOrderCsvFormat.MAX_PLOT_NAME + " 字符";
        }
        if (remark != null && remark.length() > WorkOrderCsvFormat.MAX_REMARK) {
            return "remark 长度超过 " + WorkOrderCsvFormat.MAX_REMARK + " 字符";
        }
        return null;
    }

    /** 与已有单逐字段比对（null 与空串同视），返回冲突字段名列表，空列表表示内容一致。 */
    private List<String> diffFields(WorkOrder existing, Long machineId, String operationType,
                                    String plotName, String remark) {
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(existing.getMachineId(), machineId)) {
            diffs.add("machineCode");
        }
        if (!Objects.equals(existing.getOperationType(), operationType)) {
            diffs.add("operationType");
        }
        if (!norm(existing.getPlotName()).equals(norm(plotName))) {
            diffs.add("plotName");
        }
        if (!norm(existing.getRemark()).equals(norm(remark))) {
            diffs.add("remark");
        }
        return diffs;
    }

    private Machine findMachine(String machineCode, Map<String, Machine> machineCache) {
        if (machineCache.containsKey(machineCode)) {
            return machineCache.get(machineCode);
        }
        Machine machine = machineMapper.selectOne(
                new LambdaQueryWrapper<Machine>().eq(Machine::getMachineCode, machineCode));
        machineCache.put(machineCode, machine);
        return machine;
    }

    private LambdaQueryWrapper<WorkOrder> buildQuery(String orderNo, String status, String machineCode) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(orderNo)) {
            wrapper.eq(WorkOrder::getOrderNo, orderNo);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        if (StringUtils.hasText(machineCode)) {
            Machine machine = machineMapper.selectOne(
                    new LambdaQueryWrapper<Machine>().eq(Machine::getMachineCode, machineCode));
            if (machine == null) {
                // 农机不存在则结果必为空，用一个不可能的条件短路
                wrapper.eq(WorkOrder::getId, -1L);
            } else {
                wrapper.eq(WorkOrder::getMachineId, machine.getId());
            }
        }
        return wrapper;
    }

    private Map<Long, String> loadMachineCodes(List<WorkOrder> orders) {
        List<Long> machineIds = orders.stream()
                .map(WorkOrder::getMachineId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> machineCodeById = new HashMap<>();
        if (!machineIds.isEmpty()) {
            for (Machine machine : machineMapper.selectBatchIds(machineIds)) {
                machineCodeById.put(machine.getId(), machine.getMachineCode());
            }
        }
        return machineCodeById;
    }

    private ImportResult.RowResult failRow(ImportResult.RowResult row, String message) {
        row.setStatus(ImportResult.ROW_FAILED);
        row.setMessage(message);
        return row;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String norm(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s;
    }

    private static String abbreviate(String s) {
        if (s == null || s.length() <= RAW_LINE_MAX_LENGTH) {
            return s;
        }
        return s.substring(0, RAW_LINE_MAX_LENGTH) + "...";
    }
}
