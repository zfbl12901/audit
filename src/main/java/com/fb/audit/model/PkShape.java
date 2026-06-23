package com.fb.audit.model;

import java.util.List;

public record PkShape(String tableName, List<String> columns, boolean numeric) {

	public boolean isSimple() {
		return columns.size() == 1;
	}

	public boolean isComposite() {
		return columns.size() > 1;
	}

	public String singleColumn() {
		return columns.getFirst();
	}
}
