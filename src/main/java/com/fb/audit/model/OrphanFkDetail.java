package com.fb.audit.model;

public record OrphanFkDetail(
		String childTable,
		String fkColumn,
		String parentTable,
		String parentColumn,
		Object childPk,
		Object missingFkValue
) {
}
