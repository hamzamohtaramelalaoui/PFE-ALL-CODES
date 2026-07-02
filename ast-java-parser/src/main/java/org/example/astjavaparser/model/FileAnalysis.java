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
public class FileAnalysis {
    private String filePath;
    private String packageName;
    private List<String> imports;
    private List<TypeAnalysis> types;
}
