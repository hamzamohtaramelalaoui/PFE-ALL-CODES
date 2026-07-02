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
public class GroupedConfigurationProperty {
    private String key;
    private String description;
    private String category;
    private boolean sensitive;
    private Map<String, Object> environments;
    private List<String> usedBy;
}
