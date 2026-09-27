package com.evops.recon.engine;

import com.evops.recon.ReconConstants;
import com.evops.recon.snapshot.ReconEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 旧引擎（并跑基线）：只勾"正常有效账"——红冲反向单与 REVERSED/CANCELLED 原单整笔剔除，
 * 金额分毫必较，无容差。这是切换前线上的老口径，并跑期间作为对照。
 */
@Component
public class LegacyReconEngine extends AbstractReconEngine {

    @Override
    public String code() {
        return ReconConstants.ENGINE_LEGACY;
    }

    @Override
    protected boolean effective(ReconEntry entry) {
        if (entry.getArea() == null) {
            return false;
        }
        if (entry.isReversal()) {
            return false;
        }
        return !"REVERSED".equals(entry.getStatus()) && !"CANCELLED".equals(entry.getStatus());
    }

    @Override
    protected boolean amountMatched(BigDecimal localArea, BigDecimal remoteArea, BigDecimal tolerance) {
        return Objects.equals(localArea.compareTo(remoteArea), 0);
    }
}
