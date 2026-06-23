package com.fb.audit.service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Service;

import com.fb.audit.exception.AuditException;
import com.fb.audit.model.AuditJob;

@Service
public class AuditJobRegistry {

	private final ConcurrentHashMap<String, AuditJob> jobs = new ConcurrentHashMap<>();

	public AuditJob createJob() {
		String id = UUID.randomUUID().toString();
		AuditJob job = AuditJob.running(id);
		jobs.put(id, job);
		return job;
	}

	public void updateJob(String auditId, UnaryOperator<AuditJob> updater) {
		jobs.compute(auditId, (id, job) -> {
			if (job == null) {
				throw new AuditException("Audit introuvable : " + auditId);
			}
			return updater.apply(job);
		});
	}

	public AuditJob getRequired(String auditId) {
		return Optional.ofNullable(jobs.get(auditId))
				.orElseThrow(() -> new AuditException("Audit introuvable : " + auditId));
	}
}
