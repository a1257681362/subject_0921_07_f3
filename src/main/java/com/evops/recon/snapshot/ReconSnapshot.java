package com.evops.recon.snapshot;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 截至勾稽日双方账面的累计快照。 */
@Data
public class ReconSnapshot {
    private LocalDate reconcileDate;
    private List<ReconEntry> localEntries;
    private List<ReconEntry> remoteEntries;
}
