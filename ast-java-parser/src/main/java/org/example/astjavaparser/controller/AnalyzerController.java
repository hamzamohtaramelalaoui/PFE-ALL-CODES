package org.example.astjavaparser.controller;

import org.example.astjavaparser.model.ConfigurationPropertiesAnalysis;
import org.example.astjavaparser.model.FullRepositoryAnalysis;
import org.example.astjavaparser.model.RepositoryAnalysis;
import org.example.astjavaparser.service.CodeParserService;
import org.example.astjavaparser.service.ConfigurationPropertiesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/analyze")
public class AnalyzerController {

    private final CodeParserService parserService;
    private final ConfigurationPropertiesService configurationPropertiesService;

    public AnalyzerController(CodeParserService parserService,
                              ConfigurationPropertiesService configurationPropertiesService) {
        this.parserService = parserService;
        this.configurationPropertiesService = configurationPropertiesService;
    }

    @GetMapping("/repository")
    public RepositoryAnalysis analyzeRepository(@RequestParam(value = "path", required = false) String path) throws IOException {
        return parserService.parseRepository(resolveRepositoryRoot(path));
    }

    @GetMapping("/config-properties")
    public ConfigurationPropertiesAnalysis analyzeRepositoryProperties(@RequestParam(value = "path", required = false) String path)
            throws IOException {
        return configurationPropertiesService.parseRepositoryProperties(resolveRepositoryRoot(path));
    }

    @GetMapping("/full")
    public FullRepositoryAnalysis analyzeRepositoryAndProperties(@RequestParam(value = "path", required = false) String path)
            throws IOException {
        Path repositoryRoot = resolveRepositoryRoot(path);
        return FullRepositoryAnalysis.builder()
                .repositoryAnalysis(parserService.parseRepository(repositoryRoot))
                .configurationAnalysis(configurationPropertiesService.parseRepositoryProperties(repositoryRoot))
                .build();
    }

    private Path resolveRepositoryRoot(String path) {
        return path == null || path.isBlank()
                ? Paths.get("").toAbsolutePath().normalize()
                : Paths.get(path).toAbsolutePath().normalize();
    }
}
