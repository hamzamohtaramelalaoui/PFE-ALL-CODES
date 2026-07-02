package org.example.astjavaparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurationPropertiesAnalysis {
    private String repositoryRoot;
    private List<String> resourceRoots;
    private int configFileCount;
    private List<ConfigurationFileAnalysis> configFiles;
    private List<ConfigurationKeyUsage> propertyUsages;
    private List<GroupedConfigurationProperty> groupedProperties;
    private List<ExternalServiceDependency> serviceDependencies;
    private List<ConfigurationChunk> configChunks;
}
