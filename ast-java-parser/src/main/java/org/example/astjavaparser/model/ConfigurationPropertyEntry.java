package org.example.astjavaparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurationPropertyEntry {
    private String key;
    private Object value;
    private boolean sensitive;
    private String category;
    private String description;
    private java.util.List<String> usedBy;
}
