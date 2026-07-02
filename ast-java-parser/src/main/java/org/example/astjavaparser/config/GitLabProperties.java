package org.example.astjavaparser.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gitlab")
public class GitLabProperties {
    private String baseUrl = "https://gitlab.com/api/v4";
    private String token = "gitlab-pat";
    private String cloneBaseDir = "./tmp/gitlab-repos";
    private long cloneRetentionMs = 1800000;
    private long cleanupIntervalMs = 300000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getCloneBaseDir() {
        return cloneBaseDir;
    }

    public void setCloneBaseDir(String cloneBaseDir) {
        this.cloneBaseDir = cloneBaseDir;
    }

    public long getCloneRetentionMs() {
        return cloneRetentionMs;
    }

    public void setCloneRetentionMs(long cloneRetentionMs) {
        this.cloneRetentionMs = cloneRetentionMs;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }
}
