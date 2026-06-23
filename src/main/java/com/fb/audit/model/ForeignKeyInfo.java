package com.fb.audit.model;

public record ForeignKeyInfo(
		String childTable,
		String fkColumn,
		String parentTable,
		String parentColumn
) {
}
