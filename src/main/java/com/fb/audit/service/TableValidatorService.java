package com.fb.audit.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fb.audit.exception.AuditException;
import com.fb.audit.util.BatchUtils;
import com.fb.audit.util.IdentifierValidator;
import com.fb.audit.util.QueryCountingJdbcTemplate;

@Service
public class TableValidatorService {

	public void validateTablesExist(
			QueryCountingJdbcTemplate testJdbc,
			QueryCountingJdbcTemplate devJdbc,
			String testSchema,
			String devSchema,
			List<String> tableNames) {

		Set<String> testTables = fetchExistingTables(testJdbc, testSchema, tableNames);
		Set<String> devTables = fetchExistingTables(devJdbc, devSchema, tableNames);

		for (String table : tableNames) {
			if (!testTables.contains(table)) {
				throw new AuditException("Table introuvable dans TEST (" + testSchema + ") : " + table);
			}
			if (!devTables.contains(table)) {
				throw new AuditException("Table introuvable dans DEV (" + devSchema + ") : " + table);
			}
		}
	}

	private Set<String> fetchExistingTables(
			QueryCountingJdbcTemplate jdbc, String schema, List<String> tableNames) {

		Set<String> found = new HashSet<>();
		for (List<String> batch : BatchUtils.partition(tableNames, 1000)) {
			String placeholders = String.join(", ", batch.stream().map(t -> "?").toList());
			// INDEX sur ALL_TABLES (OWNER, TABLE_NAME) — pas de full scan applicatif
			String sql = """
					SELECT table_name
					FROM all_tables
					WHERE owner = ?
					  AND table_name IN (%s)
					""".formatted(placeholders);

			Object[] args = new Object[batch.size() + 1];
			args[0] = IdentifierValidator.normalizeSchema(schema);
			for (int i = 0; i < batch.size(); i++) {
				args[i + 1] = batch.get(i);
			}

			found.addAll(jdbc.query(sql, (rs, rowNum) -> rs.getString("table_name").toUpperCase(), args));
		}
		return found;
	}
}
