package com.fb.audit.repo.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fb.audit.repo.model.NpmPackageVersion;
import tools.jackson.databind.ObjectMapper;

class PackageLockParserTest {

	private PackageLockParser parser;

	@BeforeEach
	void setUp() {
		parser = new PackageLockParser(new ObjectMapper());
	}

	@Test
	void parseLockfileV3() {
		String lock = """
				{
				  "lockfileVersion": 3,
				  "packages": {
				    "": { "name": "demo-app", "version": "1.0.0" },
				    "node_modules/lodash": { "version": "4.17.21" },
				    "node_modules/@angular/core": {
				      "name": "@angular/core",
				      "version": "17.3.0"
				    }
				  }
				}
				""";

		Set<NpmPackageVersion> packages = parser.parse(lock);

		assertThat(packages).containsExactlyInAnyOrder(
				new NpmPackageVersion("demo-app", "1.0.0"),
				new NpmPackageVersion("lodash", "4.17.21"),
				new NpmPackageVersion("@angular/core", "17.3.0"));
	}

	@Test
	void parseLockfileV1() {
		String lock = """
				{
				  "dependencies": {
				    "rxjs": {
				      "version": "7.8.1",
				      "dependencies": {
				        "tslib": { "version": "2.6.2" }
				      }
				    }
				  }
				}
				""";

		Set<NpmPackageVersion> packages = parser.parse(lock);

		assertThat(packages).containsExactlyInAnyOrder(
				new NpmPackageVersion("rxjs", "7.8.1"),
				new NpmPackageVersion("tslib", "2.6.2"));
	}
}
