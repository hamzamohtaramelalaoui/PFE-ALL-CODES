package org.example.astjavaparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullRepositoryAnalysis {
    private RepositoryAnalysis repositoryAnalysis;
    private ConfigurationPropertiesAnalysis configurationAnalysis;
}
