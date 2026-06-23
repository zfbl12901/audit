package com.fb.audit.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfig {

	@Bean(destroyMethod = "shutdown")
	@Qualifier("auditJobExecutor")
	public ExecutorService auditJobExecutor() {
		return Executors.newCachedThreadPool();
	}

	@Bean(destroyMethod = "shutdown")
	@Qualifier("auditAnalysisExecutor")
	public ExecutorService auditAnalysisExecutor(AuditProperties properties) {
		return Executors.newFixedThreadPool(properties.parallelThreads());
	}
}
