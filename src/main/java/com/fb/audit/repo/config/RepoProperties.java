package com.fb.audit.repo.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "repo")
public record RepoProperties(
		@NotNull @Valid GitLabProperties gitlab,
		@NotNull @Valid NpmRegistryProperties internalNpm,
		@NotNull @Valid NpmRegistryProperties externalNpm,
		@DefaultValue("main") String defaultBranch,
		@DefaultValue({"eisec", "fwk"}) List<String> protectedKeywords,
		@DefaultValue("package-lock.json") String lockFileName
) {

	@Validated
	public record GitLabProperties(
			@NotBlank String baseUrl,
			@NotBlank String token,
			@NotBlank String groupId,
			String angularTopic,
			@DefaultValue("true") boolean includeSubgroups
	) {
		public GitLabProperties {
			if (baseUrl.endsWith("/")) {
				baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
			}
		}
	}

	@Validated
	public record NpmRegistryProperties(
			@NotBlank String baseUrl,
			String username,
			String password,
			@NotBlank String repositoryName
	) {
		public NpmRegistryProperties {
			if (baseUrl.endsWith("/")) {
				baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
			}
		}
	}
}
