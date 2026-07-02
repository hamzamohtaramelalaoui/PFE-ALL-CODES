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
public class FieldAnalysis {
    private String name;
    private String type;
    private List<String> annotations;
    private List<String> modifiers;
    private boolean initialized;
    private String initializer;
    private boolean dependency;
    private boolean constructorInjected;
    private boolean constant;
    private boolean external;
}
