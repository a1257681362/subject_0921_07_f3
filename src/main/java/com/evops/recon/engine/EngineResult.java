package com.evops.recon.engine;

import com.evops.recon.entity.ReconDetail;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一次引擎匹配的产出：一致笔数、差异明细（含双方数据）、参与勾稽的双方汇总额。 */
@Data
public class EngineResult {
    private int matchedCount;
    /** 勾稽一致的单号集合（供双引擎逐笔比对）。 */
    private final Set<String> matchedKeys = new HashSet<>();
    private final List<ReconDetail> diffs = new ArrayList<>();
    private BigDecimal localTotal = BigDecimal.ZERO;
    private BigDecimal remoteTotal = BigDecimal.ZERO;

    public int getDiffCount() {
        return diffs.size();
    }
}
