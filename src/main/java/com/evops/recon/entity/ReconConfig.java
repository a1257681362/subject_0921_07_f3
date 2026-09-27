package com.evops.recon.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 勾稽运行期配置键值（TOLERANCE / ENGINE_MODE），在线调整后重启不丢。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recon_config")
public class ReconConfig extends BaseEntity {
    public static final String KEY_TOLERANCE = "TOLERANCE";
    public static final String KEY_ENGINE_MODE = "ENGINE_MODE";

    private String configKey;
    private String configValue;

    @Version
    private Integer version = 0;
}
