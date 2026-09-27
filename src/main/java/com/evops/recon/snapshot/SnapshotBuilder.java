package com.evops.recon.snapshot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.recon.entity.SatelliteBill;
import com.evops.recon.mapper.SatelliteBillMapper;
import com.evops.workorder.entity.WorkOrder;
import com.evops.workorder.mapper.WorkOrderMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 快照构建：勾稽日当天 24:00 前已产生的全部账面记录（累计口径，不是当日增量）。
 * 账不物理删除——已红冲原单与反向单都在快照里，由引擎决定如何配对。
 */
@Component
public class SnapshotBuilder {

    private final WorkOrderMapper workOrderMapper;
    private final SatelliteBillMapper satelliteBillMapper;

    public SnapshotBuilder(WorkOrderMapper workOrderMapper,
                           SatelliteBillMapper satelliteBillMapper) {
        this.workOrderMapper = workOrderMapper;
        this.satelliteBillMapper = satelliteBillMapper;
    }

    public ReconSnapshot build(LocalDate reconcileDate) {
        List<WorkOrder> orders = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .le(WorkOrder::getCreateTime, reconcileDate.plusDays(1).atStartOfDay())
                        .orderByAsc(WorkOrder::getId));
        List<SatelliteBill> bills = satelliteBillMapper.selectList(
                new LambdaQueryWrapper<SatelliteBill>()
                        .le(SatelliteBill::getCreateTime, reconcileDate.plusDays(1).atStartOfDay())
                        .orderByAsc(SatelliteBill::getId));

        ReconSnapshot snapshot = new ReconSnapshot();
        snapshot.setReconcileDate(reconcileDate);
        snapshot.setLocalEntries(orders.stream().map(this::toLocalEntry).collect(Collectors.toList()));
        snapshot.setRemoteEntries(bills.stream().map(this::toRemoteEntry).collect(Collectors.toList()));
        return snapshot;
    }

    private ReconEntry toLocalEntry(WorkOrder o) {
        ReconEntry e = new ReconEntry();
        e.setId(o.getId());
        e.setOrderNo(o.getOrderNo());
        e.setArea(o.getTrackArea());
        e.setBizTime(o.getTrackTime());
        e.setStatus(o.getStatus());
        e.setReversalFlag(o.getReversalFlag());
        return e;
    }

    private ReconEntry toRemoteEntry(SatelliteBill b) {
        ReconEntry e = new ReconEntry();
        e.setId(b.getId());
        e.setOrderNo(b.getOrderNo());
        e.setRefNo(b.getBillNo());
        e.setArea(b.getTrackArea());
        e.setBizTime(b.getBillTime());
        e.setStatus(b.getStatus());
        e.setReversalFlag(b.getReversalFlag());
        return e;
    }
}
