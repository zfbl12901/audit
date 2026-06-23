package com.fb.audit.util;

import java.util.ArrayList;
import java.util.List;

public final class BatchUtils {

	private BatchUtils() {
	}

	public static <T> List<List<T>> partition(List<T> items, int batchSize) {
		if (items.isEmpty()) {
			return List.of();
		}
		List<List<T>> batches = new ArrayList<>();
		for (int i = 0; i < items.size(); i += batchSize) {
			batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
		}
		return batches;
	}
}
