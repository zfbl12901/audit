package com.fb.audit.service;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fb.audit.config.AuditProperties;
import com.fb.audit.model.DatabaseConnectionInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Component
public class DynamicDataSourceFactory {

	private final AuditProperties properties;

	public DynamicDataSourceFactory(AuditProperties properties) {
		this.properties = properties;
	}

	public DataSource createDataSource(DatabaseConnectionInfo connectionInfo) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(connectionInfo.jdbcUrl());
		config.setUsername(connectionInfo.username());
		config.setPassword(connectionInfo.password());
		config.setReadOnly(true);
		config.setMaximumPoolSize(properties.poolSize());
		config.setMinimumIdle(2);
		config.setConnectionTimeout(properties.connectionTimeoutMs());
		config.setPoolName("audit-" + connectionInfo.schema());
		return new HikariDataSource(config);
	}

	public JdbcTemplate createJdbcTemplate(DataSource dataSource) {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.setQueryTimeout(120);
		return jdbcTemplate;
	}
}
