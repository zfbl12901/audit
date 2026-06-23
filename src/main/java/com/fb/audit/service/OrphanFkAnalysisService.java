package com.fb.audit.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fb.audit.config.AuditProperties;
import com.fb.audit.model.OrphanFkDetail;
import com.fb.audit.model.OrphanFkResult;
import com.fb.audit.model.PkShape;
import com.fb.audit.model.WarningEntry;
import com.fb.audit.util.BatchUtils;
import com.fb.audit.util.IdentifierValidator;
import com.fb.audit.util.QueryCountingJdbcTemplate;

/**
 * Vérification des FK orphelines par lots {@code IN (...)} uniquement (max 1000 valeurs).
 * Lecture seule stricte : aucune table temporaire ni écriture en base.
 */
@Service
public class OrphanFkAnalysisService {

	private final AuditProperties properties;
	private final SchemaDiscoveryService schemaDiscoveryService;

	public OrphanFkAnalysisService(AuditProperties properties, SchemaDiscoveryService schemaDiscoveryService) {
		this.properties = properties;
		this.schemaDiscoveryService = schemaDiscoveryService;
	}

	public OrphanFkResult analyze(
			QueryCountingJdbcTemplate testJdbc,
			QueryCountingJdbcTemplate devJdbc,
			String testSchema,
			String devSchema,
			String childTable,
			String fkColumn,
			String parentTable,
			String parentColumn,
			Map<String, PkShape> pkByTable,
			Set<String> exportScope,
			List<WarningEntry> warnings) {

		String qualifiedChild = IdentifierValidator.qualifiedTable(testSchema, childTable);
		String fkCol = IdentifierValidator.normalizeColumn(fkColumn);
		String parentCol = IdentifierValidator.normalizeColumn(parentColumn);

		if (!schemaDiscoveryService.hasIndexOnColumn(testJdbc, testSchema, childTable, fkColumn)) {
			warnings.add(new WarningEntry(childTable,
					"Aucun index détecté sur la colonne FK " + fkColumn
							+ " — le SELECT DISTINCT peut déclencher un full scan"));
		}

		// DISTINCT sur colonne FK — index FK si présent
		String distinctSql = "SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL".formatted(fkCol, qualifiedChild, fkCol);
		List<Object> distinctFkValues = testJdbc.query(distinctSql, (rs, rowNum) -> rs.getObject(1));

		if (distinctFkValues.isEmpty()) {
			return new OrphanFkResult(childTable, fkColumn, parentTable, parentColumn, 0, List.of(), null);
		}

		Set<Object> existingInDev = fetchExistingValues(
				devJdbc, devSchema, parentTable, parentCol, distinctFkValues);
		Set<Object> existingInTestExport = fetchExistingValues(
				testJdbc, testSchema, parentTable, parentCol, distinctFkValues);

		List<Object> orphanValues = distinctFkValues.stream()
				.filter(v -> !existingInDev.contains(v))
				.filter(v -> !exportScope.contains(parentTable) || !existingInTestExport.contains(v))
				.toList();

		if (orphanValues.isEmpty()) {
			return new OrphanFkResult(childTable, fkColumn, parentTable, parentColumn, 0, List.of(), null);
		}

		PkShape childPk = pkByTable.get(childTable);
		String limitationNote = null;
		List<OrphanFkDetail> details;

		if (childPk == null || !childPk.isSimple()) {
			limitationNote = "PK enfant absente ou composite — détail des lignes enfant non disponible";
			details = orphanValues.stream()
					.limit(properties.maxOrphanDetailsPerTable())
					.map(v -> new OrphanFkDetail(childTable, fkColumn, parentTable, parentColumn, null, v))
					.toList();
		} else {
			details = fetchOrphanChildRows(
					testJdbc, testSchema, childTable, parentTable, parentColumn,
					childPk.singleColumn(), fkCol, orphanValues);
		}

		return new OrphanFkResult(
				childTable, fkColumn, parentTable, parentColumn,
				orphanValues.size(), details, limitationNote);
	}

	private Set<Object> fetchExistingValues(
			QueryCountingJdbcTemplate jdbc,
			String schema,
			String table,
			String column,
			List<Object> values) {

		Set<Object> found = new HashSet<>();
		String qualifiedTable = IdentifierValidator.qualifiedTable(schema, table);
		int batchSize = properties.inClauseBatchSize();

		for (List<Object> batch : BatchUtils.partition(values, batchSize)) {
			String placeholders = String.join(", ", batch.stream().map(v -> "?").toList());
			// INDEX UNIQUE/RANGE SCAN sur la colonne parente indexée (FK côté DEV)
			String sql = "SELECT %s FROM %s WHERE %s IN (%s)".formatted(
					column, qualifiedTable, column, placeholders);
			found.addAll(jdbc.query(sql, (rs, rowNum) -> rs.getObject(1), batch.toArray()));
		}
		return found;
	}

	private List<OrphanFkDetail> fetchOrphanChildRows(
			QueryCountingJdbcTemplate testJdbc,
			String schema,
			String childTable,
			String parentTable,
			String parentColumn,
			String childPkColumn,
			String fkColumn,
			List<Object> orphanValues) {

		List<OrphanFkDetail> details = new ArrayList<>();
		int maxDetails = properties.maxOrphanDetailsPerTable();
		int batchSize = properties.inClauseBatchSize();
		String qualifiedChild = IdentifierValidator.qualifiedTable(schema, childTable);
		String pkCol = IdentifierValidator.normalizeColumn(childPkColumn);

		for (List<Object> batch : BatchUtils.partition(orphanValues, batchSize)) {
			if (details.size() >= maxDetails) {
				break;
			}
			String placeholders = String.join(", ", batch.stream().map(v -> "?").toList());
			// INDEX RANGE SCAN sur l'index FK de la table enfant
			String sql = "SELECT %s AS child_pk, %s AS fk_value FROM %s WHERE %s IN (%s)".formatted(
					pkCol, fkColumn, qualifiedChild, fkColumn, placeholders);

			testJdbc.query(sql, (rs, rowNum) -> {
				if (details.size() < maxDetails) {
					details.add(new OrphanFkDetail(
							childTable, fkColumn, parentTable, parentColumn,
							rs.getObject("child_pk"), rs.getObject("fk_value")));
				}
				return null;
			}, batch.toArray());
		}
		return details;
	}
}
