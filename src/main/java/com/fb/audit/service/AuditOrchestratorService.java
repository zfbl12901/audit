package com.fb.audit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fb.audit.config.AuditProperties;
import com.fb.audit.model.AuditJob;
import com.fb.audit.model.AuditReport;
import com.fb.audit.model.AuditStatus;
import com.fb.audit.model.ForeignKeyInfo;
import com.fb.audit.model.GeneratedReports;
import com.fb.audit.model.OrphanFkResult;
import com.fb.audit.model.OverlapResult;
import com.fb.audit.model.PkShape;
import com.fb.audit.model.WarningEntry;
import com.fb.audit.util.IdentifierValidator;
import com.fb.audit.util.QueryCountingJdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;

@Service
public class AuditOrchestratorService {

	private static final Logger log = LoggerFactory.getLogger(AuditOrchestratorService.class);

	private final DynamicDataSourceFactory dataSourceFactory;
	private final TableValidatorService tableValidatorService;
	private final SchemaDiscoveryService schemaDiscoveryService;
	private final OverlapAnalysisService overlapAnalysisService;
	private final OrphanFkAnalysisService orphanFkAnalysisService;
	private final ReportGeneratorService reportGeneratorService;
	private final AuditJobRegistry jobRegistry;
	private final AuditProperties auditProperties;
	private final ExecutorService auditJobExecutor;
	private final ExecutorService auditAnalysisExecutor;

	public AuditOrchestratorService(
			DynamicDataSourceFactory dataSourceFactory,
			TableValidatorService tableValidatorService,
			SchemaDiscoveryService schemaDiscoveryService,
			OverlapAnalysisService overlapAnalysisService,
			OrphanFkAnalysisService orphanFkAnalysisService,
			ReportGeneratorService reportGeneratorService,
			AuditJobRegistry jobRegistry,
			AuditProperties auditProperties,
			@Qualifier("auditJobExecutor") ExecutorService auditJobExecutor,
			@Qualifier("auditAnalysisExecutor") ExecutorService auditAnalysisExecutor) {
		this.dataSourceFactory = dataSourceFactory;
		this.tableValidatorService = tableValidatorService;
		this.schemaDiscoveryService = schemaDiscoveryService;
		this.overlapAnalysisService = overlapAnalysisService;
		this.orphanFkAnalysisService = orphanFkAnalysisService;
		this.reportGeneratorService = reportGeneratorService;
		this.jobRegistry = jobRegistry;
		this.auditProperties = auditProperties;
		this.auditJobExecutor = auditJobExecutor;
		this.auditAnalysisExecutor = auditAnalysisExecutor;
	}

	public AuditJob startAudit() {
		AuditJob job = jobRegistry.createJob();
		List<String> tableNames = IdentifierValidator.normalizeTableNames(auditProperties.tableNames());
		auditJobExecutor.submit(() -> runAudit(job.id(), tableNames));
		return job;
	}

	private void runAudit(String jobId, List<String> tableNames) {
		DataSource testDs = null;
		DataSource devDs = null;
		try {
			testDs = dataSourceFactory.createDataSource(auditProperties.testDb());
			devDs = dataSourceFactory.createDataSource(auditProperties.devDb());

			AtomicInteger queryCount = new AtomicInteger();
			QueryCountingJdbcTemplate testJdbc = new QueryCountingJdbcTemplate(
					dataSourceFactory.createJdbcTemplate(testDs), queryCount);
			QueryCountingJdbcTemplate devJdbc = new QueryCountingJdbcTemplate(
					dataSourceFactory.createJdbcTemplate(devDs), queryCount);

			String testSchema = IdentifierValidator.normalizeSchema(auditProperties.testDb().schema());
			String devSchema = IdentifierValidator.normalizeSchema(auditProperties.devDb().schema());

			tableValidatorService.validateTablesExist(testJdbc, devJdbc, testSchema, devSchema, tableNames);

			Map<String, PkShape> testPks = schemaDiscoveryService.discoverPrimaryKeys(testJdbc, testSchema, tableNames);
			Map<String, List<ForeignKeyInfo>> fksByTable =
					schemaDiscoveryService.discoverForeignKeys(testJdbc, testSchema, tableNames);

			List<WarningEntry> warnings = new CopyOnWriteArrayList<>();
			List<OverlapResult> overlaps = new CopyOnWriteArrayList<>();
			List<OrphanFkResult> orphanFks = new CopyOnWriteArrayList<>();

			Set<String> exportScope = Set.copyOf(tableNames);

			List<CompletableFuture<Void>> futures = new ArrayList<>();

			for (String table : tableNames) {
				PkShape pk = testPks.get(table);
				if (pk == null) {
					warnings.add(new WarningEntry(table, "Aucune clé primaire détectée — analyse d'empiètement ignorée"));
				} else if (pk.isComposite()) {
					warnings.add(new WarningEntry(table, "Clé primaire composite — analyse d'empiètement ignorée"));
				} else if (!pk.numeric()) {
					warnings.add(new WarningEntry(table, "Clé primaire non numérique — analyse d'empiètement ignorée"));
				} else {
					PkShape pkFinal = pk;
					futures.add(CompletableFuture.runAsync(() -> {
						OverlapResult result = overlapAnalysisService.analyze(
								testJdbc, devJdbc, testSchema, devSchema, table, pkFinal);
						overlaps.add(result);
					}, auditAnalysisExecutor));
				}

				for (ForeignKeyInfo fk : fksByTable.getOrDefault(table, List.of())) {
					futures.add(CompletableFuture.runAsync(() -> {
						OrphanFkResult result = orphanFkAnalysisService.analyze(
								testJdbc, devJdbc, testSchema, devSchema,
								fk.childTable(), fk.fkColumn(), fk.parentTable(), fk.parentColumn(),
								testPks, exportScope, warnings);
						if (result.totalOrphanCount() > 0) {
							orphanFks.add(result);
						}
					}, auditAnalysisExecutor));
				}
			}

			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

			int totalQueries = queryCount.get();
			log.info("Audit {} terminé — {} requêtes SQL exécutées", jobId, totalQueries);

			AuditReport report = new AuditReport(
					List.copyOf(overlaps), List.copyOf(orphanFks), List.copyOf(warnings), totalQueries);
			GeneratedReports generated = reportGeneratorService.generate(report);
			jobRegistry.updateJob(jobId, job -> job.withDone(generated));
		} catch (Exception e) {
			log.error("Échec de l'audit {}", jobId, e);
			String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			jobRegistry.updateJob(jobId, job -> job.withFailed(message));
		} finally {
			closeDataSource(testDs);
			closeDataSource(devDs);
		}
	}

	private void closeDataSource(DataSource dataSource) {
		if (dataSource instanceof HikariDataSource hikari) {
			hikari.close();
		}
	}
}
