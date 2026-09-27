package com.evops.recon.engine;

import com.evops.recon.DiffType;
import com.evops.recon.ReconConstants;
import com.evops.recon.entity.ReconDetail;
import com.evops.recon.snapshot.ReconEntry;
import com.evops.recon.snapshot.ReconSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎公共骨架：按单号把双方账目一笔对一笔配对，差异明细带双方原始字段。
 * 新旧引擎只在两处不同：哪些账参与勾稽（{@link #effective}）、金额怎样算一致（{@link #amountMatched}）。
 */
public abstract class AbstractReconEngine implements ReconEngine {

    @Override
    public EngineResult match(ReconSnapshot snapshot, BigDecimal tolerance) {
        Map<String, ReconEntry> local = indexEffective(snapshot.getLocalEntries());
        Map<String, ReconEntry> remote = indexEffective(snapshot.getRemoteEntries());

        EngineResult result = new EngineResult();
        result.setLocalTotal(sum(local));
        result.setRemoteTotal(sum(remote));

        List<String> allKeys = new ArrayList<>();
        for (String key : local.keySet()) {
            if (!allKeys.contains(key)) {
                allKeys.add(key);
            }
        }
        for (String key : remote.keySet()) {
            if (!allKeys.contains(key)) {
                allKeys.add(key);
            }
        }

        for (String key : allKeys) {
            ReconEntry l = local.get(key);
            ReconEntry r = remote.get(key);
            if (l != null && r != null) {
                if (l.isReversal() != r.isReversal()) {
                    result.getDiffs().add(build(snapshot.getReconcileDate(), DiffType.REVERSAL_DIFF, key, l, r));
                } else if (!amountMatched(l.getArea(), r.getArea(), tolerance)) {
                    result.getDiffs().add(build(snapshot.getReconcileDate(), DiffType.AMOUNT_DIFF, key, l, r));
                } else {
                    result.setMatchedCount(result.getMatchedCount() + 1);
                    result.getMatchedKeys().add(key);
                }
            } else if (l != null) {
                result.getDiffs().add(build(snapshot.getReconcileDate(), DiffType.LOCAL_ONLY, key, l, null));
            } else {
                result.getDiffs().add(build(snapshot.getReconcileDate(), DiffType.REMOTE_ONLY, key, null, r));
            }
        }
        return result;
    }

    /** 该笔账是否参与勾稽。 */
    protected abstract boolean effective(ReconEntry entry);

    /** 双方面积是否视为一致。 */
    protected abstract boolean amountMatched(BigDecimal localArea, BigDecimal remoteArea, BigDecimal tolerance);

    private Map<String, ReconEntry> indexEffective(List<ReconEntry> entries) {
        Map<String, ReconEntry> map = new LinkedHashMap<>();
        for (ReconEntry e : entries) {
            if (effective(e)) {
                map.put(e.getOrderNo(), e);
            }
        }
        return map;
    }

    private BigDecimal sum(Map<String, ReconEntry> map) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReconEntry e : map.values()) {
            if (e.getArea() != null) {
                total = total.add(e.getArea());
            }
        }
        return total;
    }

    /** 差异明细：有符号差额 remote - local；单边账按在侧金额取号。 */
    private ReconDetail build(LocalDate date, String diffType, String orderNo, ReconEntry l, ReconEntry r) {
        ReconDetail d = new ReconDetail();
        d.setReconcileDate(date);
        d.setEngine(code());
        d.setDiffType(diffType);
        d.setOrderNo(orderNo);
        d.setStatus(ReconConstants.DIFF_OPEN);

        BigDecimal localArea = l == null ? null : l.getArea();
        BigDecimal remoteArea = r == null ? null : r.getArea();
        if (l != null) {
            d.setLocalId(l.getId());
            d.setLocalOrderNo(l.getOrderNo());
            d.setLocalTrackArea(l.getArea());
            d.setLocalTrackTime(l.getBizTime());
            d.setLocalStatus(l.getStatus());
            d.setLocalReversalFlag(l.getReversalFlag());
        }
        if (r != null) {
            d.setRemoteId(r.getId());
            d.setRemoteBillNo(r.getRefNo());
            d.setRemoteOrderNo(r.getOrderNo());
            d.setRemoteTrackArea(r.getArea());
            d.setRemoteBillTime(r.getBizTime());
            d.setRemoteStatus(r.getStatus());
            d.setRemoteReversalFlag(r.getReversalFlag());
        }

        BigDecimal signed;
        if (localArea != null && remoteArea != null) {
            signed = remoteArea.subtract(localArea);
        } else if (remoteArea != null) {
            signed = remoteArea;
        } else {
            signed = localArea == null ? BigDecimal.ZERO : localArea.negate();
        }
        d.setDiffArea(signed);
        d.setAbsDiffArea(signed.abs());
        return d;
    }
}
