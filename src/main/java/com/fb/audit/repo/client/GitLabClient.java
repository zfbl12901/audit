package com.fb.audit.repo.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fb.audit.exception.AuditException;
import com.fb.audit.repo.config.RepoProperties;
import com.fb.audit.repo.model.GitLabProject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "repo", name = "enabled", havingValue = "true")
public class GitLabClient {

	private final RepoProperties properties;
	private final RestClient restClient;
	private final ObjectMapper objectMapper;

	public GitLabClient(RepoProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.restClient = RestClient.builder()
				.baseUrl(properties.gitlab().baseUrl())
				.defaultHeader("PRIVATE-TOKEN", properties.gitlab().token())
				.build();
	}

	public List<GitLabProject> listGroupProjects() {
		List<GitLabProject> projects = new ArrayList<>();
		int page = 1;
		while (true) {
			List<GitLabProject> batch = fetchProjectsPage(page);
			if (batch.isEmpty()) {
				break;
			}
			projects.addAll(batch);
			page++;
		}
		return projects;
	}

	public boolean isAngularProject(GitLabProject project) {
		String branch = resolveBranch(project);
		if (fileExists(project.id(), "angular.json", branch)) {
			return true;
		}
		return fetchPackageJson(project, branch)
				.map(this::containsAngularDependency)
				.orElse(false);
	}

	public Optional<String> fetchLockFile(GitLabProject project) {
		String branch = resolveBranch(project);
		return fetchRawFile(project.id(), properties.lockFileName(), branch);
	}

	private List<GitLabProject> fetchProjectsPage(int page) {
		try {
			String response = restClient.get()
					.uri(uriBuilder -> {
						var builder = uriBuilder
								.path("/api/v4/groups/{groupId}/projects")
								.queryParam("per_page", 100)
								.queryParam("page", page)
								.queryParam("include_subgroups", properties.gitlab().includeSubgroups());
						String topic = properties.gitlab().angularTopic();
						if (topic != null && !topic.isBlank()) {
							builder.queryParam("topic", topic);
						}
						return builder.build(properties.gitlab().groupId());
					})
					.retrieve()
					.body(String.class);

			JsonNode root = objectMapper.readTree(response);
			if (!root.isArray() || root.isEmpty()) {
				return List.of();
			}
			List<GitLabProject> projects = new ArrayList<>();
			for (JsonNode node : root) {
				projects.add(new GitLabProject(
						node.get("id").asLong(),
						node.get("path_with_namespace").asText(),
						node.path("default_branch").asText(null)));
			}
			return projects;
		} catch (RestClientResponseException e) {
			throw new AuditException("Échec de la récupération des projets GitLab (page " + page + ") : "
					+ e.getStatusCode() + " " + e.getStatusText());
		} catch (Exception e) {
			throw new AuditException("Échec de la récupération des projets GitLab : " + e.getMessage(), e);
		}
	}

	private Optional<String> fetchPackageJson(GitLabProject project, String branch) {
		return fetchRawFile(project.id(), "package.json", branch);
	}

	private boolean containsAngularDependency(String packageJson) {
		try {
			JsonNode root = objectMapper.readTree(packageJson);
			return hasAngularInDeps(root.get("dependencies"))
					|| hasAngularInDeps(root.get("devDependencies"))
					|| hasAngularInDeps(root.get("peerDependencies"));
		} catch (Exception e) {
			return false;
		}
	}

	private boolean hasAngularInDeps(JsonNode deps) {
		if (deps == null || !deps.isObject()) {
			return false;
		}
		for (var entry : deps.properties()) {
			if (entry.getKey().startsWith("@angular/")) {
				return true;
			}
		}
		return false;
	}

	private boolean fileExists(long projectId, String filePath, String branch) {
		return fetchRawFile(projectId, filePath, branch).isPresent();
	}

	private Optional<String> fetchRawFile(long projectId, String filePath, String branch) {
		try {
			String content = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/api/v4/projects/{projectId}/repository/files/{filePath}/raw")
							.queryParam("ref", branch)
							.build(projectId, encodeFilePath(filePath)))
					.retrieve()
					.body(String.class);
			return Optional.ofNullable(content);
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().value() == 404) {
				return Optional.empty();
			}
			throw new AuditException("Échec de lecture de " + filePath + " pour le projet " + projectId
					+ " : " + e.getStatusCode());
		}
	}

	private String resolveBranch(GitLabProject project) {
		if (project.defaultBranch() != null && !project.defaultBranch().isBlank()) {
			return project.defaultBranch();
		}
		return properties.defaultBranch();
	}

	private String encodeFilePath(String filePath) {
		return filePath.replace("/", "%2F");
	}
}
