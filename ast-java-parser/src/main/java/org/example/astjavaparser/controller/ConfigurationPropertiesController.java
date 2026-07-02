package org.example.astjavaparser.controller;

import org.example.astjavaparser.model.ConfigurationPropertiesAnalysis;
import org.example.astjavaparser.service.ConfigurationPropertiesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/config")
public class ConfigurationPropertiesController {

    private final ConfigurationPropertiesService configurationPropertiesService;

    public ConfigurationPropertiesController(ConfigurationPropertiesService configurationPropertiesService) {
        this.configurationPropertiesService = configurationPropertiesService;
    }

    @GetMapping("/properties")
    public ConfigurationPropertiesAnalysis analyzeRepositoryProperties(@RequestParam(value = "path", required = false) String path)
            throws IOException {
        Path repositoryRoot = path == null || path.isBlank()
                ? Paths.get("").toAbsolutePath().normalize()
                : Paths.get(path).toAbsolutePath().normalize();

        return configurationPropertiesService.parseRepositoryProperties(repositoryRoot);
    }
}
