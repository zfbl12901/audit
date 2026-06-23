package com.fb.audit.model;

import jakarta.validation.constraints.NotBlank;

public record DatabaseConnectionInfo(
		@NotBlank String jdbcUrl,
		@NotBlank String username,
		@NotBlank String password,
		@NotBlank String schema
) {
}
