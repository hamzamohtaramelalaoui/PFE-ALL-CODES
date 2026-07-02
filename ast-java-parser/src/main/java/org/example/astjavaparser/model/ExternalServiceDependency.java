package org.example.astjavaparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalServiceDependency {
    private String type;
    private String name;
    private String description;
    private String category;
    private boolean internal;
    private List<String> configs;
    private Map<String, Object> environments;
    private List<String> usedBy;
}
