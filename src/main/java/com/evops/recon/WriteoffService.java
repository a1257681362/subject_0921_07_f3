package com.evops.recon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.recon.dto.WriteoffApplyRequest;
import com.evops.recon.dto.WriteoffApproveRequest;
import com.evops.recon.dto.WriteoffRejectRequest;
import com.evops.recon.entity.ReconDetail;
import com.evops.recon.entity.ReconWriteoff;
import com.evops.recon.mapper.ReconDetailMapper;
import com.evops.recon.mapper.ReconWriteoffMapper;
import com.evops.request.RequestRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手工调整核销流程：申请 → 审批/驳回。
 * 审批与差异清零在同一事务：核销单置 APPROVED、差异明细置 WRITTEN_OFF、批次汇总重算一起提交；
 * 已核销差异在后续批次若仍存在会自动结转清零（见 ReconService）。
 */
@Service
public class WriteoffService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger NO_SEQ = new AtomicInteger();
    private static final Set<String> ADJUST_TYPES = new HashSet<>(Arrays.asList(
            AdjustType.LOCAL_OVER, AdjustType.LOCAL_SHORT,
            AdjustType.REMOTE_OVER, AdjustType.REMOTE_SHORT,
            AdjustType.CALIBER_DIFF));

    private final ReconWriteoffMapper writeoffMapper;
    private final ReconDetailMapper detailMapper;
    private final RequestRecordService requestRecordService;
    private final ReconService reconService;

    public WriteoffService(ReconWriteoffMapper writeoffMapper,
                           ReconDetailMapper detailMapper,
                           RequestRecordService requestRecordService,
                           ReconService reconService) {
        this.writeoffMapper = writeoffMapper;
        this.detailMapper = detailMapper;
        this.requestRecordService = requestRecordService;
        this.reconService = reconService;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReconWriteoff apply(WriteoffApplyRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.WRITEOFF_APPLY,
                String.valueOf(req.getDetailId()));

        ReconDetail detail = detailMapper.selectById(req.getDetailId());
        if (detail == null) {
            throw new BusinessException("差异明细不存在: " + req.getDetailId());
        }
        if (!ReconConstants.ENGINE_NEW.equals(detail.getEngine())) {
            throw new BusinessException("仅可对新引擎口径的差异发起核销");
        }
        if (!ReconConstants.DIFF_OPEN.equals(detail.getStatus())) {
            throw new BusinessException("该差异已核销，不能重复发起");
        }
        if (!ADJUST_TYPES.contains(req.getAdjustType())) {
            throw new BusinessException("未知调整类型: " + req.getAdjustType());
        }
        Long pending = writeoffMapper.selectCount(new LambdaQueryWrapper<ReconWriteoff>()
                .eq(ReconWriteoff::getDetailId, detail.getId())
                .eq(ReconWriteoff::getStatus, ReconConstants.WRITEOFF_APPLIED));
        if (pending > 0) {
            throw new BusinessException("该差异已有待审批的核销申请");
        }

        ReconWriteoff writeoff = new ReconWriteoff();
        writeoff.setWriteoffNo("WO-" + LocalDateTime.now().format(NO_FMT)
                + "-" + detail.getId() + "-" + NO_SEQ.incrementAndGet());
        writeoff.setDetailId(detail.getId());
        writeoff.setReconcileDate(detail.getReconcileDate());
        writeoff.setOrderNo(detail.getOrderNo());
        writeoff.setDiffType(detail.getDiffType());
        writeoff.setAdjustType(req.getAdjustType());
        writeoff.setDiffArea(detail.getDiffArea());
        writeoff.setLocalSideSnapshot(snapshotLocal(detail));
        writeoff.setRemoteSideSnapshot(snapshotRemote(detail));
        writeoff.setReason(req.getReason());
        writeoff.setStatus(ReconConstants.WRITEOFF_APPLIED);
        writeoff.setRequestNo(req.getRequestNo());
        writeoffMapper.insert(writeoff);
        return writeoff;
    }

    /** 审批通过：核销单落账、差异即时清零、批次未平差异汇总重算，同一事务提交。 */
    @Transactional(rollbackFor = Exception.class)
    public ReconWriteoff approve(Long id, WriteoffApproveRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.WRITEOFF_APPROVE, String.valueOf(id));

        ReconWriteoff writeoff = requireWriteoff(id);
        if (!ReconConstants.WRITEOFF_APPLIED.equals(writeoff.getStatus())) {
            throw new BusinessException("核销单状态为 " + writeoff.getStatus() + "，不能审批");
        }
        ReconDetail detail = detailMapper.selectById(writeoff.getDetailId());
        if (detail == null || !ReconConstants.DIFF_OPEN.equals(detail.getStatus())) {
            throw new BusinessException("对应差异已不是未平状态，不能审批核销");
        }

        writeoff.setStatus(ReconConstants.WRITEOFF_APPROVED);
        writeoff.setApprover(req.getApprover());
        writeoff.setApproveTime(LocalDateTime.now());
        writeoff.setApproveRemark(req.getApproveRemark());
        if (writeoffMapper.updateById(writeoff) == 0) {
            throw new BusinessException("核销单状态已变化，请刷新后重试");
        }

        detail.setStatus(ReconConstants.DIFF_WRITTEN_OFF);
        detail.setWriteoffId(writeoff.getId());
        detail.setWriteoffTime(LocalDateTime.now());
        detailMapper.updateById(detail);

        reconService.refreshBatchCounters(detail.getBatchId());
        return writeoff;
    }

    /** 驳回：只留痕，差异保持 OPEN，可重新发起。 */
    @Transactional(rollbackFor = Exception.class)
    public ReconWriteoff reject(Long id, WriteoffRejectRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.WRITEOFF_REJECT, String.valueOf(id));

        ReconWriteoff writeoff = requireWriteoff(id);
        if (!ReconConstants.WRITEOFF_APPLIED.equals(writeoff.getStatus())) {
            throw new BusinessException("核销单状态为 " + writeoff.getStatus() + "，不能驳回");
        }
        writeoff.setStatus(ReconConstants.WRITEOFF_REJECTED);
        writeoff.setRejectReason(req.getRejectReason());
        writeoffMapper.updateById(writeoff);
        return writeoff;
    }

    public List<ReconWriteoff> listByDetail(Long detailId) {
        return writeoffMapper.selectList(new LambdaQueryWrapper<ReconWriteoff>()
                .eq(detailId != null, ReconWriteoff::getDetailId, detailId)
                .orderByDesc(ReconWriteoff::getId));
    }

    private ReconWriteoff requireWriteoff(Long id) {
        ReconWriteoff writeoff = writeoffMapper.selectById(id);
        if (writeoff == null) {
            throw new BusinessException("核销单不存在: " + id);
        }
        return writeoff;
    }

    private String snapshotLocal(ReconDetail d) {
        return "{\"orderNo\":\"" + nv(d.getLocalOrderNo()) + "\",\"trackArea\":"
                + (d.getLocalTrackArea() == null ? "null" : d.getLocalTrackArea().toPlainString())
                + ",\"status\":\"" + nv(d.getLocalStatus()) + "\",\"reversalFlag\":\""
                + nv(d.getLocalReversalFlag()) + "\"}";
    }

    private String snapshotRemote(ReconDetail d) {
        return "{\"billNo\":\"" + nv(d.getRemoteBillNo()) + "\",\"orderNo\":\"" + nv(d.getRemoteOrderNo())
                + "\",\"trackArea\":"
                + (d.getRemoteTrackArea() == null ? "null" : d.getRemoteTrackArea().toPlainString())
                + ",\"status\":\"" + nv(d.getRemoteStatus()) + "\",\"reversalFlag\":\""
                + nv(d.getRemoteReversalFlag()) + "\"}";
    }

    private String nv(String s) {
        return s == null ? "" : s;
    }
}
