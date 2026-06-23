package com.fb.audit.model;

import java.util.List;

public record OrphanFkResult(
		String childTable,
		String fkColumn,
		String parentTable,
		String parentColumn,
		int totalOrphanCount,
		List<OrphanFkDetail> details,
		String limitationNote
) {
}
