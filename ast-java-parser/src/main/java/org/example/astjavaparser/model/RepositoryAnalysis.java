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
public class RepositoryAnalysis {
    private String repositoryRoot;
    private String sourceRoot;
    private int javaFileCount;
    private List<SymbolEntry> symbolTable;
    private Map<String, List<String>> reverseSymbolTable;
    private List<FileAnalysis> files;
}
