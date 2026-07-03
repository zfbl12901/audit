package com.fb.audit.repo.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fb.audit.repo.model.RepoRefreshResult;
import com.fb.audit.repo.service.NpmRepositoryRefreshService;

@RestController
@RequestMapping("/repo")
@ConditionalOnProperty(prefix = "repo", name = "enabled", havingValue = "true")
public class RepoRefreshController {

	private final NpmRepositoryRefreshService refreshService;

	public RepoRefreshController(NpmRepositoryRefreshService refreshService) {
		this.refreshService = refreshService;
	}

	@PostMapping("/refresh")
	@ResponseStatus(HttpStatus.OK)
	public RepoRefreshResult refresh() {
		return refreshService.refresh();
	}
}
