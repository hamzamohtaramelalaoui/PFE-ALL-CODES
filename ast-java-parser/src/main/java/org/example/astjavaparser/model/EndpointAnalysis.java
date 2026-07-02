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
public class EndpointAnalysis {
    private List<String> httpMethods;
    private String basePath;
    private String basePathInCode;
    private String methodPath;
    private String methodPathInCode;
    private String fullPath;
    private String fullPathInCode;
    private String canonicalEndpointId;
    private List<SymbolReference> symbols;
    private List<String> segments;
}
