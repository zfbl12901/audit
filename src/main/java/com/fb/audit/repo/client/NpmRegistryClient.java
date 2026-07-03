package com.fb.audit.repo.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fb.audit.exception.AuditException;
import com.fb.audit.repo.config.RepoProperties;
import com.fb.audit.repo.model.InternalNpmComponent;
import com.fb.audit.repo.model.NpmPackageVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "repo", name = "enabled", havingValue = "true")
public class NpmRegistryClient {

	private final ObjectMapper objectMapper;

	public NpmRegistryClient(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<InternalNpmComponent> listInternalComponents(RepoProperties.NpmRegistryProperties registry) {
		RestClient client = buildNexusClient(registry);
		List<InternalNpmComponent> components = new ArrayList<>();
		String continuationToken = null;

		do {
			String token = continuationToken;
			String response = client.get()
					.uri(uriBuilder -> {
						var builder = uriBuilder
								.path("/service/rest/v1/components")
								.queryParam("repository", registry.repositoryName());
						if (token != null && !token.isBlank()) {
							builder.queryParam("continuationToken", token);
						}
						return builder.build();
					})
					.retrieve()
					.body(String.class);

			try {
				JsonNode root = objectMapper.readTree(response);
				for (JsonNode item : root.path("items")) {
					String name = item.path("name").asText(null);
					String version = item.path("version").asText(null);
					String id = item.path("id").asText(null);
					if (name != null && version != null && id != null) {
						components.add(new InternalNpmComponent(id, name, version));
					}
				}
				JsonNode tokenNode = root.get("continuationToken");
				continuationToken = tokenNode != null && !tokenNode.isNull() ? tokenNode.asText(null) : null;
			} catch (Exception e) {
				throw new AuditException("Impossible de parser la liste internal-npm : " + e.getMessage(), e);
			}
		} while (continuationToken != null && !continuationToken.isBlank());

		return components;
	}

	public byte[] downloadTarball(RepoProperties.NpmRegistryProperties registry, NpmPackageVersion pkg) {
		RestClient client = buildRegistryClient(registry);
		String tarballUrl = resolveTarballUrl(client, registry.baseUrl(), pkg);

		try {
			return RestClient.create()
					.get()
					.uri(URI.create(tarballUrl))
					.retrieve()
					.body(byte[].class);
		} catch (RestClientResponseException e) {
			throw new AuditException("Échec du téléchargement de " + pkg.cacheKey()
					+ " depuis external-npm : " + e.getStatusCode());
		}
	}

	public void uploadTarball(
			RepoProperties.NpmRegistryProperties registry,
			NpmPackageVersion pkg,
			byte[] tarball) {

		RestClient client = buildNexusClient(registry);
		String filename = tarballFilename(pkg);
		ByteArrayResource resource = new ByteArrayResource(tarball) {
			@Override
			public String getFilename() {
				return filename;
			}
		};

		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("npm.asset", resource);

		try {
			client.post()
					.uri(uriBuilder -> uriBuilder
							.path("/service/rest/v1/components")
							.queryParam("repository", registry.repositoryName())
							.build())
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException e) {
			throw new AuditException("Échec de l'upload de " + pkg.cacheKey()
					+ " vers internal-npm : " + e.getStatusCode() + " " + e.getResponseBodyAsString());
		}
	}

	public void deleteComponent(RepoProperties.NpmRegistryProperties registry, String componentId) {
		RestClient client = buildNexusClient(registry);
		try {
			client.delete()
					.uri("/service/rest/v1/components/{id}", componentId)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException e) {
			throw new AuditException("Échec de la suppression du composant " + componentId
					+ " : " + e.getStatusCode());
		}
	}

	public boolean existsOnRegistry(RepoProperties.NpmRegistryProperties registry, NpmPackageVersion pkg) {
		return resolveTarballUrlOptional(buildRegistryClient(registry), registry.baseUrl(), pkg).isPresent();
	}

	private Optional<String> resolveTarballUrlOptional(
			RestClient client, String registryBaseUrl, NpmPackageVersion pkg) {

		try {
			return Optional.of(resolveTarballUrl(client, registryBaseUrl, pkg));
		} catch (AuditException e) {
			return Optional.empty();
		}
	}

	private String resolveTarballUrl(RestClient client, String registryBaseUrl, NpmPackageVersion pkg) {
		String encodedName = encodePackageName(pkg.name());
		try {
			String response = client.get()
					.uri("/{packageName}", encodedName)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(String.class);

			JsonNode root = objectMapper.readTree(response);
			JsonNode versionNode = root.path("versions").path(pkg.version());
			if (versionNode.isMissingNode()) {
				throw new AuditException("Version " + pkg.version() + " introuvable pour " + pkg.name()
						+ " sur " + registryBaseUrl);
			}
			String tarball = versionNode.path("dist").path("tarball").asText(null);
			if (tarball == null || tarball.isBlank()) {
				throw new AuditException("Tarball introuvable pour " + pkg.cacheKey());
			}
			return tarball;
		} catch (RestClientResponseException e) {
			throw new AuditException("Métadonnées introuvables pour " + pkg.cacheKey()
					+ " : " + e.getStatusCode());
		} catch (AuditException e) {
			throw e;
		} catch (Exception e) {
			throw new AuditException("Impossible de résoudre le tarball pour " + pkg.cacheKey(), e);
		}
	}

	private RestClient buildNexusClient(RepoProperties.NpmRegistryProperties registry) {
		return RestClient.builder()
				.baseUrl(registry.baseUrl())
				.defaultHeaders(headers -> applyAuth(headers, registry))
				.build();
	}

	private RestClient buildRegistryClient(RepoProperties.NpmRegistryProperties registry) {
		return RestClient.builder()
				.baseUrl(registry.baseUrl())
				.defaultHeaders(headers -> {
					applyAuth(headers, registry);
					headers.setAccept(List.of(MediaType.APPLICATION_JSON));
				})
				.build();
	}

	private void applyAuth(HttpHeaders headers, RepoProperties.NpmRegistryProperties registry) {
		if (registry.username() != null && !registry.username().isBlank()) {
			String credentials = registry.username() + ":" + registry.password();
			String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
			headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
		}
	}

	private String encodePackageName(String name) {
		if (name.startsWith("@")) {
			int slash = name.indexOf('/');
			if (slash < 0) {
				throw new IllegalArgumentException("Nom de package scopé invalide : " + name);
			}
			return "@" + name.substring(1, slash) + "%2F" + name.substring(slash + 1);
		}
		return name;
	}

	private String tarballFilename(NpmPackageVersion pkg) {
		String baseName = pkg.name().startsWith("@")
				? pkg.name().substring(1).replace("/", "-")
				: pkg.name();
		return baseName + "-" + pkg.version() + ".tgz";
	}
}
