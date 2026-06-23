package com.fb.audit.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class IdentifierValidator {

	private static final Pattern ORACLE_IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]*$");

	private IdentifierValidator() {
	}

	public static String normalizeTableName(String tableName) {
		if (tableName == null || tableName.isBlank()) {
			throw new IllegalArgumentException("Le nom de table ne peut pas être vide");
		}
		String trimmed = tableName.trim();
		if (!ORACLE_IDENTIFIER.matcher(trimmed).matches()) {
			throw new IllegalArgumentException("Nom de table invalide : " + tableName);
		}
		return trimmed.toUpperCase();
	}

	public static List<String> normalizeTableNames(List<String> tableNames) {
		return tableNames.stream().map(IdentifierValidator::normalizeTableName).distinct().toList();
	}

	public static String normalizeSchema(String schema) {
		if (schema == null || schema.isBlank()) {
			throw new IllegalArgumentException("Le schéma ne peut pas être vide");
		}
		return schema.trim().toUpperCase();
	}

	public static String qualifiedTable(String schema, String table) {
		return normalizeSchema(schema) + "." + normalizeTableName(table);
	}

	public static String normalizeColumn(String column) {
		if (column == null || column.isBlank()) {
			throw new IllegalArgumentException("Le nom de colonne ne peut pas être vide");
		}
		String trimmed = column.trim();
		if (!ORACLE_IDENTIFIER.matcher(trimmed).matches()) {
			throw new IllegalArgumentException("Nom de colonne invalide : " + column);
		}
		return trimmed.toUpperCase();
	}
}
