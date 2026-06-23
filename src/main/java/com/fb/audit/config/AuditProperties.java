package com.fb.audit.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.fb.audit.model.DatabaseConnectionInfo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(
		@NotNull @Valid DatabaseConnectionInfo testDb,
		@NotNull @Valid DatabaseConnectionInfo devDb,
		@NotEmpty List<String> tableNames,
		@DefaultValue("12") int poolSize,
		@DefaultValue("12") int parallelThreads,
		@DefaultValue("5000") int connectionTimeoutMs,
		@DefaultValue("500") int maxOrphanDetailsPerTable,
		@DefaultValue("1000") int inClauseBatchSize
) {
}
