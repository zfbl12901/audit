package com.fb.audit.repo.model;

public record NpmPackageVersion(String name, String version) {

	public NpmPackageVersion {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Le nom du package ne peut pas être vide");
		}
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("La version du package ne peut pas être vide");
		}
	}

	public String cacheKey() {
		return name + "@" + version;
	}
}
