package com.fb.audit.dto;

import com.fb.audit.model.AuditStatus;

public record AuditStatusResponse(String auditId, AuditStatus status, String errorMessage) {
}
