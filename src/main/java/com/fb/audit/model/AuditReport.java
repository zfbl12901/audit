package com.fb.audit.model;

import java.util.List;

public record AuditReport(
		List<OverlapResult> overlaps,
		List<OrphanFkResult> orphanFks,
		List<WarningEntry> warnings,
		int sqlQueryCount
) {
}
