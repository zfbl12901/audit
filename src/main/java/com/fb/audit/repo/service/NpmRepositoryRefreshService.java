package com.fb.audit.repo.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fb.audit.repo.client.GitLabClient;
import com.fb.audit.repo.client.NpmRegistryClient;
import com.fb.audit.repo.config.RepoProperties;
import com.fb.audit.repo.model.GitLabProject;
import com.fb.audit.repo.model.InternalNpmComponent;
import com.fb.audit.repo.model.NpmPackageVersion;
import com.fb.audit.repo.model.RepoRefreshResult;

@Service
@ConditionalOnProperty(prefix = "repo", name = "enabled", havingValue = "true")
public class NpmRepositoryRefreshService {

	private static final Logger log = LoggerFactory.getLogger(NpmRepositoryRefreshService.class);

	private final RepoProperties properties;
	private final GitLabClient gitLabClient;
	private final NpmRegistryClient npmRegistryClient;
	private final PackageLockParser packageLockParser;

	public NpmRepositoryRefreshService(
			RepoProperties properties,
			GitLabClient gitLabClient,
			NpmRegistryClient npmRegistryClient,
			PackageLockParser packageLockParser) {
		this.properties = properties;
		this.gitLabClient = gitLabClient;
		this.npmRegistryClient = npmRegistryClient;
		this.packageLockParser = packageLockParser;
	}

	public RepoRefreshResult refresh() {
		List<String> angularProjects = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		Set<NpmPackageVersion> requiredPackages = new HashSet<>();

		for (GitLabProject project : gitLabClient.listGroupProjects()) {
			if (!gitLabClient.isAngularProject(project)) {
				continue;
			}
			angularProjects.add(project.pathWithNamespace());

			gitLabClient.fetchLockFile(project).ifPresentOrElse(
					lockContent -> requiredPackages.addAll(packageLockParser.parse(lockContent)),
					() -> warnings.add("package-lock.json absent pour " + project.pathWithNamespace()));
		}

		log.info("{} projet(s) Angular détecté(s), {} dépendance(s) requise(s)",
				angularProjects.size(), requiredPackages.size());

		Map<String, InternalNpmComponent> internalByKey = new LinkedHashMap<>();
		for (InternalNpmComponent component : npmRegistryClient.listInternalComponents(properties.internalNpm())) {
			internalByKey.put(cacheKey(component.name(), component.version()), component);
		}

		List<NpmPackageVersion> uploaded = new ArrayList<>();
		for (NpmPackageVersion required : requiredPackages) {
			if (internalByKey.containsKey(required.cacheKey())) {
				continue;
			}
			try {
				byte[] tarball = npmRegistryClient.downloadTarball(properties.externalNpm(), required);
				npmRegistryClient.uploadTarball(properties.internalNpm(), required, tarball);
				uploaded.add(required);
				log.info("Uploadé sur internal-npm : {}", required.cacheKey());
			} catch (Exception e) {
				errors.add(required.cacheKey() + " : " + e.getMessage());
				log.error("Échec sync {}", required.cacheKey(), e);
			}
		}

		Set<String> requiredKeys = requiredPackages.stream()
				.map(NpmPackageVersion::cacheKey)
				.collect(Collectors.toSet());

		List<NpmPackageVersion> deleted = new ArrayList<>();
		List<NpmPackageVersion> skippedProtected = new ArrayList<>();

		for (InternalNpmComponent internal : internalByKey.values()) {
			String key = cacheKey(internal.name(), internal.version());
			if (requiredKeys.contains(key)) {
				continue;
			}
			if (isProtected(internal.name())) {
				skippedProtected.add(new NpmPackageVersion(internal.name(), internal.version()));
				continue;
			}
			try {
				npmRegistryClient.deleteComponent(properties.internalNpm(), internal.id());
				deleted.add(new NpmPackageVersion(internal.name(), internal.version()));
				log.info("Supprimé de internal-npm : {}", key);
			} catch (Exception e) {
				errors.add("suppression " + key + " : " + e.getMessage());
				log.error("Échec suppression {}", key, e);
			}
		}

		return new RepoRefreshResult(
				List.copyOf(angularProjects),
				requiredPackages.size(),
				internalByKey.size(),
				List.copyOf(uploaded),
				List.copyOf(deleted),
				List.copyOf(skippedProtected),
				List.copyOf(warnings),
				List.copyOf(errors));
	}

	private boolean isProtected(String packageName) {
		String lower = packageName.toLowerCase(Locale.ROOT);
		return properties.protectedKeywords().stream()
				.anyMatch(keyword -> lower.contains(keyword.toLowerCase(Locale.ROOT)));
	}

	private String cacheKey(String name, String version) {
		return name + "@" + version;
	}
}
