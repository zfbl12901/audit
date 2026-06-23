package com.fb.audit.model;

public record OverlapResult(
		String tableName,
		long minTest,
		long maxTest,
		long minDev,
		long maxDev,
		boolean overlap,
		Long lowestCollidingTestId
) {
}
