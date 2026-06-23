package com.fb.audit.dto;

import com.fb.audit.model.AuditStatus;

public record AuditCreatedResponse(String auditId, AuditStatus status) {
}
