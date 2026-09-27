package com.evops.recon.vo;

import com.evops.recon.entity.ReconBatch;
import com.evops.recon.entity.ReconDetail;
import lombok.Data;

import java.util.List;

/** 批次详情：批次头 + 新引擎差异清单 +（并跑期）旧引擎差异清单。 */
@Data
public class BatchDetail {
    private ReconBatch batch;
    private List<ReconDetail> newDetails;
    private List<ReconDetail> legacyDetails;
}
