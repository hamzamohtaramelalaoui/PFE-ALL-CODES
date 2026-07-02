package org.example.astjavaparser.model;

import lombok.Data;

@Data
public class GitLabProjectDto {
    private Long id;
    private String name;
    private String path_with_namespace;
    private String http_url_to_repo;
    private String ssh_url_to_repo;
    private String web_url;
    private String default_branch;
}
