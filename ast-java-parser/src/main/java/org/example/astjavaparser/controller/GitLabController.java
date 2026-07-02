package org.example.astjavaparser.controller;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.example.astjavaparser.model.ConfigurationPropertiesAnalysis;
import org.example.astjavaparser.model.FullRepositoryAnalysis;
import org.example.astjavaparser.model.GitLabCloneResult;
import org.example.astjavaparser.model.GitLabProjectDto;
import org.example.astjavaparser.model.RepositoryAnalysis;
import org.example.astjavaparser.service.CodeParserService;
import org.example.astjavaparser.service.ConfigurationPropertiesService;
import org.example.astjavaparser.service.GitLabService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/gitlab")
public class GitLabController {
    private final GitLabService gitLabService;
    private final CodeParserService codeParserService;
    private final ConfigurationPropertiesService configurationPropertiesService;

    public GitLabController(GitLabService gitLabService,
                            CodeParserService codeParserService,
                            ConfigurationPropertiesService configurationPropertiesService) {
        this.gitLabService = gitLabService;
        this.codeParserService = codeParserService;
        this.configurationPropertiesService = configurationPropertiesService;
    }

    @GetMapping("/projects")
    public List<GitLabProjectDto> getProjects() {
        return gitLabService.getProjects();
    }

    @PostMapping("/projects/{projectId}/clone")
    public GitLabCloneResult cloneProject(@PathVariable Long projectId) throws GitAPIException, IOException {
        return gitLabService.cloneProject(projectId);
    }

    @PostMapping("/projects/{projectId}/analyze")
    public FullRepositoryAnalysis analyzeProject(@PathVariable Long projectId) throws GitAPIException, IOException {
        GitLabCloneResult cloneResult = gitLabService.cloneProject(projectId);
        Path localRepositoryPath = Paths.get(cloneResult.getLocalPath());
        RepositoryAnalysis repositoryAnalysis = codeParserService.parseRepository(localRepositoryPath);
        ConfigurationPropertiesAnalysis configurationAnalysis = configurationPropertiesService.parseRepositoryProperties(localRepositoryPath);
        String repositoryRootUrl = resolveRepositoryRootUrl(cloneResult);
        String localSourceRoot = repositoryAnalysis.getSourceRoot();
        List<String> localResourceRoots = configurationAnalysis.getResourceRoots();

        repositoryAnalysis.setRepositoryRoot(repositoryRootUrl);
        repositoryAnalysis.setSourceRoot(resolveGitLabTreeUrl(cloneResult, localRepositoryPath, localSourceRoot));
        configurationAnalysis.setRepositoryRoot(repositoryRootUrl);
        if (!localResourceRoots.isEmpty()) {
            configurationAnalysis.setResourceRoots(localResourceRoots.stream()
                    .map(resourceRoot -> resolveGitLabTreeUrl(cloneResult, localRepositoryPath, resourceRoot))
                    .toList());
        }

        return FullRepositoryAnalysis.builder()
                .repositoryAnalysis(repositoryAnalysis)
                .configurationAnalysis(configurationAnalysis)
                .build();
    }

    private String resolveRepositoryRootUrl(GitLabCloneResult cloneResult) {
        if (cloneResult.getProjectWebUrl() != null && !cloneResult.getProjectWebUrl().isBlank()) {
            return cloneResult.getProjectWebUrl();
        }
        return cloneResult.getRepositoryUrl();
    }

    private String resolveGitLabTreeUrl(GitLabCloneResult cloneResult, Path localRepositoryPath, String localPath) {
        Path normalizedRepositoryPath = localRepositoryPath.toAbsolutePath().normalize();
        Path normalizedLocalPath = Paths.get(localPath).toAbsolutePath().normalize();
        String relativePath = normalizedRepositoryPath.relativize(normalizedLocalPath).toString().replace('\\', '/');
        if (relativePath.isBlank()) {
            return resolveRepositoryRootUrl(cloneResult);
        }
        return resolveRepositoryRootUrl(cloneResult) + "/-/tree/" + resolveBranch(cloneResult) + "/" + relativePath;
    }

    private String resolveBranch(GitLabCloneResult cloneResult) {
        if (cloneResult.getDefaultBranch() != null && !cloneResult.getDefaultBranch().isBlank()) {
            return cloneResult.getDefaultBranch();
        }
        return "main";
    }
}
