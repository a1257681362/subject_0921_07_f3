package com.evops.recon;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.config.ReconcileProperties;
import com.evops.recon.entity.ReconConfig;
import com.evops.recon.mapper.ReconConfigMapper;
import com.evops.request.RequestRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 勾稽运行期配置：application.yml 给默认值，t_recon_config 存在线调整结果（重启不丢）。
 * 容差、引擎模式的变更同样走请求号台账。
 */
@Service
public class ReconConfigService {

    private final ReconConfigMapper configMapper;
    private final ReconcileProperties properties;
    private final RequestRecordService requestRecordService;

    public ReconConfigService(ReconConfigMapper configMapper,
                              ReconcileProperties properties,
                              RequestRecordService requestRecordService) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.requestRecordService = requestRecordService;
    }

    public BigDecimal getTolerance() {
        String value = get(ReconConfig.KEY_TOLERANCE);
        return value == null ? properties.getTolerance() : new BigDecimal(value);
    }

    public String getEngineMode() {
        String value = get(ReconConfig.KEY_ENGINE_MODE);
        return value == null ? properties.getEngineMode() : value;
    }

    @Transactional(rollbackFor = Exception.class)
    public BigDecimal updateTolerance(BigDecimal tolerance, String requestNo) {
        if (tolerance == null || tolerance.signum() < 0) {
            throw new BusinessException("匹配容差不能为空且不能为负");
        }
        requestRecordService.register(requestNo, BizType.RECON_TOLERANCE_UPDATE,
                ReconConfig.KEY_TOLERANCE + "=" + tolerance.toPlainString());
        put(ReconConfig.KEY_TOLERANCE, tolerance.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString());
        return tolerance;
    }

    @Transactional(rollbackFor = Exception.class)
    public String updateEngineMode(String mode, String requestNo) {
        if (!ReconConstants.MODE_PARALLEL.equals(mode)
                && !ReconConstants.MODE_NEW.equals(mode)
                && !ReconConstants.MODE_LEGACY.equals(mode)) {
            throw new BusinessException("未知引擎模式: " + mode);
        }
        requestRecordService.register(requestNo, BizType.RECON_ENGINE_MODE_UPDATE,
                ReconConfig.KEY_ENGINE_MODE + "=" + mode);
        put(ReconConfig.KEY_ENGINE_MODE, mode);
        return mode;
    }

    private String get(String key) {
        ReconConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<ReconConfig>().eq(ReconConfig::getConfigKey, key));
        return config == null ? null : config.getConfigValue();
    }

    private void put(String key, String value) {
        ReconConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<ReconConfig>().eq(ReconConfig::getConfigKey, key));
        if (config == null) {
            config = new ReconConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            configMapper.insert(config);
        } else {
            config.setConfigValue(value);
            configMapper.updateById(config);
        }
    }
}
