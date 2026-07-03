package com.fb.audit.repo.model;

public record GitLabProject(long id, String pathWithNamespace, String defaultBranch) {
}
