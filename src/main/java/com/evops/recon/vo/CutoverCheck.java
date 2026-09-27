package com.evops.recon.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 切换前闸门检查结果：最近 N 批双引擎逐笔一致且无未核销差异，才允许切到新引擎。 */
@Data
public class CutoverCheck {
    private boolean ready;
    private int requiredBatches;
    private int matchedBatches;
    private final List<String> reasons = new ArrayList<>();
}
