package com.fb.audit.repo.model;

import java.util.List;

public record RepoRefreshResult(
		List<String> angularProjects,
		int requiredPackageCount,
		int internalPackageCount,
		List<NpmPackageVersion> uploaded,
		List<NpmPackageVersion> deleted,
		List<NpmPackageVersion> skippedProtected,
		List<String> warnings,
		List<String> errors
) {
}
