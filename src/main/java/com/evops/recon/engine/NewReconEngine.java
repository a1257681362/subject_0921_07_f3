package com.evops.recon.engine;

import com.evops.recon.ReconConstants;
import com.evops.recon.snapshot.ReconEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 新引擎：红冲原单 + 反向单都进勾稽（净额口径，红冲双方逐笔配对），
 * 仅剔除已取消作业单；金额在可配置容差内即视为一致，超容差才出 AMOUNT_DIFF。
 */
@Component
public class NewReconEngine extends AbstractReconEngine {

    @Override
    public String code() {
        return ReconConstants.ENGINE_NEW;
    }

    @Override
    protected boolean effective(ReconEntry entry) {
        if (entry.getArea() == null) {
            return false;
        }
        return !"CANCELLED".equals(entry.getStatus());
    }

    @Override
    protected boolean amountMatched(BigDecimal localArea, BigDecimal remoteArea, BigDecimal tolerance) {
        BigDecimal delta = localArea.subtract(remoteArea).abs();
        return delta.compareTo(tolerance) <= 0;
    }
}
