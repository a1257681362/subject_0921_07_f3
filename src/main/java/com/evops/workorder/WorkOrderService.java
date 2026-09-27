package com.evops.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.machine.MachineStatus;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.request.RequestRecordService;
import com.evops.workorder.dto.ManualAreaRequest;
import com.evops.workorder.dto.ReverseRequest;
import com.evops.workorder.dto.StatusTransitRequest;
import com.evops.workorder.dto.TrackAreaRequest;
import com.evops.workorder.dto.WorkOrderCreateRequest;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.entity.WorkOrderStatusLog;
import com.evops.workorder.mapper.WorkOrderMapper;
import com.evops.workorder.mapper.WorkOrderStatusLogMapper;
import com.evops.workorder.vo.WorkOrderDetail;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkOrderService {

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderStatusLogMapper statusLogMapper;
    private final MachineMapper machineMapper;
    private final RequestRecordService requestRecordService;

    public WorkOrderService(WorkOrderMapper workOrderMapper,
                            WorkOrderStatusLogMapper statusLogMapper,
                            MachineMapper machineMapper,
                            RequestRecordService requestRecordService) {
        this.workOrderMapper = workOrderMapper;
        this.statusLogMapper = statusLogMapper;
        this.machineMapper = machineMapper;
        this.requestRecordService = requestRecordService;
    }

    /**
     * 作业单建档：请求号登记 → 农机校验 → 落单 → 写初始状态日志，全部同一事务。
     * 同一作业单号并发两笔由 uk_work_order_no 兜底，只可能成功一条。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder create(WorkOrderCreateRequest req) {
        return doCreate(req, BizType.WORK_ORDER_CREATE);
    }

    /**
     * CSV 导入建档：与接口建档同一事务口径、同一校验规则，
     * 业务类型记 WORK_ORDER_IMPORT，便于台账区分来源。行级请求号由导入方生成。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder createImported(WorkOrderCreateRequest req) {
        return doCreate(req, BizType.WORK_ORDER_IMPORT);
    }

    private WorkOrder doCreate(WorkOrderCreateRequest req, String bizType) {
        requestRecordService.register(req.getRequestNo(), bizType, req.getOrderNo());

        Machine machine = machineMapper.selectById(req.getMachineId());
        if (machine == null) {
            throw new BusinessException("农机不存在: " + req.getMachineId());
        }
        if (!MachineStatus.ACTIVE.equals(machine.getStatus())) {
            throw new BusinessException("农机已停用，不能新建作业单");
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(req.getOrderNo());
        order.setMachineId(req.getMachineId());
        order.setOperationType(req.getOperationType());
        order.setPlotName(req.getPlotName());
        order.setRemark(req.getRemark());
        order.setStatus(WorkOrderStatus.CREATED);
        order.setReversalFlag("0");
        try {
            workOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("作业单号 " + req.getOrderNo() + " 已存在");
        }
        writeLog(order, null, WorkOrderStatus.CREATED, req.getRequestNo(), "作业单建档");
        return order;
    }

    /** 按作业单号检索：作业单本体 + 状态流转轨迹。 */
    public WorkOrderDetail findByOrderNo(String orderNo) {
        WorkOrder order = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException("作业单不存在: " + orderNo);
        }
        return loadDetail(order);
    }

    /** 状态流转。完工要求轨迹核算面积已就位；人工上报面积不作数。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder transit(Long id, StatusTransitRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.WORK_ORDER_TRANSIT, String.valueOf(id));

        WorkOrder order = requireOrder(id);
        String target = req.getTargetStatus();
        if (!WorkOrderStatus.canTransit(order.getStatus(), target)) {
            throw new BusinessException("作业单状态不允许从 " + order.getStatus() + " 流转到 " + target);
        }
        if (WorkOrderStatus.COMPLETED.equals(target) && order.getTrackArea() == null) {
            throw new BusinessException("缺少轨迹核算面积，不能完工；人工上报面积仅供参考");
        }
        String from = order.getStatus();
        order.setStatus(target);
        applyVersionUpdate(order, "作业单状态已变化，请刷新后重试");
        writeLog(order, from, target, req.getRequestNo(), null);
        return order;
    }

    /** 登记轨迹核算面积（权威面积），同事务更新版本。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder reportTrackArea(Long id, TrackAreaRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.WORK_ORDER_TRACK_AREA, String.valueOf(id));

        WorkOrder order = requireEditable(id);
        order.setTrackArea(req.getTrackArea());
        order.setTrackTime(LocalDateTime.now());
        applyVersionUpdate(order, "作业单状态已变化，请刷新后重试");
        return order;
    }

    /** 人工上报面积，仅作参考留痕。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder reportManualArea(Long id, ManualAreaRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.WORK_ORDER_MANUAL_AREA, String.valueOf(id));

        WorkOrder order = requireEditable(id);
        order.setManualArea(req.getManualArea());
        order.setManualReporter(req.getReporter());
        order.setManualReportTime(LocalDateTime.now());
        applyVersionUpdate(order, "作业单状态已变化，请刷新后重试");
        return order;
    }

    /**
     * 红冲：原单置 REVERSED，另开反向单据（面积取反、reversal_flag=1 指向原单）。
     * 原单不物理删除；台账登记、原单更新、反向单建档、两份流转日志同一事务，失败整体回退。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder reverse(Long id, ReverseRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.WORK_ORDER_REVERSE, String.valueOf(id));

        WorkOrder origin = requireOrder(id);
        if ("1".equals(origin.getReversalFlag())) {
            throw new BusinessException("反向单据不能再次红冲");
        }
        if (WorkOrderStatus.REVERSED.equals(origin.getStatus())
                || WorkOrderStatus.CANCELLED.equals(origin.getStatus())) {
            throw new BusinessException("作业单状态为 " + origin.getStatus() + "，不能红冲");
        }
        String originFromStatus = origin.getStatus();

        origin.setStatus(WorkOrderStatus.REVERSED);
        applyVersionUpdate(origin, "作业单状态已变化，请刷新后重试");
        writeLog(origin, originFromStatus, WorkOrderStatus.REVERSED, req.getRequestNo(), "红冲原单");

        WorkOrder reverse = new WorkOrder();
        reverse.setOrderNo(origin.getOrderNo() + "-R");
        reverse.setMachineId(origin.getMachineId());
        reverse.setOperationType(origin.getOperationType());
        reverse.setPlotName(origin.getPlotName());
        reverse.setTrackArea(origin.getTrackArea() == null ? null : origin.getTrackArea().negate());
        reverse.setTrackTime(origin.getTrackTime());
        reverse.setManualArea(origin.getManualArea() == null ? null : origin.getManualArea().negate());
        reverse.setStatus(WorkOrderStatus.REVERSED);
        reverse.setReversalFlag("1");
        reverse.setOriginOrderId(origin.getId());
        reverse.setRemark(req.getRemark());
        workOrderMapper.insert(reverse);
        writeLog(reverse, null, WorkOrderStatus.REVERSED, req.getRequestNo(),
                "红冲反向单据，原单 " + origin.getOrderNo());
        return reverse;
    }

    private WorkOrder requireOrder(Long id) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("作业单不存在: " + id);
        }
        return order;
    }

    private WorkOrder requireEditable(Long id) {
        WorkOrder order = requireOrder(id);
        if (WorkOrderStatus.COMPLETED.equals(order.getStatus())
                || WorkOrderStatus.CANCELLED.equals(order.getStatus())
                || WorkOrderStatus.REVERSED.equals(order.getStatus())) {
            throw new BusinessException("作业单状态为 " + order.getStatus() + "，不能再登记面积");
        }
        return order;
    }

    private void applyVersionUpdate(WorkOrder order, String failMessage) {
        if (workOrderMapper.updateById(order) == 0) {
            throw new BusinessException(failMessage);
        }
    }

    private void writeLog(WorkOrder order, String fromStatus, String toStatus,
                          String requestNo, String remark) {
        WorkOrderStatusLog log = new WorkOrderStatusLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setRequestNo(requestNo);
        log.setRemark(remark);
        statusLogMapper.insert(log);
    }

    private WorkOrderDetail loadDetail(WorkOrder order) {
        List<WorkOrderStatusLog> logs = statusLogMapper.selectList(
                new LambdaQueryWrapper<WorkOrderStatusLog>()
                        .eq(WorkOrderStatusLog::getOrderId, order.getId())
                        .orderByAsc(WorkOrderStatusLog::getId));
        WorkOrderDetail detail = new WorkOrderDetail();
        detail.setOrder(order);
        detail.setStatusLogs(logs);
        return detail;
    }
}
