package com.fb.audit.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fb.audit.model.ForeignKeyInfo;
import com.fb.audit.model.PkShape;
import com.fb.audit.util.BatchUtils;
import com.fb.audit.util.IdentifierValidator;
import com.fb.audit.util.QueryCountingJdbcTemplate;

@Service
public class SchemaDiscoveryService {

	private static final Set<String> NUMERIC_TYPES = Set.of(
			"NUMBER", "INTEGER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE");

	public Map<String, PkShape> discoverPrimaryKeys(
			QueryCountingJdbcTemplate jdbc, String schema, List<String> tableNames) {

		Map<String, List<String>> pkColumnsByTable = new LinkedHashMap<>();
		for (List<String> batch : BatchUtils.partition(tableNames, 1000)) {
			String placeholders = String.join(", ", batch.stream().map(t -> "?").toList());
			// Vues catalogue ALL_CONSTRAINTS / ALL_CONS_COLUMNS — indexées par OWNER
			String sql = """
					SELECT cols.table_name, cols.column_name, cols.position
					FROM all_constraints cons
					JOIN all_cons_columns cols
					  ON cons.constraint_name = cols.constraint_name
					 AND cons.owner = cols.owner
					WHERE cons.constraint_type = 'P'
					  AND cons.owner = ?
					  AND cons.table_name IN (%s)
					ORDER BY cols.table_name, cols.position
					""".formatted(placeholders);

			Object[] args = buildArgs(schema, batch);
			jdbc.query(sql, (rs, rowNum) -> {
				String table = rs.getString("table_name").toUpperCase();
				String column = rs.getString("column_name").toUpperCase();
				pkColumnsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(column);
				return null;
			}, args);
		}

		Map<String, String> columnTypes = discoverColumnTypes(jdbc, schema, tableNames, pkColumnsByTable);

		Map<String, PkShape> result = new HashMap<>();
		for (String table : tableNames) {
			List<String> columns = pkColumnsByTable.get(table);
			if (columns == null || columns.isEmpty()) {
				continue;
			}
			boolean numeric = columns.size() == 1
					&& isNumericType(columnTypes.get(table + "." + columns.getFirst()));
			result.put(table, new PkShape(table, List.copyOf(columns), numeric));
		}
		return result;
	}

	public Map<String, List<ForeignKeyInfo>> discoverForeignKeys(
			QueryCountingJdbcTemplate jdbc, String schema, List<String> tableNames) {

		Map<String, List<ForeignKeyInfo>> fksByChildTable = new LinkedHashMap<>();
		for (String table : tableNames) {
			fksByChildTable.put(table, new ArrayList<>());
		}

		for (List<String> batch : BatchUtils.partition(tableNames, 1000)) {
			String placeholders = String.join(", ", batch.stream().map(t -> "?").toList());
			String sql = """
					SELECT a.table_name   AS child_table,
					       a.column_name  AS fk_column,
					       c_pk.table_name AS parent_table,
					       b.column_name  AS parent_column
					FROM all_cons_columns a
					JOIN all_constraints c
					  ON a.constraint_name = c.constraint_name AND a.owner = c.owner
					JOIN all_constraints c_pk
					  ON c.r_constraint_name = c_pk.constraint_name AND c.r_owner = c_pk.owner
					JOIN all_cons_columns b
					  ON c_pk.constraint_name = b.constraint_name AND c_pk.owner = b.owner
					WHERE c.constraint_type = 'R'
					  AND a.owner = ?
					  AND a.table_name IN (%s)
					""".formatted(placeholders);

			Object[] args = buildArgs(schema, batch);
			jdbc.query(sql, (rs, rowNum) -> {
				String childTable = rs.getString("child_table").toUpperCase();
				ForeignKeyInfo fk = new ForeignKeyInfo(
						childTable,
						rs.getString("fk_column").toUpperCase(),
						rs.getString("parent_table").toUpperCase(),
						rs.getString("parent_column").toUpperCase());
				fksByChildTable.computeIfAbsent(childTable, k -> new ArrayList<>()).add(fk);
				return null;
			}, args);
		}
		return fksByChildTable;
	}

	public boolean hasIndexOnColumn(
			QueryCountingJdbcTemplate jdbc, String schema, String table, String column) {

		// ALL_IND_COLUMNS indexée par (TABLE_OWNER, TABLE_NAME, COLUMN_NAME)
		String sql = """
				SELECT COUNT(*)
				FROM all_ind_columns ic
				JOIN all_indexes i
				  ON ic.index_name = i.index_name
				 AND ic.table_owner = i.table_owner
				WHERE ic.table_owner = ?
				  AND ic.table_name = ?
				  AND ic.column_name = ?
				  AND i.index_type != 'LOB'
				""";
		Integer count = jdbc.queryForObject(sql, Integer.class,
				IdentifierValidator.normalizeSchema(schema),
				IdentifierValidator.normalizeTableName(table),
				IdentifierValidator.normalizeColumn(column));
		return count != null && count > 0;
	}

	private Map<String, String> discoverColumnTypes(
			QueryCountingJdbcTemplate jdbc,
			String schema,
			List<String> tableNames,
			Map<String, List<String>> pkColumnsByTable) {

		Map<String, String> types = new HashMap<>();
		List<String> columnsToFetch = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : pkColumnsByTable.entrySet()) {
			if (entry.getValue().size() == 1) {
				columnsToFetch.add(entry.getKey() + "\0" + entry.getValue().getFirst());
			}
		}
		if (columnsToFetch.isEmpty()) {
			return types;
		}

		for (List<String> batch : BatchUtils.partition(columnsToFetch, 500)) {
			StringBuilder sql = new StringBuilder("""
					SELECT table_name, column_name, data_type
					FROM all_tab_columns
					WHERE owner = ?
					  AND (
					""");
			List<Object> args = new ArrayList<>();
			args.add(IdentifierValidator.normalizeSchema(schema));
			for (int i = 0; i < batch.size(); i++) {
				if (i > 0) {
					sql.append(" OR ");
				}
				String[] parts = batch.get(i).split("\0");
				sql.append("(table_name = ? AND column_name = ?)");
				args.add(parts[0]);
				args.add(parts[1]);
			}
			sql.append(")");

			jdbc.query(sql.toString(), (rs, rowNum) -> {
				String key = rs.getString("table_name").toUpperCase() + "."
						+ rs.getString("column_name").toUpperCase();
				types.put(key, rs.getString("data_type").toUpperCase());
				return null;
			}, args.toArray());
		}
		return types;
	}

	private boolean isNumericType(String dataType) {
		if (dataType == null) {
			return false;
		}
		return NUMERIC_TYPES.contains(dataType);
	}

	private Object[] buildArgs(String schema, List<String> batch) {
		Object[] args = new Object[batch.size() + 1];
		args[0] = IdentifierValidator.normalizeSchema(schema);
		for (int i = 0; i < batch.size(); i++) {
			args[i + 1] = batch.get(i);
		}
		return args;
	}
}
