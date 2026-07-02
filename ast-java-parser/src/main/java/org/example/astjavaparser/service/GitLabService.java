package org.example.astjavaparser.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.example.astjavaparser.config.GitLabProperties;
import org.example.astjavaparser.model.GitLabCloneResult;
import org.example.astjavaparser.model.GitLabProjectDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class GitLabService {
    private static final Logger logger = LoggerFactory.getLogger(GitLabService.class);
    private static final int DELETE_RETRY_ATTEMPTS = 5;
    private static final long DELETE_RETRY_DELAY_MS = 200L;

    private final WebClient gitlabWebClient;
    private final GitLabProperties gitLabProperties;

    public GitLabService(WebClient gitlabWebClient, GitLabProperties gitLabProperties) {
        this.gitlabWebClient = gitlabWebClient;
        this.gitLabProperties = gitLabProperties;
    }

    @PostConstruct
    void cleanupCloneBaseDirOnStartup() {
        try {
            deleteRecursively(resolveCloneBaseDir());
        } catch (IOException exception) {
            logCleanupFailure("Startup cleanup failed", resolveCloneBaseDir(), exception);
        }
    }

    public List<GitLabProjectDto> getProjects() {
        return gitlabWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/projects")
                        .queryParam("membership", true)
                        .queryParam("per_page", 100)
                        .build())
                .retrieve()
                .bodyToFlux(GitLabProjectDto.class)
                .collectList()
                .block();
    }

    public GitLabCloneResult cloneProject(Long projectId) throws GitAPIException, IOException {
        GitLabProjectDto project = gitlabWebClient.get()
                .uri("/projects/{projectId}", projectId)
                .retrieve()
                .bodyToMono(GitLabProjectDto.class)
                .block();

        if (project == null) {
            throw new IllegalStateException("GitLab project " + projectId + " was not found.");
        }

        Path cloneBaseDir = Paths.get(gitLabProperties.getCloneBaseDir()).toAbsolutePath().normalize();
        Files.createDirectories(cloneBaseDir);

        String namespace = project.getPath_with_namespace();
        Path targetDirectory = cloneBaseDir.resolve(namespace.replace("/", java.io.File.separator)).normalize();

        if (Files.isDirectory(targetDirectory.resolve(".git"))) {
            touchCloneTree(targetDirectory);
            return buildCloneResult(project, targetDirectory, true);
        }
        if (Files.exists(targetDirectory) && hasContent(targetDirectory)) {
            throw new IllegalStateException("Clone target already exists and is not an initialized git repository: " + targetDirectory);
        }

        Files.createDirectories(targetDirectory.getParent());

        Git.cloneRepository()
                .setURI(project.getHttp_url_to_repo())
                .setDirectory(targetDirectory.toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2", gitLabProperties.getToken()))
                .call()
                .close();

        touchCloneTree(targetDirectory);
        return buildCloneResult(project, targetDirectory, false);
    }

    @Scheduled(fixedDelayString = "${gitlab.cleanup-interval-ms:300000}")
    public void cleanupExpiredClones() throws IOException {
        Path cloneBaseDir = resolveCloneBaseDir();
        if (!Files.isDirectory(cloneBaseDir)) {
            return;
        }

        Instant cutoff = Instant.now().minusMillis(gitLabProperties.getCloneRetentionMs());
        try (Stream<Path> stream = Files.walk(cloneBaseDir)) {
            for (Path path : stream
                    .filter(Files::isDirectory)
                    .filter(path -> Files.isDirectory(path.resolve(".git")))
                    .toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                    try {
                        deleteRecursively(path);
                    } catch (IOException exception) {
                        logCleanupFailure("Cleanup failed", path, exception);
                    }
                }
            }
        }
        try {
            pruneEmptyDirectories(cloneBaseDir);
        } catch (IOException exception) {
            logCleanupFailure("Pruning empty clone directories failed", cloneBaseDir, exception);
        }
    }

    @PreDestroy
    void cleanupCloneBaseDirOnShutdown() {
        try {
            deleteRecursively(resolveCloneBaseDir());
        } catch (IOException exception) {
            logCleanupFailure("Cleanup failed during shutdown", resolveCloneBaseDir(), exception);
        }
    }

    private boolean hasContent(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        }
    }

    private GitLabCloneResult buildCloneResult(GitLabProjectDto project, Path targetDirectory, boolean alreadyCloned) {
        return GitLabCloneResult.builder()
                .projectId(project.getId())
                .projectName(project.getName())
                .namespace(project.getPath_with_namespace())
                .repositoryUrl(project.getHttp_url_to_repo())
                .projectWebUrl(project.getWeb_url())
                .defaultBranch(project.getDefault_branch())
                .localPath(targetDirectory.toString())
                .alreadyCloned(alreadyCloned)
                .build();
    }

    private Path resolveCloneBaseDir() {
        return Paths.get(gitLabProperties.getCloneBaseDir()).toAbsolutePath().normalize();
    }

    private void touchCloneTree(Path targetDirectory) throws IOException {
        Instant now = Instant.now();
        FileTime updatedTime = FileTime.from(now);
        Path cloneBaseDir = resolveCloneBaseDir();

        Path current = targetDirectory;
        while (current != null && current.startsWith(cloneBaseDir)) {
            if (Files.exists(current)) {
                Files.setLastModifiedTime(current, updatedTime);
            }
            if (current.equals(cloneBaseDir)) {
                break;
            }
            current = current.getParent();
        }
    }

    private void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }

        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path cloneBaseDir = resolveCloneBaseDir();
        if (!normalizedTarget.equals(cloneBaseDir) && !normalizedTarget.startsWith(cloneBaseDir)) {
            throw new IllegalArgumentException("Refusing to delete outside clone base dir: " + normalizedTarget);
        }

        try (Stream<Path> stream = Files.walk(normalizedTarget)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            deleteWithRetry(path);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private void deleteWithRetry(Path path) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DELETE_RETRY_ATTEMPTS; attempt++) {
            try {
                clearReadOnlyFlag(path);
                Files.deleteIfExists(path);
                return;
            } catch (FileSystemException exception) {
                lastFailure = exception;
                if (attempt == DELETE_RETRY_ATTEMPTS) {
                    break;
                }
                pauseBeforeRetry(path, attempt, exception);
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
    }

    private void clearReadOnlyFlag(Path path) {
        File file = path.toFile();
        if (file.exists() && !file.canWrite()) {
            file.setWritable(true);
        }
    }

    private void pauseBeforeRetry(Path path, int attempt, IOException exception) throws IOException {
        logger.debug("Retrying delete for {} after attempt {}", path, attempt, exception);
        try {
            Thread.sleep(DELETE_RETRY_DELAY_MS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            IOException ioException = new IOException("Interrupted while deleting " + path, interruptedException);
            ioException.addSuppressed(exception);
            throw ioException;
        }
    }

    private void pruneEmptyDirectories(Path cloneBaseDir) throws IOException {
        if (!Files.isDirectory(cloneBaseDir)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(cloneBaseDir)) {
            for (Path path : stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                if (path.equals(cloneBaseDir)) {
                    continue;
                }
                try (Stream<Path> children = Files.list(path)) {
                    if (children.findAny().isEmpty()) {
                        try {
                            deleteWithRetry(path);
                        } catch (IOException exception) {
                            logCleanupFailure("Unable to delete empty directory", path, exception);
                        }
                    }
                }
            }
        }
    }

    private void logCleanupFailure(String context, Path path, IOException exception) {
        logger.warn("{}: {} ({})", context, path, exception.getMessage());
        logger.debug("{} stack trace for {}", context, path, exception);
    }
}
