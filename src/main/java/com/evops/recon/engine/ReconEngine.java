package com.evops.recon.engine;

import com.evops.recon.snapshot.ReconSnapshot;

import java.math.BigDecimal;

/** 勾稽匹配引擎：同一快照、同一汇总口径，只在配对判定规则上区分新旧。 */
public interface ReconEngine {

    /** NEW / LEGACY。 */
    String code();

    EngineResult match(ReconSnapshot snapshot, BigDecimal tolerance);
}
