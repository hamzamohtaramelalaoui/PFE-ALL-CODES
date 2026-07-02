package org.example.astjavaparser.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GitLabConfig {

    @Bean
    public WebClient gitlabWebClient(GitLabProperties gitLabProperties) {
        return WebClient.builder()
                .baseUrl(gitLabProperties.getBaseUrl())
                .defaultHeader("PRIVATE-TOKEN", gitLabProperties.getToken())
                .build();
    }
}
