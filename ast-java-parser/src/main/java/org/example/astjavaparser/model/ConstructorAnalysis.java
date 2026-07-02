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
public class ConstructorAnalysis {
    private List<String> annotations;
    private String visibility;
    private List<ParameterAnalysis> parameters;
    private List<String> initializedFields;
    private String code;
}
