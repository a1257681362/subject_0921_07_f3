package com.evops.workorder.vo;

import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.entity.WorkOrderStatusLog;
import lombok.Data;

import java.util.List;

/** 按作业单号检索的结果：作业单本体 + 完整状态流转轨迹。 */
@Data
public class WorkOrderDetail {
    private WorkOrder order;
    private List<WorkOrderStatusLog> statusLogs;
}
