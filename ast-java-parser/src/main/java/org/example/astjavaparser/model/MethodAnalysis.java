package org.example.astjavaparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodAnalysis {
    private List<String> annotations;
    private String visibility;
    private List<String> modifiers;
    private List<String> httpMethods;
    private String rawPath;
    private String endpoint;
    private String endpointInCode;
    private String basePath;
    private String basePathInCode;
    private List<SymbolReference> endpointSymbols;
    private List<String> endpointSegments;
    private EndpointAnalysis endpointAnalysis;
    private String returnType;
    private String name;
    private String methodRole;
    private List<ParameterAnalysis> parameters;
    private List<String> dependencies;
    private List<String> internalDependencies;
    private Map<String, String> variableTypes;
    private List<String> instantiations;
    private List<String> constantsUsed;
    private List<String> methodCalls;
    private List<String> internalCalls;
    private List<String> externalCalls;
    private List<String> frameworkCalls;
    private List<CallEdge> edges;
    private List<DependencyReference> externalDependencies;
    private String code;
}
