package com.evops.workorder.exchange;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.machine.MachineStatus;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.workorder.WorkOrderService;
import com.evops.workorder.WorkOrderStatus;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.exchange.vo.ImportResult;
import com.evops.workorder.exchange.vo.ImportRowResult;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 作业单 CSV 文件交换：模板下载、导出、批量导入。
 * <ul>
 *   <li>交换列为 {@link WorkOrderCsv#EXCHANGE_HEADERS} 5 列；导出追加参考列，导入忽略。</li>
 *   <li>导入逐行独立事务：一行失败不影响其他行，失败行带行号与原因返回。</li>
 *   <li>幂等口径：作业单号已存在的行跳过（SKIPPED），重复导入同一文件库内数据不变。</li>
 *   <li>行级请求号 = 批次号-行号，落请求号台账，与建档同事务。</li>
 * </ul>
 */
@Service
public class WorkOrderExchangeService {

    private static final int MAX_ORDER_NO_LEN = 40;
    private static final int MAX_OPERATION_TYPE_LEN = 40;
    private static final int MAX_PLOT_NAME_LEN = 80;
    private static final int MAX_REMARK_LEN = 255;

    private final WorkOrderMapper workOrderMapper;
    private final MachineMapper machineMapper;
    private final WorkOrderService workOrderService;

    public WorkOrderExchangeService(WorkOrderMapper workOrderMapper,
                                    MachineMapper machineMapper,
                                    WorkOrderService workOrderService) {
        this.workOrderMapper = workOrderMapper;
        this.machineMapper = machineMapper;
        this.workOrderService = workOrderService;
    }

    /** 导入模板：仅表头一行（UTF-8 带 BOM，Excel 直接打开不乱码）。 */
    public byte[] templateCsv() {
        String text = CsvCodec.BOM + CsvCodec.encodeRecord(WorkOrderCsv.EXCHANGE_HEADERS) + CsvCodec.CRLF;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 导出作业单（仅正常单；红冲反向单由系统红冲产生，不参与文件交换）。
     * status 为空导出全部，否则按状态过滤。UTF-8 带 BOM、CRLF 行尾。
     */
    public byte[] exportCsv(String status) {
        if (StringUtils.hasText(status)) {
            requireValidStatus(status);
        }
        List<WorkOrder> orders = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getReversalFlag, "0")
                        .eq(StringUtils.hasText(status), WorkOrder::getStatus, status)
                        .orderByAsc(WorkOrder::getId));
        Map<Long, Machine> machineById = loadMachineById();

        StringBuilder sb = new StringBuilder(CsvCodec.BOM);
        sb.append(CsvCodec.encodeRecord(WorkOrderCsv.EXPORT_HEADERS)).append(CsvCodec.CRLF);
        for (WorkOrder order : orders) {
            Machine machine = machineById.get(order.getMachineId());
            List<String> fields = new ArrayList<>();
            fields.add(order.getOrderNo());
            fields.add(machine == null ? null : machine.getMachineCode());
            fields.add(order.getOperationType());
            fields.add(order.getPlotName());
            fields.add(order.getRemark());
            fields.add(order.getStatus());
            fields.add(toPlain(order.getTrackArea()));
            fields.add(toPlain(order.getManualArea()));
            sb.append(CsvCodec.encodeRecord(fields)).append(CsvCodec.CRLF);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 批量导入。表头按名称匹配可交换列，未知列忽略；
     * 空行不计数；每行独立建档事务，行结果逐条返回。
     */
    public ImportResult importCsv(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException("导入文件为空");
        }
        List<List<String>> records = CsvCodec.parse(new String(content, StandardCharsets.UTF_8));
        if (records.isEmpty()) {
            throw new BusinessException("导入文件为空，未解析到表头");
        }
        Map<String, Integer> columnIndex = resolveColumns(records.get(0));

        ImportResult result = new ImportResult();
        result.setBatchNo("IMP-" + UUID.randomUUID().toString().replace("-", ""));
        Map<String, Machine> machineByCode = loadMachineByCode();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (isBlankRecord(record)) {
                continue;
            }
            result.addRow(importRow(i + 1, record, columnIndex, machineByCode, result.getBatchNo()));
        }
        return result;
    }

    /** 处理一条数据记录：校验 → 已存在跳过 → 建档。行号 = 记录序号（表头为 1）。 */
    private ImportRowResult importRow(int lineNo, List<String> record, Map<String, Integer> columnIndex,
                                      Map<String, Machine> machineByCode, String batchNo) {
        String orderNo = field(record, columnIndex, WorkOrderCsv.COL_ORDER_NO);
        String machineCode = field(record, columnIndex, WorkOrderCsv.COL_MACHINE_CODE);
        String operationType = field(record, columnIndex, WorkOrderCsv.COL_OPERATION_TYPE);
        String plotName = field(record, columnIndex, WorkOrderCsv.COL_PLOT_NAME);
        String remark = field(record, columnIndex, WorkOrderCsv.COL_REMARK);

        String error = validate(lineNo, orderNo, machineCode, operationType, plotName, remark, machineByCode);
        if (error != null) {
            return new ImportRowResult(lineNo, orderNo, ImportRowResult.FAILED, error);
        }
        if (orderExists(orderNo)) {
            return new ImportRowResult(lineNo, orderNo, ImportRowResult.SKIPPED, "作业单号已存在，跳过（幂等）");
        }

        WorkOrderCreateRequest req = new WorkOrderCreateRequest();
        req.setOrderNo(orderNo);
        req.setMachineId(machineByCode.get(machineCode).getId());
        req.setOperationType(operationType);
        req.setPlotName(StringUtils.hasText(plotName) ? plotName : null);
        req.setRemark(StringUtils.hasText(remark) ? remark : null);
        req.setRequestNo(batchNo + "-" + lineNo);
        try {
            workOrderService.createImported(req);
            return new ImportRowResult(lineNo, orderNo, ImportRowResult.SUCCESS, null);
        } catch (BusinessException e) {
            // 并发撞单号等落库失败：本行标记失败，重跑导入即转为 SKIPPED，最终一致。
            return new ImportRowResult(lineNo, orderNo, ImportRowResult.FAILED, e.getMessage());
        }
    }

    private String validate(int lineNo, String orderNo, String machineCode, String operationType,
                            String plotName, String remark, Map<String, Machine> machineByCode) {
        if (!StringUtils.hasText(orderNo)) {
            return "作业单号不能为空";
        }
        if (orderNo.length() > MAX_ORDER_NO_LEN) {
            return "作业单号超长（≤" + MAX_ORDER_NO_LEN + "字符）: " + orderNo;
        }
        if (orderNo.endsWith(WorkOrderCsv.REVERSAL_SUFFIX)) {
            return "作业单号不能以 " + WorkOrderCsv.REVERSAL_SUFFIX + " 结尾（红冲反向单保留后缀）: " + orderNo;
        }
        if (!StringUtils.hasText(machineCode)) {
            return "农机编码不能为空";
        }
        Machine machine = machineByCode.get(machineCode);
        if (machine == null) {
            return "农机编码不存在: " + machineCode;
        }
        if (!MachineStatus.ACTIVE.equals(machine.getStatus())) {
            return "农机已停用，不能新建作业单: " + machineCode;
        }
        if (!StringUtils.hasText(operationType)) {
            return "作业类型不能为空";
        }
        if (operationType.length() > MAX_OPERATION_TYPE_LEN) {
            return "作业类型超长（≤" + MAX_OPERATION_TYPE_LEN + "字符）: " + operationType;
        }
        if (plotName != null && plotName.length() > MAX_PLOT_NAME_LEN) {
            return "地块名称超长（≤" + MAX_PLOT_NAME_LEN + "字符）";
        }
        if (remark != null && remark.length() > MAX_REMARK_LEN) {
            return "备注超长（≤" + MAX_REMARK_LEN + "字符）";
        }
        return null;
    }

    /** 表头按名称映射列下标；必填列缺失则整批拒绝。 */
    private Map<String, Integer> resolveColumns(List<String> header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i) == null ? "" : header.get(i).trim();
            if (!name.isEmpty() && !index.containsKey(name)) {
                index.put(name, i);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String required : WorkOrderCsv.REQUIRED_HEADERS) {
            if (!index.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("CSV 表头缺少必需列: " + String.join("、", missing)
                    + "；请使用导入模板（/api/work-orders/import-template）");
        }
        return index;
    }

    private String field(List<String> record, Map<String, Integer> columnIndex, String column) {
        Integer idx = columnIndex.get(column);
        if (idx == null || idx >= record.size()) {
            return null;
        }
        String value = record.get(idx);
        return value == null ? null : value.trim();
    }

    private boolean isBlankRecord(List<String> record) {
        for (String field : record) {
            if (StringUtils.hasText(field)) {
                return false;
            }
        }
        return true;
    }

    private boolean orderExists(String orderNo) {
        return workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getOrderNo, orderNo)) > 0;
    }

    private Map<String, Machine> loadMachineByCode() {
        Map<String, Machine> map = new LinkedHashMap<>();
        for (Machine machine : machineMapper.selectList(null)) {
            map.put(machine.getMachineCode(), machine);
        }
        return map;
    }

    private Map<Long, Machine> loadMachineById() {
        Map<Long, Machine> map = new HashMap<>();
        for (Machine machine : machineMapper.selectList(null)) {
            map.put(machine.getId(), machine);
        }
        return map;
    }

    private String toPlain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private void requireValidStatus(String status) {
        if (!WorkOrderStatus.CREATED.equals(status) && !WorkOrderStatus.IN_PROGRESS.equals(status)
                && !WorkOrderStatus.COMPLETED.equals(status) && !WorkOrderStatus.CANCELLED.equals(status)
                && !WorkOrderStatus.REVERSED.equals(status)) {
            throw new BusinessException("状态值不合法: " + status
                    + "（可选 CREATED/IN_PROGRESS/COMPLETED/CANCELLED/REVERSED）");
        }
    }
}
