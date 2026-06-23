package com.fb.audit.service;

import org.springframework.stereotype.Service;

import com.fb.audit.model.OverlapResult;
import com.fb.audit.model.PkShape;
import com.fb.audit.util.IdentifierValidator;
import com.fb.audit.util.QueryCountingJdbcTemplate;

@Service
public class OverlapAnalysisService {

	public OverlapResult analyze(
			QueryCountingJdbcTemplate testJdbc,
			QueryCountingJdbcTemplate devJdbc,
			String testSchema,
			String devSchema,
			String tableName,
			PkShape pk) {

		String column = IdentifierValidator.normalizeColumn(pk.singleColumn());
		String testTable = IdentifierValidator.qualifiedTable(testSchema, tableName);
		String devTable = IdentifierValidator.qualifiedTable(devSchema, tableName);

		// INDEX FULL SCAN (MIN/MAX) sur l'index de la PK — pas de full table scan
		String minMaxSql = "SELECT NVL(MIN(%s), 0), NVL(MAX(%s), 0) FROM %s".formatted(column, column, testTable);
		long[] testRange = fetchMinMax(testJdbc, minMaxSql);
		String devMinMaxSql = "SELECT NVL(MIN(%s), 0), NVL(MAX(%s), 0) FROM %s".formatted(column, column, devTable);
		long[] devRange = fetchMinMax(devJdbc, devMinMaxSql);

		long minTest = testRange[0];
		long maxTest = testRange[1];
		long minDev = devRange[0];
		long maxDev = devRange[1];

		boolean overlap = minTest <= maxDev && maxTest >= minDev;
		Long lowestCollidingId = null;

		if (overlap) {
			long overlapStart = Math.max(minTest, minDev);
			// INDEX RANGE SCAN sur la PK
			String collisionSql = "SELECT MIN(%s) FROM %s WHERE %s >= ?".formatted(column, testTable, column);
			lowestCollidingId = testJdbc.queryForObject(collisionSql, Long.class, overlapStart);
		}

		return new OverlapResult(tableName, minTest, maxTest, minDev, maxDev, overlap, lowestCollidingId);
	}

	private long[] fetchMinMax(QueryCountingJdbcTemplate jdbc, String sql) {
		return jdbc.query(sql, (rs, rowNum) -> new long[] {
				rs.getLong(1),
				rs.getLong(2)
		}).getFirst();
	}
}
