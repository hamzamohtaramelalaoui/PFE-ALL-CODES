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
public class TypeAnalysis {
    private String name;
    private String qualifiedName;
    private String type;
    private String layer;
    private List<String> annotations;
    private List<String> extendsTypes;
    private List<String> implementsTypes;
    private boolean structureOnly;
    private List<FieldAnalysis> fields;
    private List<String> injectedDependencies;
    private List<String> internalDependencies;
    private List<String> externalDependencies;
    private List<DependencyReference> externalDependencyDetails;
    private List<String> collaborators;
    private List<String> contractDependencies;
    private List<String> internalDataTypes;
    private List<String> externalDataTypes;
    private List<String> exceptionTypes;
    private List<String> annotationDependencies;
    private List<String> constantSources;
    private List<ConstructorAnalysis> constructors;
    private List<MethodAnalysis> methods;
    private String code;
}
