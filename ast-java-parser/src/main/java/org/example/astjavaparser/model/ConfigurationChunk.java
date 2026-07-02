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
public class ConfigurationChunk {
    private String type;
    private String key;
    private String summary;
    private Map<String, Object> values;
    private List<String> usedBy;
    private String category;
    private boolean sensitive;
}
