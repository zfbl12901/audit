package com.fb.audit.repo.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fb.audit.repo.model.NpmPackageVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "repo", name = "enabled", havingValue = "true")
public class PackageLockParser {

	private final ObjectMapper objectMapper;

	public PackageLockParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public Set<NpmPackageVersion> parse(String lockFileContent) {
		try {
			JsonNode root = objectMapper.readTree(lockFileContent);
			Set<NpmPackageVersion> packages = new LinkedHashSet<>();
			if (root.has("lockfileVersion") && root.get("lockfileVersion").asInt() >= 1 && root.has("packages")) {
				parseLockfileV2Plus(root.get("packages"), packages);
			} else if (root.has("dependencies")) {
				parseLockfileV1(root.get("dependencies"), packages);
			} else {
				throw new IllegalArgumentException("Format package-lock.json non reconnu");
			}
			return packages;
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("Impossible de parser package-lock.json : " + e.getMessage(), e);
		}
	}

	private void parseLockfileV2Plus(JsonNode packagesNode, Set<NpmPackageVersion> packages) {
		Map<String, JsonNode> fields = new LinkedHashMap<>();
		for (var entry : packagesNode.properties()) {
			fields.put(entry.getKey(), entry.getValue());
		}

		for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
			JsonNode pkg = entry.getValue();
			if (!pkg.has("version")) {
				continue;
			}
			String version = pkg.get("version").asText();
			if (version.isBlank()) {
				continue;
			}
			String name = pkg.has("name") ? pkg.get("name").asText() : nameFromPackagesKey(entry.getKey());
			if (name == null || name.isBlank()) {
				continue;
			}
			packages.add(new NpmPackageVersion(name, version));
		}
	}

	private void parseLockfileV1(JsonNode dependenciesNode, Set<NpmPackageVersion> packages) {
		for (var entry : dependenciesNode.properties()) {
			String name = entry.getKey();
			JsonNode dep = entry.getValue();
			if (dep.has("version")) {
				packages.add(new NpmPackageVersion(name, dep.get("version").asText()));
			}
			if (dep.has("dependencies")) {
				parseLockfileV1(dep.get("dependencies"), packages);
			}
		}
	}

	private String nameFromPackagesKey(String key) {
		if (key == null || key.isBlank() || key.equals("node_modules")) {
			return null;
		}
		int idx = key.lastIndexOf("node_modules/");
		if (idx >= 0) {
			return key.substring(idx + "node_modules/".length());
		}
		return key;
	}
}
