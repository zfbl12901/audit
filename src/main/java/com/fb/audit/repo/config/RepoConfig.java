package com.fb.audit.repo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "repo", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RepoProperties.class)
public class RepoConfig {
}
