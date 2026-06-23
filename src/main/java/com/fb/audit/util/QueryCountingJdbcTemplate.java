package com.fb.audit.util;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class QueryCountingJdbcTemplate {

	private final JdbcTemplate delegate;
	private final AtomicInteger queryCount;

	public QueryCountingJdbcTemplate(JdbcTemplate delegate, AtomicInteger queryCount) {
		this.delegate = delegate;
		this.queryCount = queryCount;
	}

	public JdbcTemplate delegate() {
		return delegate;
	}

	public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
		queryCount.incrementAndGet();
		return delegate.query(sql, rowMapper, args);
	}

	public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
		queryCount.incrementAndGet();
		return delegate.queryForObject(sql, requiredType, args);
	}

	public int queryCount() {
		return queryCount.get();
	}
}
