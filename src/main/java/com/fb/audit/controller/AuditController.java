package com.fb.audit.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fb.audit.dto.AuditCreatedResponse;
import com.fb.audit.dto.AuditStatusResponse;
import com.fb.audit.model.AuditJob;
import com.fb.audit.model.AuditStatus;
import com.fb.audit.model.GeneratedReports;
import com.fb.audit.service.AuditOrchestratorService;
import com.fb.audit.service.AuditJobRegistry;

@RestController
@RequestMapping("/audit")
public class AuditController {

	private final AuditOrchestratorService orchestratorService;
	private final AuditJobRegistry jobRegistry;

	public AuditController(AuditOrchestratorService orchestratorService, AuditJobRegistry jobRegistry) {
		this.orchestratorService = orchestratorService;
		this.jobRegistry = jobRegistry;
	}

	@PostMapping
	public ResponseEntity<AuditCreatedResponse> startAudit() {
		AuditJob job = orchestratorService.startAudit();
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(new AuditCreatedResponse(job.id(), AuditStatus.RUNNING));
	}

	@GetMapping("/{id}/status")
	public AuditStatusResponse getStatus(@PathVariable("id") String auditId) {
		AuditJob job = jobRegistry.getRequired(auditId);
		return new AuditStatusResponse(job.id(), job.status(), job.errorMessage());
	}

	@GetMapping("/{id}/report.xlsx")
	public ResponseEntity<byte[]> getExcelReport(@PathVariable("id") String auditId) {
		AuditJob job = jobRegistry.getRequired(auditId);
		if (job.status() != AuditStatus.DONE) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
		GeneratedReports reports = job.reports();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-" + auditId + ".xlsx")
				.contentType(MediaType.parseMediaType(
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
				.body(reports.excel());
	}

	@GetMapping("/{id}/report.html")
	public ResponseEntity<byte[]> getHtmlReport(@PathVariable("id") String auditId) {
		AuditJob job = jobRegistry.getRequired(auditId);
		if (job.status() != AuditStatus.DONE) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
		GeneratedReports reports = job.reports();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=audit-" + auditId + ".html")
				.contentType(MediaType.TEXT_HTML)
				.body(reports.html());
	}
}
