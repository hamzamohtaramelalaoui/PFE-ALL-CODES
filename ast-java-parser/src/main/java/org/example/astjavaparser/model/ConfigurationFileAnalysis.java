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
public class ConfigurationFileAnalysis {
    private String filePath;
    private String fileName;
    private String environment;
    private boolean defaultConfig;
    private List<ConfigurationPropertyEntry> properties;
}
