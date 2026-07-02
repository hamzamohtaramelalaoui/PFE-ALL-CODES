package org.example.astjavaparser.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GitLabCloneResult {
    private Long projectId;
    private String projectName;
    private String namespace;
    private String repositoryUrl;
    private String projectWebUrl;
    private String defaultBranch;
    private String localPath;
    private boolean alreadyCloned;
}
