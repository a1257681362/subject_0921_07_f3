package com.evops.recon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.config.ReconcileProperties;
import com.evops.recon.engine.EngineResult;
import com.evops.recon.engine.LegacyReconEngine;
import com.evops.recon.engine.NewReconEngine;
import com.evops.recon.entity.ReconBatch;
import com.evops.recon.entity.ReconDetail;
import com.evops.recon.entity.ReconWriteoff;
import com.evops.recon.mapper.ReconBatchMapper;
import com.evops.recon.mapper.ReconDetailMapper;
import com.evops.recon.mapper.ReconWriteoffMapper;
import com.evops.recon.snapshot.ReconSnapshot;
import com.evops.recon.snapshot.SnapshotBuilder;
import com.evops.recon.vo.BatchDetail;
import com.evops.recon.vo.CutoverCheck;
import com.evops.request.RequestRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 勾稽主链路：建批次 → 快照 → 新旧引擎（并跑期双跑）→ 逐笔明细落库 →
 * 已核销差异自动结转（调整后差异清零）→ 双引擎逐笔比对 → 批次汇总。
 * 整个执行一个事务：任何一步失败整体回退，当日可重跑，不会留下半截批次。
 */
@Service
public class ReconService {

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReconBatchMapper batchMapper;
    private final ReconDetailMapper detailMapper;
    private final ReconWriteoffMapper writeoffMapper;
    private final SnapshotBuilder snapshotBuilder;
    private final NewReconEngine newEngine;
    private final LegacyReconEngine legacyEngine;
    private final ReconConfigService configService;
    private final RequestRecordService requestRecordService;
    private final ReconcileProperties properties;

    public ReconService(ReconBatchMapper batchMapper,
                        ReconDetailMapper detailMapper,
                        ReconWriteoffMapper writeoffMapper,
                        SnapshotBuilder snapshotBuilder,
                        NewReconEngine newEngine,
                        LegacyReconEngine legacyEngine,
                        ReconConfigService configService,
                        RequestRecordService requestRecordService,
                        ReconcileProperties properties) {
        this.batchMapper = batchMapper;
        this.detailMapper = detailMapper;
        this.writeoffMapper = writeoffMapper;
        this.snapshotBuilder = snapshotBuilder;
        this.newEngine = newEngine;
        this.legacyEngine = legacyEngine;
        this.configService = configService;
        this.requestRecordService = requestRecordService;
        this.properties = properties;
    }

    /**
     * 执行某日勾稽。每天每侧只允许一个批次（uk_recon_batch_date 兜底）；
     * 定时触发的请求号按日生成 AUTO-RECON-yyyyMMdd，重复触发会被请求号台账拦下。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconBatch runReconciliation(LocalDate reconcileDate, String triggeredBy, String requestNo) {
        if (reconcileDate == null) {
            reconcileDate = LocalDate.now().minusDays(1);
        }
        String effectiveRequestNo = requestNo != null ? requestNo
                : "AUTO-RECON-" + reconcileDate.format(DATE_FMT);
        String mode = configService.getEngineMode();
        BigDecimal tolerance = configService.getTolerance();

        ReconBatch existing = findBatchByDate(reconcileDate);
        if (existing != null) {
            throw new BusinessException(reconcileDate + " 的勾稽批次已存在（" + existing.getBatchNo() + "），每日只勾一次");
        }

        requestRecordService.register(effectiveRequestNo, BizType.RECON_RUN, reconcileDate.format(DATE_FMT));

        ReconBatch batch = new ReconBatch();
        batch.setBatchNo("RB" + reconcileDate.format(DATE_FMT));
        batch.setReconcileDate(reconcileDate);
        batch.setTriggeredBy(triggeredBy);
        batch.setEngineMode(mode);
        batch.setTolerance(tolerance);
        batch.setStatus(ReconConstants.BATCH_RUNNING);
        batch.setStartedAt(LocalDateTime.now());
        try {
            batchMapper.insert(batch);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(reconcileDate + " 的勾稽批次已存在，每日只勾一次");
        }

        ReconSnapshot snapshot = snapshotBuilder.build(reconcileDate);
        EngineResult engResult = newEngine.match(snapshot, tolerance);
        List<ReconDetail> engDetails = persistDetails(batch.getId(), engResult.getDiffs());
        applyWriteoffCarryForward(batch.getId(), engDetails);

        fillNewCounters(batch, engResult, engDetails);

        if (ReconConstants.MODE_PARALLEL.equals(mode)) {
            EngineResult legResult = legacyEngine.match(snapshot, tolerance);
            List<ReconDetail> legDetails = persistDetails(batch.getId(), legResult.getDiffs());
            fillLegacyCounters(batch, legResult, legDetails);
            compareEngines(batch, engResult, legResult);
        }

        batch.setStatus(ReconConstants.BATCH_DONE);
        batch.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        log.info("勾稽批次 {} 完成：新模式差异 {} 笔（未核销 {}），双引擎比对 {}",
                batch.getBatchNo(), batch.getEngDiffCount(), batch.getEngOpenDiffCount(),
                batch.getEngineCompareResult() == null ? "-" : batch.getEngineCompareResult());
        return batch;
    }

    /** 核销审批后即时重算批次新引擎口径计数：差异清零直接体现在批次汇总上。 */
    public void refreshBatchCounters(Long batchId) {
        ReconBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return;
        }
        List<ReconDetail> details = detailMapper.selectList(
                new LambdaQueryWrapper<ReconDetail>()
                        .eq(ReconDetail::getBatchId, batchId)
                        .eq(ReconDetail::getEngine, ReconConstants.ENGINE_NEW));
        int open = 0;
        BigDecimal openTotal = BigDecimal.ZERO;
        for (ReconDetail d : details) {
            if (ReconConstants.DIFF_OPEN.equals(d.getStatus())) {
                open++;
                openTotal = openTotal.add(d.getAbsDiffArea());
            }
        }
        batch.setEngDiffCount(details.size());
        batch.setEngOpenDiffCount(open);
        batch.setEngResolvedDiffCount(details.size() - open);
        batch.setEngDiffTotal(openTotal.setScale(2, BigDecimal.ROUND_HALF_UP));
        batchMapper.updateById(batch);
    }

    public List<ReconBatch> listRecentBatches(int limit) {
        return batchMapper.selectList(new LambdaQueryWrapper<ReconBatch>()
                .eq(ReconBatch::getStatus, ReconConstants.BATCH_DONE)
                .orderByDesc(ReconBatch::getReconcileDate)
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }

    public BatchDetail getBatchDetail(Long batchId) {
        ReconBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("勾稽批次不存在: " + batchId);
        }
        List<ReconDetail> details = detailMapper.selectList(
                new LambdaQueryWrapper<ReconDetail>()
                        .eq(ReconDetail::getBatchId, batchId)
                        .orderByAsc(ReconDetail::getEngine)
                        .orderByAsc(ReconDetail::getId));
        BatchDetail detail = new BatchDetail();
        detail.setBatch(batch);
        detail.setNewDetails(details.stream()
                .filter(d -> ReconConstants.ENGINE_NEW.equals(d.getEngine())).collect(Collectors.toList()));
        detail.setLegacyDetails(details.stream()
                .filter(d -> ReconConstants.ENGINE_LEGACY.equals(d.getEngine())).collect(Collectors.toList()));
        return detail;
    }

    /** 当前最新批次下的未核销差异（新引擎口径），差异清单带双方明细。 */
    public List<ReconDetail> listOpenDiffs(LocalDate reconcileDate) {
        LambdaQueryWrapper<ReconDetail> wrapper = new LambdaQueryWrapper<ReconDetail>()
                .eq(ReconDetail::getEngine, ReconConstants.ENGINE_NEW)
                .eq(ReconDetail::getStatus, ReconConstants.DIFF_OPEN)
                .orderByAsc(ReconDetail::getId);
        if (reconcileDate != null) {
            wrapper.eq(ReconDetail::getReconcileDate, reconcileDate);
        } else {
            ReconBatch latest = batchMapper.selectOne(new LambdaQueryWrapper<ReconBatch>()
                    .eq(ReconBatch::getStatus, ReconConstants.BATCH_DONE)
                    .orderByDesc(ReconBatch::getReconcileDate)
                    .last("limit 1"));
            if (latest == null) {
                return new ArrayList<>();
            }
            wrapper.eq(ReconDetail::getBatchId, latest.getId());
        }
        return detailMapper.selectList(wrapper);
    }

    /**
     * 切换闸门：最近 N 个完成批次双引擎逐笔比对一致，且没有未核销差异，才允许切新引擎。
     */
    public CutoverCheck checkCutoverReady() {
        int required = Math.max(1, properties.getCutoverBatchSize());
        CutoverCheck check = new CutoverCheck();
        check.setRequiredBatches(required);

        List<ReconBatch> recent = batchMapper.selectList(new LambdaQueryWrapper<ReconBatch>()
                .eq(ReconBatch::getStatus, ReconConstants.BATCH_DONE)
                .orderByDesc(ReconBatch::getReconcileDate)
                .last("limit " + required));
        if (recent.size() < required) {
            check.getReasons().add("并跑批次不足：需要最近 " + required + " 个，实际 " + recent.size() + " 个");
        }
        int matched = 0;
        for (ReconBatch b : recent) {
            if (ReconConstants.COMPARE_MATCH.equals(b.getEngineCompareResult())) {
                matched++;
            } else {
                check.getReasons().add("批次 " + b.getBatchNo() + " 双引擎结果不一致："
                        + String.valueOf(b.getEngineCompareDetail()));
            }
            if (b.getEngOpenDiffCount() != null && b.getEngOpenDiffCount() > 0) {
                check.getReasons().add("批次 " + b.getBatchNo() + " 仍有 " + b.getEngOpenDiffCount()
                        + " 笔未核销差异");
            }
        }
        check.setMatchedBatches(matched);
        check.setReady(check.getReasons().isEmpty() && matched == required);
        return check;
    }

    // ---- internals ----

    private ReconBatch findBatchByDate(LocalDate date) {
        return batchMapper.selectOne(new LambdaQueryWrapper<ReconBatch>()
                .eq(ReconBatch::getReconcileDate, date).last("limit 1"));
    }

    private List<ReconDetail> persistDetails(Long batchId, List<ReconDetail> diffs) {
        List<ReconDetail> saved = new ArrayList<>();
        for (ReconDetail d : diffs) {
            d.setBatchId(batchId);
            detailMapper.insert(d);
            saved.add(d);
        }
        return saved;
    }

    /**
     * 已审批核销的差异若在新快照中仍然存在（对方尚未补/冲账），自动结转到本批：
     * 本批对应明细直接置 WRITTEN_OFF 并挂上来历，调整后差异清零，不会当新差异重复报。
     */
    private void applyWriteoffCarryForward(Long batchId, List<ReconDetail> currentDetails) {
        List<ReconWriteoff> approved = writeoffMapper.selectList(
                new LambdaQueryWrapper<ReconWriteoff>()
                        .eq(ReconWriteoff::getStatus, ReconConstants.WRITEOFF_APPROVED)
                        .orderByAsc(ReconWriteoff::getId));
        if (approved.isEmpty() || currentDetails.isEmpty()) {
            return;
        }
        Map<String, ReconDetail> openByKey = new HashMap<>();
        for (ReconDetail d : currentDetails) {
            if (ReconConstants.DIFF_OPEN.equals(d.getStatus())) {
                openByKey.put(d.getOrderNo() + "|" + d.getDiffType(), d);
            }
        }
        for (ReconWriteoff w : approved) {
            ReconDetail recurring = openByKey.get(w.getOrderNo() + "|" + w.getDiffType());
            if (recurring != null) {
                recurring.setStatus(ReconConstants.DIFF_WRITTEN_OFF);
                recurring.setWriteoffId(w.getId());
                recurring.setCarriedFromDetailId(w.getDetailId());
                recurring.setWriteoffTime(LocalDateTime.now());
                detailMapper.updateById(recurring);
                openByKey.remove(w.getOrderNo() + "|" + w.getDiffType());
                log.info("差异结转：批次 {} 单号 {} 的已核销差异仍在，自动清零并挂账核销单 {}",
                        batchId, w.getOrderNo(), w.getWriteoffNo());
            }
        }
    }

    private void fillNewCounters(ReconBatch batch, EngineResult result, List<ReconDetail> details) {
        int open = 0;
        int localOnly = 0;
        int remoteOnly = 0;
        int amountDiff = 0;
        BigDecimal openTotal = BigDecimal.ZERO;
        for (ReconDetail d : details) {
            if (ReconConstants.DIFF_OPEN.equals(d.getStatus())) {
                open++;
                openTotal = openTotal.add(d.getAbsDiffArea());
            }
            if (DiffType.LOCAL_ONLY.equals(d.getDiffType())) {
                localOnly++;
            } else if (DiffType.REMOTE_ONLY.equals(d.getDiffType())) {
                remoteOnly++;
            } else if (DiffType.AMOUNT_DIFF.equals(d.getDiffType())) {
                amountDiff++;
            }
        }
        batch.setEngMatchedCount(result.getMatchedCount());
        batch.setEngLocalOnlyCount(localOnly);
        batch.setEngRemoteOnlyCount(remoteOnly);
        batch.setEngAmountDiffCount(amountDiff);
        batch.setEngDiffCount(details.size());
        batch.setEngResolvedDiffCount(details.size() - open);
        batch.setEngOpenDiffCount(open);
        batch.setEngLocalTotal(scale(result.getLocalTotal()));
        batch.setEngRemoteTotal(scale(result.getRemoteTotal()));
        batch.setEngDiffTotal(scale(openTotal));
    }

    private void fillLegacyCounters(ReconBatch batch, EngineResult result, List<ReconDetail> details) {
        BigDecimal diffTotal = BigDecimal.ZERO;
        for (ReconDetail d : details) {
            diffTotal = diffTotal.add(d.getAbsDiffArea());
        }
        batch.setLegMatchedCount(result.getMatchedCount());
        batch.setLegDiffCount(details.size());
        batch.setLegOpenDiffCount(details.size());
        batch.setLegLocalTotal(scale(result.getLocalTotal()));
        batch.setLegRemoteTotal(scale(result.getRemoteTotal()));
        batch.setLegDiffTotal(scale(diffTotal));
    }

    /** 双引擎逐笔比对：一致单号集合、差异单号+差异类型都相同才算 MATCH。 */
    private void compareEngines(ReconBatch batch, EngineResult eng, EngineResult leg) {
        List<String> notes = new ArrayList<>();

        for (String key : eng.getMatchedKeys()) {
            if (!leg.getMatchedKeys().contains(key)) {
                notes.add("新引擎判一致而旧引擎未判一致: " + key);
            }
        }
        for (String key : leg.getMatchedKeys()) {
            if (!eng.getMatchedKeys().contains(key)) {
                notes.add("旧引擎判一致而新引擎未判一致: " + key);
            }
        }
        Map<String, String> engDiffMap = diffTypeMap(eng);
        Map<String, String> legDiffMap = diffTypeMap(leg);
        for (Map.Entry<String, String> e : engDiffMap.entrySet()) {
            String legType = legDiffMap.get(e.getKey());
            if (legType == null) {
                notes.add("仅新引擎报差异: " + e.getKey() + "(" + e.getValue() + ")");
            } else if (!legType.equals(e.getValue())) {
                notes.add("差异定性不一致: " + e.getKey() + " 新=" + e.getValue() + " 旧=" + legType);
            }
        }
        for (Map.Entry<String, String> e : legDiffMap.entrySet()) {
            if (!engDiffMap.containsKey(e.getKey())) {
                notes.add("仅旧引擎报差异: " + e.getKey() + "(" + e.getValue() + ")");
            }
        }

        if (notes.isEmpty()) {
            batch.setEngineCompareResult(ReconConstants.COMPARE_MATCH);
            batch.setEngineCompareDetail("双引擎逐笔一致");
        } else {
            batch.setEngineCompareResult(ReconConstants.COMPARE_MISMATCH);
            String detail = notes.stream().limit(10).collect(Collectors.joining("; "));
            if (notes.size() > 10) {
                detail = detail + "; 等 " + notes.size() + " 处";
            }
            batch.setEngineCompareDetail(detail);
        }
    }

    private Map<String, String> diffTypeMap(EngineResult result) {
        Map<String, String> map = new HashMap<>();
        for (ReconDetail d : result.getDiffs()) {
            map.put(d.getOrderNo(), d.getDiffType());
        }
        return map;
    }

    private BigDecimal scale(BigDecimal v) {
        return v.setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}
