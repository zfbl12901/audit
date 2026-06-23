package com.fb.audit.model;

import java.time.Instant;

public record AuditJob(
		String id,
		Instant createdAt,
		AuditStatus status,
		String errorMessage,
		GeneratedReports reports
) {

	public static AuditJob running(String id) {
		return new AuditJob(id, Instant.now(), AuditStatus.RUNNING, null, null);
	}

	public AuditJob withDone(GeneratedReports reports) {
		return new AuditJob(id, createdAt, AuditStatus.DONE, null, reports);
	}

	public AuditJob withFailed(String errorMessage) {
		return new AuditJob(id, createdAt, AuditStatus.FAILED, errorMessage, null);
	}
}
