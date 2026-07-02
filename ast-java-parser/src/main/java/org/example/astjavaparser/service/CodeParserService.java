package org.example.astjavaparser.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.example.astjavaparser.model.CallEdge;
import org.example.astjavaparser.model.ConstructorAnalysis;
import org.example.astjavaparser.model.DependencyReference;
import org.example.astjavaparser.model.EndpointAnalysis;
import org.example.astjavaparser.model.FieldAnalysis;
import org.example.astjavaparser.model.FileAnalysis;
import org.example.astjavaparser.model.MethodAnalysis;
import org.example.astjavaparser.model.ParameterAnalysis;
import org.example.astjavaparser.model.RepositoryAnalysis;
import org.example.astjavaparser.model.SymbolEntry;
import org.example.astjavaparser.model.SymbolReference;
import org.example.astjavaparser.model.TypeAnalysis;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CodeParserService {
    private final JavaParser javaParser;

    public CodeParserService() {
        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(parserConfiguration);
    }

    public RepositoryAnalysis parseRepository(Path repositoryRoot) throws IOException {
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        Path sourceRoot = resolveSourceRoot(normalizedRoot);
        List<Path> javaFiles = listJavaFiles(sourceRoot);
        InternalTypeIndex internalTypeIndex = buildInternalTypeIndex(javaFiles);
        ConstantIndex constantIndex = buildConstantIndex(javaFiles);
        String basePackage = detectBasePackage(javaFiles);

        List<FileAnalysis> files = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            files.add(parseJavaFile(javaFile, normalizedRoot, internalTypeIndex, constantIndex, basePackage));
        }

        return RepositoryAnalysis.builder()
                .repositoryRoot(normalizedRoot.toString())
                .sourceRoot(sourceRoot.toString())
                .javaFileCount(javaFiles.size())
                .symbolTable(constantIndex.symbolTable())
                .reverseSymbolTable(constantIndex.reverseSymbolTable())
                .files(files)
                .build();
    }

    private Path resolveSourceRoot(Path repositoryRoot) {
        Path conventionalSourceRoot = repositoryRoot.resolve("src").resolve("main").resolve("java");
        if (Files.isDirectory(conventionalSourceRoot)) {
            return conventionalSourceRoot;
        }
        if (Files.isDirectory(repositoryRoot)) {
            return repositoryRoot;
        }
        throw new IllegalArgumentException("Unable to locate a Java source root under " + repositoryRoot);
    }

    private List<Path> listJavaFiles(Path sourceRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private InternalTypeIndex buildInternalTypeIndex(List<Path> javaFiles) throws IOException {
        Map<String, String> simpleNameToQualifiedName = new HashMap<>();
        Set<String> qualifiedNames = new HashSet<>();

        for (Path javaFile : javaFiles) {
            CompilationUnit unit = parseCompilationUnit(javaFile);
            for (TypeDeclaration<?> type : unit.getTypes()) {
                registerType(type, unit, simpleNameToQualifiedName, qualifiedNames);
            }
        }

        return new InternalTypeIndex(simpleNameToQualifiedName, qualifiedNames);
    }

    private void registerType(TypeDeclaration<?> type,
                              CompilationUnit unit,
                              Map<String, String> simpleNameToQualifiedName,
                              Set<String> qualifiedNames) {
        String simpleName = type.getNameAsString();
        String qualifiedName = type.getFullyQualifiedName().orElseGet(() -> qualify(unit, simpleName));
        simpleNameToQualifiedName.putIfAbsent(simpleName, qualifiedName);
        qualifiedNames.add(qualifiedName);

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nestedType) {
                registerType(nestedType, unit, simpleNameToQualifiedName, qualifiedNames);
            }
        }
    }

    private ConstantIndex buildConstantIndex(List<Path> javaFiles) throws IOException {
        Map<String, String> qualifiedValues = new LinkedHashMap<>();
        Map<String, List<String>> simpleFieldOwners = new LinkedHashMap<>();
        Map<String, List<String>> reverseValues = new LinkedHashMap<>();

        for (Path javaFile : javaFiles) {
            CompilationUnit unit = parseCompilationUnit(javaFile);
            registerConstants(unit, javaFile, qualifiedValues, simpleFieldOwners, reverseValues);
        }

        return new ConstantIndex(qualifiedValues, simpleFieldOwners, reverseValues);
    }

    private void registerConstants(CompilationUnit unit,
                                   Path javaFile,
                                   Map<String, String> qualifiedValues,
                                   Map<String, List<String>> simpleFieldOwners,
                                   Map<String, List<String>> reverseValues) {
        List<String> imports = unit.getImports().stream()
                .map(importDeclaration -> importDeclaration.getNameAsString())
                .toList();

        for (TypeDeclaration<?> type : unit.getTypes()) {
            registerConstants(type, unit, javaFile, imports, qualifiedValues, simpleFieldOwners, reverseValues);
        }
    }

    private void registerConstants(TypeDeclaration<?> type,
                                   CompilationUnit unit,
                                   Path javaFile,
                                   List<String> imports,
                                   Map<String, String> qualifiedValues,
                                   Map<String, List<String>> simpleFieldOwners,
                                   Map<String, List<String>> reverseValues) {
        if (isConstantsType(type, unit, javaFile)) {
            String ownerQualifiedName = type.getFullyQualifiedName().orElseGet(() -> qualify(unit, type.getNameAsString()));
            Map<String, String> localConstants = new LinkedHashMap<>();
            ConstantIndex currentIndex = new ConstantIndex(qualifiedValues, simpleFieldOwners, reverseValues);

            for (FieldDeclaration fieldDeclaration : type.getFields()) {
                if (!fieldDeclaration.isStatic() || !fieldDeclaration.isFinal()) {
                    continue;
                }
                for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                    Optional<String> resolved = variable.getInitializer()
                            .flatMap(initializer -> resolveStringExpression(initializer, unit, imports, localConstants, currentIndex));
                    if (resolved.isPresent()) {
                        String key = ownerQualifiedName + "." + variable.getNameAsString();
                        qualifiedValues.put(key, resolved.get());
                        simpleFieldOwners.computeIfAbsent(variable.getNameAsString(), ignored -> new ArrayList<>()).add(key);
                        reverseValues.computeIfAbsent(resolved.get(), ignored -> new ArrayList<>()).add(key);
                        localConstants.put(variable.getNameAsString(), resolved.get());
                    }
                }
            }
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nestedType) {
                registerConstants(nestedType, unit, javaFile, imports, qualifiedValues, simpleFieldOwners, reverseValues);
            }
        }
    }

    private boolean isConstantsType(TypeDeclaration<?> type, CompilationUnit unit, Path javaFile) {
        String normalizedName = type.getNameAsString().toLowerCase(Locale.ROOT);
        String normalizedPackage = packageName(unit).toLowerCase(Locale.ROOT);
        String normalizedPath = javaFile.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalizedName.contains("path")
                || normalizedName.contains("constant")
                || normalizedPackage.contains(".constants")
                || normalizedPath.contains("/constants/");
    }

    private FileAnalysis parseJavaFile(Path javaFile,
                                       Path repositoryRoot,
                                       InternalTypeIndex internalTypeIndex,
                                       ConstantIndex constantIndex,
                                       String basePackage) throws IOException {
        CompilationUnit unit = parseCompilationUnit(javaFile);
        String packageName = packageName(unit);

        List<String> imports = unit.getImports().stream()
                .map(importDeclaration -> importDeclaration.getNameAsString())
                .sorted()
                .toList();

        List<TypeAnalysis> types = unit.getTypes().stream()
                .map(type -> analyzeType(type, unit, packageName, imports, internalTypeIndex, constantIndex, basePackage))
                .toList();

        return FileAnalysis.builder()
                .filePath(repositoryRoot.relativize(javaFile.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                .packageName(packageName)
                .imports(imports)
                .types(types)
                .build();
    }

    private CompilationUnit parseCompilationUnit(Path javaFile) throws IOException {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
            return parseResult.getResult().get();
        }
        if (!parseResult.getProblems().isEmpty()) {
            throw new ParseProblemException(parseResult.getProblems());
        }
        throw new IllegalStateException("Unable to parse " + javaFile);
    }

    private TypeAnalysis analyzeType(TypeDeclaration<?> type,
                                     CompilationUnit unit,
                                     String packageName,
                                     List<String> imports,
                                     InternalTypeIndex internalTypeIndex,
                                     ConstantIndex constantIndex,
                                     String basePackage) {
        boolean structureOnly = isStructureOnlyType(type, packageName);
        String qualifiedName = type.getFullyQualifiedName().orElseGet(() -> qualify(unit, type.getNameAsString()));
        String typeKind = detectTypeKind(type, packageName);
        String layer = detectLayer(type, packageName);

        List<FieldAnalysis> fields = extractFields(type, internalTypeIndex);
        Set<String> constructorInjectedFields = extractConstructorInjectedFieldNames(type);
        fields.forEach(field -> field.setConstructorInjected(constructorInjectedFields.contains(field.getName())));

        Map<String, FieldAnalysis> fieldsByName = fields.stream()
                .collect(Collectors.toMap(FieldAnalysis::getName, field -> field, (left, right) -> left, LinkedHashMap::new));

        List<ConstructorAnalysis> constructors = extractConstructors(type);
        List<String> injectedDependencies = fields.stream()
                .filter(FieldAnalysis::isDependency)
                .map(field -> field.getType() + " " + field.getName())
                .distinct()
                .toList();

        Map<String, String> localStringConstants = extractLocalStringConstants(type, unit, imports, constantIndex);
        List<MethodAnalysis> methods = structureOnly && !shouldExtractMethodsForStructureOnlyType(type, packageName, layer)
                ? Collections.emptyList()
                : extractMethods(type, unit, packageName, imports, fieldsByName, internalTypeIndex, constantIndex, localStringConstants, basePackage);
        TypeDependencySummary typeDependencies = collectTypeDependencies(type, imports, fields, constructors, methods, internalTypeIndex, basePackage);

        return TypeAnalysis.builder()
                .name(type.getNameAsString())
                .qualifiedName(qualifiedName)
                .type(typeKind)
                .layer(layer)
                .annotations(annotationNames(type))
                .extendsTypes(extractExtendedTypes(type))
                .implementsTypes(extractImplementedTypes(type))
                .structureOnly(structureOnly)
                .fields(fields)
                .injectedDependencies(injectedDependencies)
                .internalDependencies(new ArrayList<>(typeDependencies.internalDependencies()))
                .externalDependencies(new ArrayList<>(typeDependencies.externalDependencies().keySet()))
                .externalDependencyDetails(new ArrayList<>(typeDependencies.externalDependencies().values()))
                .collaborators(new ArrayList<>(typeDependencies.collaborators()))
                .contractDependencies(new ArrayList<>(typeDependencies.contractDependencies()))
                .internalDataTypes(new ArrayList<>(typeDependencies.internalDataTypes()))
                .externalDataTypes(new ArrayList<>(typeDependencies.externalDataTypes()))
                .exceptionTypes(new ArrayList<>(typeDependencies.exceptionTypes()))
                .annotationDependencies(new ArrayList<>(typeDependencies.annotationDependencies()))
                .constantSources(new ArrayList<>(typeDependencies.constantSources()))
                .constructors(constructors)
                .methods(methods)
                .code(type.toString())
                .build();
    }

    private List<FieldAnalysis> extractFields(TypeDeclaration<?> type, InternalTypeIndex internalTypeIndex) {
        List<FieldAnalysis> fields = new ArrayList<>();

        if (type instanceof RecordDeclaration recordDeclaration) {
            for (Parameter parameter : recordDeclaration.getParameters()) {
                String fieldType = parameter.getType().asString();
                fields.add(FieldAnalysis.builder()
                        .name(parameter.getNameAsString())
                        .type(fieldType)
                        .annotations(annotationNames(parameter))
                        .modifiers(Collections.emptyList())
                        .initialized(false)
                        .initializer(null)
                        .dependency(false)
                        .constructorInjected(false)
                        .constant(false)
                        .external(isExternalType(fieldType, internalTypeIndex))
                        .build());
            }
        }

        for (FieldDeclaration fieldDeclaration : type.getFields()) {
            for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                String fieldType = variable.getType().asString();
                boolean constant = fieldDeclaration.isStatic() && fieldDeclaration.isFinal();
                boolean dependency = isDependencyField(fieldDeclaration, variable, internalTypeIndex);

                fields.add(FieldAnalysis.builder()
                        .name(variable.getNameAsString())
                        .type(fieldType)
                        .annotations(annotationNames(fieldDeclaration))
                        .modifiers(fieldDeclaration.getModifiers().stream()
                                .map(Modifier::getKeyword)
                                .map(keyword -> keyword.asString())
                                .toList())
                        .initialized(variable.getInitializer().isPresent())
                        .initializer(variable.getInitializer().map(Expression::toString).orElse(null))
                        .dependency(dependency)
                        .constructorInjected(false)
                        .constant(constant)
                        .external(isExternalType(fieldType, internalTypeIndex))
                        .build());
            }
        }

        if (type instanceof EnumDeclaration enumDeclaration) {
            for (EnumConstantDeclaration constant : enumDeclaration.getEntries()) {
                fields.add(FieldAnalysis.builder()
                        .name(constant.getNameAsString())
                        .type(enumDeclaration.getNameAsString())
                        .annotations(annotationNames(constant))
                        .modifiers(List.of("enum"))
                        .initialized(true)
                        .initializer(constant.toString())
                        .dependency(false)
                        .constructorInjected(false)
                        .constant(true)
                        .external(false)
                        .build());
            }
        }

        return fields;
    }

    private Set<String> extractConstructorInjectedFieldNames(TypeDeclaration<?> type) {
        Set<String> initializedFields = new LinkedHashSet<>();
        for (ConstructorDeclaration constructor : type.getConstructors()) {
            Set<String> parameterNames = constructor.getParameters().stream()
                    .map(Parameter::getNameAsString)
                    .collect(Collectors.toSet());

            constructor.findAll(AssignExpr.class).forEach(assignExpr ->
                    extractAssignedFieldName(assignExpr, parameterNames).ifPresent(initializedFields::add));
        }
        initializedFields.addAll(extractLombokConstructorInjectedFieldNames(type));
        return initializedFields;
    }

    private Set<String> extractLombokConstructorInjectedFieldNames(TypeDeclaration<?> type) {
        Set<String> annotations = new LinkedHashSet<>(annotationNames(type));
        if (annotations.contains("RequiredArgsConstructor")) {
            return type.getFields().stream()
                    .filter(field -> !field.isStatic())
                    .filter(field -> field.isFinal()
                            || annotationNames(field).stream().anyMatch(annotation -> annotation.equals("NonNull")))
                    .flatMap(field -> field.getVariables().stream())
                    .filter(variable -> variable.getInitializer().isEmpty())
                    .map(variable -> variable.getName().asString())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (annotations.contains("AllArgsConstructor")) {
            return type.getFields().stream()
                    .filter(field -> !field.isStatic())
                    .flatMap(field -> field.getVariables().stream())
                    .map(variable -> variable.getName().asString())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Collections.emptySet();
    }

    private List<ConstructorAnalysis> extractConstructors(TypeDeclaration<?> type) {
        return type.getConstructors().stream()
                .map(constructor -> {
                    Set<String> parameterNames = constructor.getParameters().stream()
                            .map(Parameter::getNameAsString)
                            .collect(Collectors.toSet());

                    List<String> initializedFields = constructor.findAll(AssignExpr.class).stream()
                            .map(assignExpr -> extractAssignedFieldName(assignExpr, parameterNames))
                            .flatMap(Optional::stream)
                            .distinct()
                            .toList();

                    return ConstructorAnalysis.builder()
                            .annotations(annotationNames(constructor))
                            .visibility(resolveVisibility(constructor))
                            .parameters(extractParameters(constructor))
                            .initializedFields(initializedFields)
                            .code(constructor.toString())
                            .build();
                })
                .toList();
    }

    private List<MethodAnalysis> extractMethods(TypeDeclaration<?> type,
                                                CompilationUnit unit,
                                                String packageName,
                                                List<String> imports,
                                                Map<String, FieldAnalysis> fieldsByName,
                                                InternalTypeIndex internalTypeIndex,
                                                ConstantIndex constantIndex,
                                                Map<String, String> localStringConstants,
                                                String basePackage) {
        return type.getMethods().stream()
                .filter(method -> !isTrivialAccessor(method, fieldsByName.keySet()))
                .map(method -> analyzeMethod(type, unit, packageName, imports, method, fieldsByName, internalTypeIndex, constantIndex, localStringConstants, basePackage))
                .toList();
    }

    private MethodAnalysis analyzeMethod(TypeDeclaration<?> type,
                                         CompilationUnit unit,
                                         String packageName,
                                         List<String> imports,
                                         MethodDeclaration method,
                                         Map<String, FieldAnalysis> fieldsByName,
                                         InternalTypeIndex internalTypeIndex,
                                         ConstantIndex constantIndex,
                                         Map<String, String> localStringConstants,
                                         String basePackage) {
        Set<String> localMethodTargets = type.getMethods().stream()
                .map(MethodDeclaration::getNameAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> dependencies = new LinkedHashSet<>();
        Set<String> internalDependencies = new LinkedHashSet<>();
        Map<String, DependencyReference> externalDependencies = new LinkedHashMap<>();
        Map<String, String> variableTypes = buildVariableTypeMap(method, fieldsByName, imports);
        Map<String, String> lambdaScopeOwners = buildLambdaScopeOwners(type, method, variableTypes, imports, localMethodTargets);
        Set<String> instantiations = extractInstantiations(method, imports);
        Set<String> constantsUsed = extractConstantsUsed(method, variableTypes, imports);

        fieldsByName.values().stream()
                .filter(FieldAnalysis::isDependency)
                .filter(field -> referencesField(method, field.getName()))
                .forEach(field -> {
                    addDependencyType(field.getType(), internalTypeIndex, imports, dependencies, internalDependencies, externalDependencies, basePackage);
                });

        Set<String> constructorInjectedFieldNames = extractConstructorInjectedFieldNames(type);

        constructorInjectedFieldNames.stream()
                .map(fieldsByName::get)
                .filter(field -> field != null && referencesField(method, field.getName()))
                .forEach(field -> {
                    addDependencyType(field.getType(), internalTypeIndex, imports, dependencies, internalDependencies, externalDependencies, basePackage);
                });

        method.getParameters().forEach(parameter -> {
            addDependencyType(parameter.getType().asString(), internalTypeIndex, imports, dependencies, internalDependencies, externalDependencies, basePackage);
        });

        Set<String> methodCalls = method.findAll(MethodCallExpr.class).stream()
                .map(call -> cleanMethodCall(type, method, call, variableTypes, imports, localMethodTargets, lambdaScopeOwners))
                .filter(call -> !call.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        instantiations.forEach(instantiation ->
                addDependencyType(instantiation, internalTypeIndex, imports, dependencies, internalDependencies, externalDependencies, basePackage));

        extractUsedDependencyTypes(method, imports, internalTypeIndex, variableTypes)
                .forEach(importedType -> addDependencyType(importedType, internalTypeIndex, imports, dependencies, internalDependencies, externalDependencies, basePackage));

        CallBuckets callBuckets = classifyCalls(methodCalls, internalTypeIndex, imports, localMethodTargets, type.getNameAsString(), basePackage);
        String defaultFrom = type.getFullyQualifiedName().orElse(type.getNameAsString()) + "." + method.getNameAsString();
        List<CallEdge> edges = methodCalls.stream()
                .map(target -> CallEdge.builder().from(defaultFrom).to(target).build())
                .toList();

        EndpointResolution endpointResolution = resolveEndpoint(type, unit, packageName, imports, method, constantIndex, localStringConstants);
        String edgeFrom = endpointResolution.endpointAnalysis() != null
                && endpointResolution.endpointAnalysis().getCanonicalEndpointId() != null
                && !endpointResolution.endpointAnalysis().getCanonicalEndpointId().isBlank()
                ? endpointResolution.endpointAnalysis().getCanonicalEndpointId()
                : defaultFrom;
        edges = methodCalls.stream()
                .map(target -> CallEdge.builder().from(edgeFrom).to(target).build())
                .toList();

        return MethodAnalysis.builder()
                .annotations(annotationNames(method))
                .visibility(resolveVisibility(method))
                .modifiers(method.getModifiers().stream()
                        .map(Modifier::getKeyword)
                        .map(keyword -> keyword.asString())
                        .toList())
                .httpMethods(endpointResolution.httpMethods())
                .rawPath(endpointResolution.rawPath())
                .endpoint(endpointResolution.endpoint())
                .endpointInCode(endpointResolution.endpointInCode())
                .basePath(endpointResolution.basePath())
                .basePathInCode(endpointResolution.basePathInCode())
                .endpointSymbols(endpointResolution.endpointSymbols())
                .endpointSegments(endpointResolution.endpointSegments())
                .endpointAnalysis(endpointResolution.endpointAnalysis())
                .returnType(method.getType().asString())
                .name(method.getNameAsString())
                .methodRole(detectMethodRole(type, method))
                .parameters(extractParameters(method))
                .dependencies(new ArrayList<>(dependencies))
                .internalDependencies(new ArrayList<>(internalDependencies))
                .variableTypes(variableTypes)
                .instantiations(new ArrayList<>(instantiations))
                .constantsUsed(new ArrayList<>(constantsUsed))
                .methodCalls(new ArrayList<>(methodCalls))
                .internalCalls(new ArrayList<>(callBuckets.internalCalls()))
                .externalCalls(new ArrayList<>(callBuckets.externalCalls()))
                .frameworkCalls(new ArrayList<>(callBuckets.frameworkCalls()))
                .edges(edges)
                .externalDependencies(new ArrayList<>(externalDependencies.values()))
                .code(method.toString())
                .build();
    }

    private List<ParameterAnalysis> extractParameters(CallableDeclaration<?> declaration) {
        return declaration.getParameters().stream()
                .map(parameter -> ParameterAnalysis.builder()
                        .name(parameter.getNameAsString())
                        .type(parameter.getType().asString())
                        .build())
                .toList();
    }

    private boolean isStructureOnlyType(TypeDeclaration<?> type, String packageName) {
        if (type instanceof EnumDeclaration || type instanceof RecordDeclaration) {
            return true;
        }

        String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
        String normalizedName = type.getNameAsString().toLowerCase(Locale.ROOT);
        List<String> annotations = annotationNames(type).stream()
                .map(annotation -> annotation.toLowerCase(Locale.ROOT))
                .toList();

        if (normalizedPackage.contains(".dto")
                || normalizedPackage.contains(".entity")
                || normalizedPackage.contains(".entities")
                || normalizedPackage.contains(".model")
                || normalizedPackage.contains(".repository")
                || normalizedPackage.contains(".repositories")
                || normalizedPackage.contains(".dao")) {
            return true;
        }

        if (normalizedName.endsWith("dto")
                || normalizedName.endsWith("entity")
                || normalizedName.endsWith("repository")
                || normalizedName.endsWith("dao")
                || normalizedName.endsWith("model")) {
            return true;
        }

        return annotations.stream().anyMatch(annotation ->
                annotation.equals("entity")
                        || annotation.equals("mappedsuperclass")
                        || annotation.equals("embeddable")
                        || annotation.equals("repository"));
    }

    private String detectLayer(TypeDeclaration<?> type, String packageName) {
        Set<String> annotations = new HashSet<>(annotationNames(type));
        String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
        String normalizedName = type.getNameAsString().toLowerCase(Locale.ROOT);

        if (annotations.contains("RestController") || annotations.contains("Controller") || normalizedPackage.contains(".controller")) {
            return "controller";
        }
        if (isApplicationType(type)) {
            return "application";
        }
        if (isConfigType(type, packageName)) {
            return "config";
        }
        if (annotations.contains("Service")
                || normalizedPackage.contains(".service")
                || normalizedPackage.contains(".service.impl")
                || normalizedPackage.contains(".service.implementation")
                || normalizedName.endsWith("serviceimpl")
                || normalizedName.endsWith("serviceimplementation")
                || normalizedName.endsWith("impl")
                || normalizedName.endsWith("implementation")) {
            return "service";
        }
        if (isRepositoryType(type, packageName, annotations, normalizedName)) {
            return "repository";
        }
        if (normalizedPackage.contains(".dao") || normalizedName.endsWith("dao")) {
            return "dao";
        }
        if (annotations.contains("Entity") || normalizedPackage.contains(".entity") || normalizedPackage.contains(".entities")) {
            return "entity";
        }
        if (normalizedPackage.contains(".dto") || normalizedName.endsWith("dto")) {
            return "dto";
        }
        if (normalizedPackage.contains(".model") || normalizedName.endsWith("model")) {
            return "model";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) {
            return "interface";
        }
        return "class";
    }

    private boolean isApplicationType(TypeDeclaration<?> type) {
        Set<String> annotations = new HashSet<>(annotationNames(type));
        if (annotations.contains("SpringBootApplication")) {
            return true;
        }
        return type.getMethods().stream().anyMatch(this::isMainMethod);
    }

    private boolean isRepositoryType(TypeDeclaration<?> type,
                                     String packageName,
                                     Set<String> annotations,
                                     String normalizedName) {
        String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
        if (annotations.contains("Repository")
                || normalizedPackage.contains(".repository")
                || normalizedPackage.contains(".repositories")
                || normalizedName.endsWith("repository")) {
            return true;
        }
        return extractExtendedTypes(type).stream()
                .map(this::eraseGenericType)
                .map(name -> name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name)
                .anyMatch(name -> Set.of(
                        "JpaRepository",
                        "CrudRepository",
                        "PagingAndSortingRepository",
                        "Repository",
                        "JpaSpecificationExecutor"
                ).contains(name));
    }

    private boolean isConfigType(TypeDeclaration<?> type, String packageName) {
        Set<String> annotations = new HashSet<>(annotationNames(type));
        String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
        String normalizedName = type.getNameAsString().toLowerCase(Locale.ROOT);

        if (annotations.contains("Configuration") || annotations.contains("Component")) {
            return true;
        }
        if (normalizedPackage.contains(".config") || normalizedName.endsWith("config") || normalizedName.endsWith("configuration")) {
            return true;
        }
        return type.getMethods().stream().anyMatch(method -> annotationNames(method).contains("Bean"));
    }

    private String detectTypeKind(TypeDeclaration<?> type, String packageName) {
        if (isExceptionType(type, packageName)) {
            return "exception";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? "interface" : "class";
        }
        return type.getMetaModel().getTypeName().replace("Declaration", "").toLowerCase(Locale.ROOT);
    }

    private boolean isExceptionType(TypeDeclaration<?> type, String packageName) {
        String normalizedName = type.getNameAsString().toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith("exception")) {
            return true;
        }
        if (packageName.toLowerCase(Locale.ROOT).contains(".exception")) {
            return true;
        }
        return extractExtendedTypes(type).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.endsWith("exception")
                        || name.endsWith("runtimeexception")
                        || name.endsWith("throwable"));
    }

    private List<String> extractExtendedTypes(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.getExtendedTypes().stream()
                    .map(ClassOrInterfaceType::asString)
                    .toList();
        }
        return Collections.emptyList();
    }

    private List<String> extractImplementedTypes(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::asString)
                    .toList();
        }
        if (type instanceof RecordDeclaration recordDeclaration) {
            return recordDeclaration.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::asString)
                    .toList();
        }
        if (type instanceof EnumDeclaration enumDeclaration) {
            return enumDeclaration.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::asString)
                    .toList();
        }
        return Collections.emptyList();
    }

    private boolean isDependencyField(FieldDeclaration fieldDeclaration,
                                      VariableDeclarator variable,
                                      InternalTypeIndex internalTypeIndex) {
        if (fieldDeclaration.isStatic() && fieldDeclaration.isFinal()) {
            return false;
        }
        if (hasAnyAnnotation(fieldDeclaration, Set.of("Autowired", "Inject", "Resource", "Qualifier"))) {
            return true;
        }
        if (fieldDeclaration.isFinal() && !variable.getInitializer().isPresent()) {
            return true;
        }

        String fieldType = variable.getType().asString();
        if (isPrimitiveLike(fieldType)) {
            return false;
        }

        return !variable.getInitializer().isPresent()
                && (!fieldDeclaration.isPrivate() || isKnownDependencyType(fieldType, internalTypeIndex));
    }

    private boolean isKnownDependencyType(String fieldType, InternalTypeIndex internalTypeIndex) {
        return internalTypeIndex.contains(fieldType)
                || fieldType.startsWith("Rest")
                || fieldType.startsWith("Web")
                || fieldType.startsWith("Jpa")
                || fieldType.endsWith("Template")
                || fieldType.endsWith("Client")
                || fieldType.endsWith("Service")
                || fieldType.endsWith("Repository");
    }

    private boolean isPrimitiveLike(String typeName) {
        String normalized = eraseGenericType(typeName);
        return Set.of(
                "byte", "short", "int", "long", "float", "double", "boolean", "char", "void",
                "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character",
                "String", "BigDecimal", "BigInteger", "UUID", "LocalDate", "LocalDateTime", "Instant"
        ).contains(normalized);
    }

    private boolean isExternalType(String typeName, InternalTypeIndex internalTypeIndex) {
        for (String token : splitTypeTokens(typeName)) {
            if (internalTypeIndex.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private List<String> splitTypeTokens(String typeName) {
        String sanitized = typeName.replace(">", "")
                .replace("<", ",")
                .replace("[", ",")
                .replace("]", ",")
                .replace("?", ",")
                .replace("extends", ",")
                .replace("super", ",");

        return Stream.of(sanitized.split("[,\\s&]+"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(this::eraseGenericType)
                .map(token -> token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token)
                .filter(token -> !token.isEmpty())
                .toList();
    }

    private String eraseGenericType(String typeName) {
        String raw = typeName.trim();
        int genericStart = raw.indexOf('<');
        if (genericStart >= 0) {
            raw = raw.substring(0, genericStart);
        }
        if (raw.endsWith("...")) {
            raw = raw.substring(0, raw.length() - 3);
        }
        return raw;
    }

    private boolean referencesField(MethodDeclaration method, String fieldName) {
        boolean nameReferenced = method.findAll(NameExpr.class).stream()
                .anyMatch(nameExpr -> nameExpr.getNameAsString().equals(fieldName));

        boolean fieldAccessReferenced = method.findAll(FieldAccessExpr.class).stream()
                .anyMatch(fieldAccessExpr -> fieldAccessExpr.getScope().isThisExpr()
                        && fieldAccessExpr.getNameAsString().equals(fieldName));

        return nameReferenced || fieldAccessReferenced;
    }

    private boolean isTrivialAccessor(MethodDeclaration method, Set<String> fieldNames) {
        String methodName = method.getNameAsString();
        if (method.getBody().isEmpty()) {
            return false;
        }

        BlockStmt body = method.getBody().orElseThrow();
        if (body.getStatements().size() != 1) {
            return false;
        }

        if (methodName.startsWith("get") && method.getParameters().isEmpty()) {
            String fieldName = decapitalize(methodName.substring(3));
            return fieldNames.contains(fieldName)
                    && (body.toString().contains("return " + fieldName)
                    || body.toString().contains("return this." + fieldName));
        }

        if (methodName.startsWith("is") && method.getParameters().isEmpty()) {
            String fieldName = decapitalize(methodName.substring(2));
            return fieldNames.contains(fieldName)
                    && (body.toString().contains("return " + fieldName)
                    || body.toString().contains("return this." + fieldName));
        }

        if (methodName.startsWith("set") && method.getParameters().size() == 1) {
            String fieldName = decapitalize(methodName.substring(3));
            String parameterName = method.getParameter(0).getNameAsString();
            return fieldNames.contains(fieldName)
                    && (body.toString().contains(fieldName + " = " + parameterName)
                    || body.toString().contains("this." + fieldName + " = " + parameterName));
        }

        return false;
    }

    private String cleanMethodCall(TypeDeclaration<?> type,
                                   MethodDeclaration enclosingMethod,
                                   MethodCallExpr methodCallExpr,
                                   Map<String, String> variableTypes,
                                   List<String> imports,
                                   Set<String> localMethodTargets,
                                   Map<String, String> lambdaScopeOwners) {
        String methodName = methodCallExpr.getNameAsString();
        if (methodCallExpr.getScope().isEmpty()) {
            if (localMethodTargets.contains(methodName)) {
                return type.getNameAsString() + "." + methodName;
            }
            return "";
        }

        Expression scope = methodCallExpr.getScope().orElseThrow();
        String scopeName = simplifyScope(scope, variableTypes, imports, lambdaScopeOwners);
        if (scopeName == null || scopeName.isBlank()) {
            return "";
        }
        if (isIgnoredDependencyType(scopeName, imports)) {
            return "";
        }
        if (shouldIgnoreLowValueMethodCall(scopeName, methodName)) {
            return "";
        }
        if (isChainedCallFragment(methodCallExpr) && !shouldKeepChainedCall(scopeName)) {
            return "";
        }
        return scopeName + "." + methodName;
    }

    private boolean isChainedCallFragment(MethodCallExpr methodCallExpr) {
        return methodCallExpr.getScope()
                .filter(Expression::isMethodCallExpr)
                .isPresent();
    }

    private boolean shouldKeepChainedCall(String scopeName) {
        return scopeName.contains(".")
                || (!scopeName.isBlank() && Character.isUpperCase(scopeName.charAt(0)));
    }

    private boolean shouldIgnoreLowValueMethodCall(String scopeName, String methodName) {
        if (scopeName.equals("System") && methodName.equals("println")) {
            return true;
        }
        if (scopeName.equals("ResponseEntity") && methodName.equals("getBody")) {
            return true;
        }
        if (scopeName.equals("ResponseEntity") && methodName.equals("getStatusCode")) {
            return true;
        }
        if (isAccessorLikeMethod(methodName) && isLowValueAccessorOwner(scopeName)) {
            return true;
        }
        if (!scopeName.startsWith("HttpSecurity.authorizeHttpRequests")) {
            return false;
        }
        return methodName.equals("permitAll")
                || methodName.equals("authenticated")
                || methodName.equals("anyRequest");
    }

    private boolean isAccessorLikeMethod(String methodName) {
        return methodName.startsWith("get")
                || methodName.startsWith("is")
                || methodName.startsWith("set");
    }

    private boolean isLowValueAccessorOwner(String scopeName) {
        String normalized = scopeName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("dto")
                || normalized.endsWith("request")
                || normalized.endsWith("response")
                || normalized.endsWith("principal")
                || normalized.endsWith("details");
    }

    private String simplifyScope(Expression scope,
                                 Map<String, String> variableTypes,
                                 List<String> imports,
                                 Map<String, String> lambdaScopeOwners) {
        if (scope.isThisExpr()) {
            return "this";
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            if (lambdaScopeOwners.containsKey(name)) {
                return lambdaScopeOwners.get(name);
            }
            if (variableTypes.containsKey(name)) {
                return variableTypes.get(name);
            }
            if (isImportedSimpleType(name, imports)) {
                return name;
            }
            return Character.isUpperCase(name.charAt(0)) ? name : "";
        }
        if (scope.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = scope.asFieldAccessExpr();
            String fieldName = fieldAccessExpr.getNameAsString();
            if (isImportedSimpleType(fieldName, imports)) {
                return fieldName;
            }
            return simplifyScope(fieldAccessExpr.getScope(), variableTypes, imports, lambdaScopeOwners);
        }
        if (scope.isMethodCallExpr()) {
            MethodCallExpr parentCall = scope.asMethodCallExpr();
            return cleanMethodCall(null, null, parentCall, variableTypes, imports, Collections.emptySet(), lambdaScopeOwners)
                    .replaceFirst("\\.[^.]+$", "");
        }
        return null;
    }

    private Map<String, String> buildLambdaScopeOwners(TypeDeclaration<?> type,
                                                       MethodDeclaration method,
                                                       Map<String, String> variableTypes,
                                                       List<String> imports,
                                                       Set<String> localMethodTargets) {
        Map<String, String> lambdaScopeOwners = new LinkedHashMap<>();
        method.findAll(MethodCallExpr.class).forEach(methodCallExpr ->
                methodCallExpr.getArguments().stream()
                        .filter(Expression::isLambdaExpr)
                        .map(Expression::asLambdaExpr)
                        .forEach(lambdaExpr -> registerLambdaScopeOwners(type,
                                method,
                                methodCallExpr,
                                lambdaExpr,
                                variableTypes,
                                imports,
                                localMethodTargets,
                                lambdaScopeOwners)));
        return lambdaScopeOwners;
    }

    private void registerLambdaScopeOwners(TypeDeclaration<?> type,
                                           MethodDeclaration method,
                                           MethodCallExpr parentCall,
                                           LambdaExpr lambdaExpr,
                                           Map<String, String> variableTypes,
                                           List<String> imports,
                                           Set<String> localMethodTargets,
                                           Map<String, String> lambdaScopeOwners) {
        String parentTarget = cleanMethodCall(type, method, parentCall, variableTypes, imports, localMethodTargets, lambdaScopeOwners);
        if (parentTarget.isBlank() || !shouldPropagateLambdaOwner(parentTarget)) {
            return;
        }
        lambdaExpr.getParameters().forEach(parameter ->
                lambdaScopeOwners.putIfAbsent(parameter.getNameAsString(), parentTarget));
    }

    private boolean shouldPropagateLambdaOwner(String parentTarget) {
        return parentTarget.equals("HttpSecurity.authorizeHttpRequests")
                || parentTarget.equals("HttpSecurity.sessionManagement")
                || parentTarget.equals("HttpSecurity.oauth2ResourceServer")
                || parentTarget.equals("HttpSecurity.oauth2ResourceServer.jwt");
    }

    private Set<String> extractInstantiations(MethodDeclaration method, List<String> imports) {
        return method.findAll(ObjectCreationExpr.class).stream()
                .map(objectCreationExpr -> normalizeTypeName(objectCreationExpr.getType().asString(), imports))
                .filter(typeName -> !isIgnoredDependencyType(typeName, imports))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> extractConstantsUsed(MethodDeclaration method,
                                             Map<String, String> variableTypes,
                                             List<String> imports) {
        Set<String> constants = new LinkedHashSet<>();
        method.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
            String scopeName = simplifyScope(fieldAccessExpr.getScope(), variableTypes, imports, Collections.emptyMap());
            if (scopeName != null
                    && !scopeName.isBlank()
                    && Character.isUpperCase(scopeName.charAt(0))
                    && isLikelyConstantSource(scopeName, imports)) {
                constants.add(scopeName + "." + fieldAccessExpr.getNameAsString());
            }
        });
        return constants;
    }

    private void addDependencyType(String rawType,
                                   InternalTypeIndex internalTypeIndex,
                                   List<String> imports,
                                   Set<String> dependencies,
                                   Set<String> internalDependencies,
                                   Map<String, DependencyReference> externalDependencies,
                                   String basePackage) {
        for (String token : splitTypeTokens(rawType)) {
            if (token.isBlank() || isIgnoredDependencyType(token, imports)) {
                continue;
            }
            dependencies.add(token);
            String fullName = resolveImportedType(token, imports).orElse(token);
            if (isInternalDependency(token, fullName, internalTypeIndex, basePackage)) {
                internalDependencies.add(token);
            } else {
                externalDependencies.putIfAbsent(token, DependencyReference.builder()
                        .name(token)
                        .fullName(fullName)
                        .build());
            }
        }
    }

    private Optional<String> resolveImportedType(String typeName, List<String> imports) {
        return imports.stream()
                .filter(importedType -> importedType.endsWith("." + typeName))
                .findFirst();
    }

    private Set<String> extractUsedDependencyTypes(MethodDeclaration method,
                                                   List<String> imports,
                                                   InternalTypeIndex internalTypeIndex,
                                                   Map<String, String> variableTypes) {
        Set<String> usedTypes = new LinkedHashSet<>();

        method.getParameters().forEach(parameter -> usedTypes.add(parameter.getType().asString()));

        method.findAll(VariableDeclarator.class).forEach(variableDeclarator ->
                usedTypes.add(variableDeclarator.getType().asString()));

        method.findAll(ObjectCreationExpr.class).forEach(objectCreationExpr ->
                usedTypes.add(objectCreationExpr.getType().asString()));

        method.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> {
            String scope = fieldAccessExpr.getScope().toString();
            if (isImportedSimpleType(scope, imports)) {
                usedTypes.add(scope);
            }
        });

        method.findAll(MethodReferenceExpr.class).forEach(methodReferenceExpr -> {
            String scope = methodReferenceExpr.getScope().toString();
            if (isImportedSimpleType(scope, imports)) {
                usedTypes.add(scope);
            }
        });

        method.findAll(MethodCallExpr.class).forEach(methodCallExpr -> {
            methodCallExpr.getScope().ifPresent(scope -> {
                if (scope.isNameExpr()) {
                    String scopeName = scope.asNameExpr().getNameAsString();
                    if (variableTypes.containsKey(scopeName)) {
                        usedTypes.add(variableTypes.get(scopeName));
                    } else if (isImportedSimpleType(scopeName, imports)) {
                        usedTypes.add(scopeName);
                    }
                }
            });
        });

        return usedTypes.stream()
                .filter(typeName -> !isIgnoredDependencyType(typeName, imports))
                .filter(typeName -> !eraseGenericType(typeName).equals(method.getType().asString()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private CallBuckets classifyCalls(Set<String> methodCalls,
                                      InternalTypeIndex internalTypeIndex,
                                      List<String> imports,
                                      Set<String> localMethodTargets,
                                      String currentTypeName,
                                      String basePackage) {
        Set<String> internalCalls = new LinkedHashSet<>();
        Set<String> externalCalls = new LinkedHashSet<>();
        Set<String> frameworkCalls = new LinkedHashSet<>();

        for (String call : methodCalls) {
            String owner = call.contains(".") ? call.substring(0, call.lastIndexOf('.')) : call;
            String rootOwner = owner.contains(".") ? owner.substring(0, owner.indexOf('.')) : owner;
            String ownerFullName = resolveImportedType(rootOwner, imports).orElse(rootOwner);
            if (rootOwner.equals(currentTypeName) || isInternalDependency(rootOwner, ownerFullName, internalTypeIndex, basePackage)) {
                internalCalls.add(call);
                continue;
            }

            Optional<String> imported = resolveImportedType(rootOwner, imports);
            if (imported.isPresent()) {
                if (isFrameworkImport(imported.get())) {
                    frameworkCalls.add(call);
                } else {
                    externalCalls.add(call);
                }
                continue;
            }

            if (Character.isUpperCase(rootOwner.charAt(0))) {
                externalCalls.add(call);
            }
        }

        return new CallBuckets(internalCalls, externalCalls, frameworkCalls);
    }

    private boolean isFrameworkImport(String importedType) {
        return importedType.startsWith("org.springframework.")
                || importedType.startsWith("jakarta.")
                || importedType.startsWith("javax.");
    }

    private String detectMethodRole(TypeDeclaration<?> type, MethodDeclaration method) {
        List<String> annotations = annotationNames(method);
        String layer = detectLayer(type, type.findCompilationUnit().map(this::packageName).orElse(""));
        if (isMainMethod(method)) {
            return "entrypoint";
        }
        if (annotations.contains("Bean")) {
            return "bean";
        }
        if ("controller".equals(layer) && annotations.stream().anyMatch(this::isMappingAnnotation)) {
            return "endpoint";
        }
        if ("repository".equals(layer) || "dao".equals(layer) || annotations.contains("Query")) {
            return "query";
        }
        if (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) {
            return "contract";
        }
        if (method.isPrivate()) {
            return "helper";
        }
        if ("config".equals(layer) || "application".equals(layer)) {
            return "config";
        }
        return "method";
    }

    private boolean isMainMethod(MethodDeclaration method) {
        return method.isPublic()
                && method.isStatic()
                && method.getNameAsString().equals("main")
                && method.getType().isVoidType()
                && method.getParameters().size() == 1
                && "String[]".equals(method.getParameter(0).getType().asString());
    }

    private boolean shouldExtractMethodsForStructureOnlyType(TypeDeclaration<?> type,
                                                             String packageName,
                                                             String layer) {
        if ("repository".equals(layer) || "dao".equals(layer)) {
            return true;
        }
        return type instanceof ClassOrInterfaceDeclaration declaration
                && declaration.isInterface()
                && !packageName.toLowerCase(Locale.ROOT).contains(".dto");
    }

    private TypeDependencySummary collectTypeDependencies(TypeDeclaration<?> type,
                                                          List<String> imports,
                                                          List<FieldAnalysis> fields,
                                                          List<ConstructorAnalysis> constructors,
                                                          List<MethodAnalysis> methods,
                                                          InternalTypeIndex internalTypeIndex,
                                                          String basePackage) {
        Set<String> internalDependencies = new LinkedHashSet<>();
        Map<String, DependencyReference> externalDependencies = new LinkedHashMap<>();
        Set<String> allDependencies = new LinkedHashSet<>();
        Set<String> collaborators = new LinkedHashSet<>();
        Set<String> contractDependencies = new LinkedHashSet<>();
        Set<String> internalDataTypes = new LinkedHashSet<>();
        Set<String> externalDataTypes = new LinkedHashSet<>();
        Set<String> exceptionTypes = new LinkedHashSet<>();
        Set<String> annotationDependencies = new LinkedHashSet<>();
        Set<String> constantSources = new LinkedHashSet<>();

        fields.forEach(field -> {
            TypeDependencyCollector collector = createCollector(internalTypeIndex, imports, basePackage);
            collectTypeDependency(field.getType(), collector);
            mergeTypeDependencyCollector(collector, allDependencies, internalDependencies, externalDependencies);
            if (field.isDependency()) {
                addDependencyCategory(collector, collaborators, collaborators);
            } else if (!field.isConstant()) {
                addDependencyCategory(collector, internalDataTypes, externalDataTypes);
            }
        });

        constructors.stream()
                .flatMap(constructor -> constructor.getParameters().stream())
                .forEach(parameter -> {
                    TypeDependencyCollector collector = createCollector(internalTypeIndex, imports, basePackage);
                    collectTypeDependency(parameter.getType(), collector);
                    mergeTypeDependencyCollector(collector, allDependencies, internalDependencies, externalDependencies);
                    addDependencyCategory(collector, collaborators, collaborators);
                });

        extractExtendedTypes(type).forEach(extendedType -> {
            if (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) {
                categorizeExtendedContractType(extendedType,
                        internalTypeIndex,
                        imports,
                        basePackage,
                        allDependencies,
                        internalDependencies,
                        externalDependencies,
                        contractDependencies,
                        internalDataTypes,
                        externalDataTypes);
            } else {
                TypeDependencyCollector collector = createCollector(internalTypeIndex, imports, basePackage);
                collectTypeDependency(extendedType, collector);
                mergeTypeDependencyCollector(collector, allDependencies, internalDependencies, externalDependencies);
                addDependencyCategory(collector, collaborators, collaborators);
            }
        });
        extractImplementedTypes(type).forEach(implementedType -> {
            TypeDependencyCollector collector = createCollector(internalTypeIndex, imports, basePackage);
            collectTypeDependency(implementedType, collector);
            mergeTypeDependencyCollector(collector, allDependencies, internalDependencies, externalDependencies);
            addDependencyCategory(collector, contractDependencies, contractDependencies);
        });

        annotationNames(type).forEach(annotation -> {
            if (isImportedSimpleType(annotation, imports)) {
                TypeDependencyCollector collector = createCollector(internalTypeIndex, imports, basePackage);
                collectTypeDependency(annotation, collector);
                mergeTypeDependencyCollector(collector, allDependencies, internalDependencies, externalDependencies);
                addDependencyCategory(collector, annotationDependencies, annotationDependencies);
            }
        });

        type.findAll(ClassExpr.class).forEach(classExpr -> {
            TypeDependencyCollector collector = createCollector(internalTypeIndex, imports, basePackage);
            collectTypeDependency(classExpr.getTypeAsString(), collector);
            mergeTypeDependencyCollector(collector, allDependencies, internalDependencies, externalDependencies);
            addDependencyCategory(collector, internalDataTypes, externalDataTypes);
        });

        type.getMethods().forEach(method -> {
            TypeDependencyCollector returnCollector = createCollector(internalTypeIndex, imports, basePackage);
            collectTypeDependency(method.getType().asString(), returnCollector);
            mergeTypeDependencyCollector(returnCollector, allDependencies, internalDependencies, externalDependencies);
            addDependencyCategory(returnCollector, internalDataTypes, externalDataTypes);
            method.getParameters().forEach(parameter -> {
                TypeDependencyCollector parameterCollector = createCollector(internalTypeIndex, imports, basePackage);
                collectTypeDependency(parameter.getType().asString(), parameterCollector);
                mergeTypeDependencyCollector(parameterCollector, allDependencies, internalDependencies, externalDependencies);
                addDependencyCategory(parameterCollector, internalDataTypes, externalDataTypes);
            });
            annotationNames(method).forEach(annotation -> {
                if (isImportedSimpleType(annotation, imports)
                        && shouldPromoteMethodAnnotationToTypeDependencies(annotation, imports)) {
                    TypeDependencyCollector collector = createCollector(internalTypeIndex, imports, basePackage);
                    collectTypeDependency(annotation, collector);
                    mergeTypeDependencyCollector(collector, allDependencies, internalDependencies, externalDependencies);
                    addDependencyCategory(collector, annotationDependencies, annotationDependencies);
                }
            });
        });

        methods.forEach(method -> {
            method.getInternalDependencies().forEach(dependency -> {
                if (extractImplementedTypes(type).stream().anyMatch(implemented -> splitTypeTokens(implemented).contains(dependency))) {
                    contractDependencies.add(dependency);
                } else if (isExceptionLike(dependency, imports)) {
                    exceptionTypes.add(dependency);
                } else if (fields.stream().anyMatch(field -> field.isDependency() && field.getType().equals(dependency))
                        || isLikelyCollaboratorType(dependency, resolveImportedType(dependency, imports).orElse(dependency))) {
                    collaborators.add(dependency);
                } else {
                    internalDataTypes.add(dependency);
                }
                internalDependencies.add(dependency);
                allDependencies.add(dependency);
            });
            method.getExternalDependencies().forEach(reference -> {
                if (isAnnotationDependency(reference.getName(), imports)) {
                    annotationDependencies.add(reference.getName());
                } else if (isConstantSourceDependency(reference.getName(), method.getConstantsUsed())) {
                    constantSources.add(reference.getName());
                } else if (isExceptionLike(reference.getName(), imports)) {
                    exceptionTypes.add(reference.getName());
                } else if (isMethodCollaboratorDependency(reference.getName(), method, fields)) {
                    collaborators.add(reference.getName());
                } else {
                    externalDataTypes.add(reference.getName());
                }
                externalDependencies.putIfAbsent(reference.getName(), reference);
                allDependencies.add(reference.getName());
            });
            method.getConstantsUsed().stream()
                    .map(constant -> constant.contains(".") ? constant.substring(0, constant.indexOf('.')) : constant)
                    .filter(source -> !source.isBlank())
                    .forEach(source -> {
                        if (!isIgnoredDependencyType(source, imports)
                                && !isPrimitiveLike(source)
                                && isLikelyConstantSource(source, imports)) {
                            constantSources.add(source);
                        }
                    });
        });

        collaborators.removeAll(contractDependencies);
        collaborators.removeAll(constantSources);
        collaborators.removeAll(annotationDependencies);
        collaborators.removeIf(name -> isExceptionLike(name, imports));
        internalDataTypes.removeAll(contractDependencies);
        internalDataTypes.removeAll(constantSources);
        internalDataTypes.removeAll(annotationDependencies);
        internalDataTypes.removeIf(name -> isExceptionLike(name, imports));
        externalDataTypes.removeAll(contractDependencies);
        externalDataTypes.removeAll(constantSources);
        externalDataTypes.removeAll(annotationDependencies);
        externalDataTypes.removeIf(name -> isExceptionLike(name, imports));
        exceptionTypes.removeAll(annotationDependencies);
        exceptionTypes.removeAll(constantSources);

        Set<String> filteredExternalDependencyNames = externalDependencies.keySet().stream()
                .filter(name -> !annotationDependencies.contains(name))
                .filter(name -> !constantSources.contains(name))
                .filter(name -> !isPrimitiveLike(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DependencyReference> filteredExternalDependencies = externalDependencies.entrySet().stream()
                .filter(entry -> filteredExternalDependencyNames.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        return new TypeDependencySummary(
                internalDependencies,
                filteredExternalDependencies,
                collaborators,
                contractDependencies,
                internalDataTypes,
                externalDataTypes.stream()
                        .filter(name -> !isPrimitiveLike(name))
                        .filter(name -> !annotationDependencies.contains(name))
                        .filter(name -> !constantSources.contains(name))
                        .filter(name -> !exceptionTypes.contains(name))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                exceptionTypes,
                annotationDependencies,
                constantSources
        );
    }

    private TypeDependencyCollector createCollector(InternalTypeIndex internalTypeIndex,
                                                    List<String> imports,
                                                    String basePackage) {
        return new TypeDependencyCollector(
                new LinkedHashSet<>(),
                new LinkedHashSet<>(),
                new LinkedHashMap<>(),
                internalTypeIndex,
                imports,
                basePackage
        );
    }

    private void collectTypeDependency(String rawType, TypeDependencyCollector collector) {
        addDependencyType(
                rawType,
                collector.internalTypeIndex(),
                collector.imports(),
                collector.dependencies(),
                collector.internalDependencies(),
                collector.externalDependencies(),
                collector.basePackage()
        );
    }

    private void mergeTypeDependencyCollector(TypeDependencyCollector collector,
                                              Set<String> allDependencies,
                                              Set<String> internalDependencies,
                                              Map<String, DependencyReference> externalDependencies) {
        allDependencies.addAll(collector.dependencies());
        internalDependencies.addAll(collector.internalDependencies());
        collector.externalDependencies().forEach(externalDependencies::putIfAbsent);
    }

    private void addDependencyCategory(TypeDependencyCollector collector,
                                       Set<String> internalBucket,
                                       Set<String> externalBucket) {
        internalBucket.addAll(collector.internalDependencies());
        externalBucket.addAll(collector.externalDependencies().keySet());
    }

    private void categorizeExtendedContractType(String extendedType,
                                                InternalTypeIndex internalTypeIndex,
                                                List<String> imports,
                                                String basePackage,
                                                Set<String> allDependencies,
                                                Set<String> internalDependencies,
                                                Map<String, DependencyReference> externalDependencies,
                                                Set<String> contractDependencies,
                                                Set<String> internalDataTypes,
                                                Set<String> externalDataTypes) {
        String rawType = eraseGenericType(extendedType);
        TypeDependencyCollector rawCollector = createCollector(internalTypeIndex, imports, basePackage);
        collectTypeDependency(rawType, rawCollector);
        mergeTypeDependencyCollector(rawCollector, allDependencies, internalDependencies, externalDependencies);
        addDependencyCategory(rawCollector, contractDependencies, contractDependencies);

        String genericPortion = extractGenericPortion(extendedType);
        if (genericPortion.isBlank()) {
            return;
        }
        TypeDependencyCollector genericCollector = createCollector(internalTypeIndex, imports, basePackage);
        collectTypeDependency(genericPortion, genericCollector);
        mergeTypeDependencyCollector(genericCollector, allDependencies, internalDependencies, externalDependencies);
        addDependencyCategory(genericCollector, internalDataTypes, externalDataTypes);
    }

    private String extractGenericPortion(String typeName) {
        int genericStart = typeName.indexOf('<');
        int genericEnd = typeName.lastIndexOf('>');
        if (genericStart < 0 || genericEnd <= genericStart) {
            return "";
        }
        return typeName.substring(genericStart + 1, genericEnd).trim();
    }

    private boolean isAnnotationDependency(String name, List<String> imports) {
        return imports.stream()
                .filter(importedType -> importedType.endsWith("." + name))
                .anyMatch(importedType -> importedType.contains(".annotation.")
                        || importedType.endsWith(".Service")
                        || importedType.endsWith(".Repository")
                        || importedType.endsWith(".Component")
                        || importedType.endsWith(".Configuration")
                        || importedType.endsWith(".SpringBootApplication")
                        || importedType.endsWith(".EnableJpaRepositories")
                        || importedType.endsWith(".EntityScan")
                        || importedType.endsWith(".Import"));
    }

    private boolean shouldPromoteMethodAnnotationToTypeDependencies(String annotation, List<String> imports) {
        String fullName = resolveImportedType(annotation, imports).orElse(annotation);
        String normalized = fullName.toLowerCase(Locale.ROOT);
        if (normalized.contains(".web.bind.annotation.")
                || normalized.contains(".security.")
                || normalized.contains(".oas.annotations.")
                || normalized.contains(".validation.")) {
            return false;
        }
        return true;
    }

    private boolean isConstantSourceDependency(String name, List<String> constantsUsed) {
        return constantsUsed.stream().anyMatch(constant -> constant.startsWith(name + "."));
    }

    private boolean isMethodCollaboratorDependency(String dependency,
                                                   MethodAnalysis method,
                                                   List<FieldAnalysis> fields) {
        if (fields.stream().anyMatch(field -> field.isDependency() && field.getType().equals(dependency))) {
            return true;
        }
        String fullName = method.getExternalDependencies().stream()
                .filter(reference -> reference.getName().equals(dependency))
                .map(DependencyReference::getFullName)
                .findFirst()
                .orElse(dependency);
        return (method.getExternalCalls().stream().anyMatch(call -> call.startsWith(dependency + "."))
                || method.getInternalCalls().stream().anyMatch(call -> call.startsWith(dependency + ".")))
                && isLikelyCollaboratorType(dependency, fullName);
    }

    private boolean isLikelyCollaboratorType(String simpleName, String fullName) {
        String normalized = simpleName.toLowerCase(Locale.ROOT);
        String normalizedFullName = fullName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("service")
                || normalized.endsWith("repository")
                || normalized.endsWith("client")
                || normalized.endsWith("mapper")
                || normalized.endsWith("template")
                || normalized.endsWith("gateway")
                || normalized.endsWith("resolver")
                || normalized.endsWith("factory")
                || normalized.endsWith("provider")
                || normalized.endsWith("url")
                || normalizedFullName.contains(".service.")
                || normalizedFullName.contains(".repository.")
                || normalizedFullName.contains(".client.")
                || normalizedFullName.contains(".mapper.");
    }

    private boolean isLikelyConstantSource(String simpleName, List<String> imports) {
        String fullName = resolveImportedType(simpleName, imports).orElse(simpleName);
        String normalizedName = simpleName.toLowerCase(Locale.ROOT);
        String normalizedFullName = fullName.toLowerCase(Locale.ROOT);
        return normalizedFullName.contains(".constant")
                || normalizedFullName.contains(".constants.")
                || normalizedFullName.contains(".urlserviceconstant.")
                || normalizedName.endsWith("constants")
                || normalizedName.endsWith("constant")
                || normalizedName.endsWith("paths")
                || normalizedName.endsWith("path")
                || normalizedName.endsWith("uri")
                || normalizedName.endsWith("url");
    }

    private boolean isExceptionLike(String simpleName, List<String> imports) {
        String fullName = resolveImportedType(simpleName, imports).orElse(simpleName);
        String normalizedName = simpleName.toLowerCase(Locale.ROOT);
        String normalizedFullName = fullName.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith("exceptiontype") || normalizedFullName.endsWith("exceptiontype")) {
            return false;
        }
        return normalizedName.endsWith("exception")
                || normalizedFullName.contains(".exception")
                || normalizedFullName.endsWith("throwable");
    }

    private Map<String, String> buildVariableTypeMap(MethodDeclaration method,
                                                     Map<String, FieldAnalysis> fieldsByName,
                                                     List<String> imports) {
        Map<String, String> variableTypes = new LinkedHashMap<>();

        fieldsByName.values().forEach(field -> variableTypes.put(field.getName(), normalizeTypeName(field.getType(), imports)));
        method.getParameters().forEach(parameter -> variableTypes.put(parameter.getNameAsString(), normalizeTypeName(parameter.getType().asString(), imports)));
        method.findAll(VariableDeclarator.class).forEach(variableDeclarator ->
                variableTypes.put(variableDeclarator.getNameAsString(), normalizeTypeName(variableDeclarator.getType().asString(), imports)));

        return variableTypes;
    }

    private String normalizeTypeName(String rawType, List<String> imports) {
        String simpleType = eraseGenericType(rawType);
        if (simpleType.contains(".")) {
            simpleType = simpleType.substring(simpleType.lastIndexOf('.') + 1);
        }
        return resolveImportedType(simpleType, imports)
                .map(importedType -> importedType.substring(importedType.lastIndexOf('.') + 1))
                .orElse(simpleType);
    }

    private boolean isImportedSimpleType(String name, List<String> imports) {
        return resolveImportedType(name, imports).isPresent();
    }

    private boolean isIgnoredDependencyType(String typeName, List<String> imports) {
        String simpleName = eraseGenericType(typeName);
        if (Set.of(
                "String", "Object", "Class", "Void",
                "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character",
                "List", "Set", "Map", "Collection", "Collections", "Collectors", "Optional", "Stream"
        ).contains(simpleName)) {
            return true;
        }
        Optional<String> importedType = resolveImportedType(simpleName, imports);
        if (importedType.isPresent()) {
            return importedType.get().startsWith("java.") || importedType.get().startsWith("javax.");
        }
        return simpleName.startsWith("java.") || simpleName.startsWith("javax.");
    }

    private boolean isInternalDependency(String simpleName,
                                         String fullName,
                                         InternalTypeIndex internalTypeIndex,
                                         String basePackage) {
        if (!basePackage.isBlank() && fullName.startsWith(basePackage + ".")) {
            return true;
        }
        return internalTypeIndex.contains(simpleName) || internalTypeIndex.contains(fullName);
    }

    private String detectBasePackage(List<Path> javaFiles) throws IOException {
        List<String> packageNames = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            String packageName = packageName(parseCompilationUnit(javaFile));
            if (!packageName.isBlank()) {
                packageNames.add(packageName);
            }
        }
        if (packageNames.isEmpty()) {
            return "";
        }

        String[] prefix = packageNames.get(0).split("\\.");
        int commonLength = prefix.length;
        for (int i = 1; i < packageNames.size(); i++) {
            String[] parts = packageNames.get(i).split("\\.");
            commonLength = Math.min(commonLength, parts.length);
            int index = 0;
            while (index < commonLength && prefix[index].equals(parts[index])) {
                index++;
            }
            commonLength = index;
        }

        if (commonLength <= 0) {
            return "";
        }
        return String.join(".", java.util.Arrays.copyOf(prefix, commonLength));
    }

    private Optional<String> extractAssignedFieldName(AssignExpr assignExpr, Set<String> parameterNames) {
        String targetName;
        if (assignExpr.getTarget() instanceof FieldAccessExpr fieldAccessExpr && fieldAccessExpr.getScope() instanceof ThisExpr) {
            targetName = fieldAccessExpr.getNameAsString();
        } else if (assignExpr.getTarget() instanceof NameExpr nameExpr) {
            targetName = nameExpr.getNameAsString();
        } else {
            return Optional.empty();
        }

        if (assignExpr.getValue() instanceof NameExpr nameExpr && parameterNames.contains(nameExpr.getNameAsString())) {
            return Optional.of(targetName);
        }

        return Optional.empty();
    }

    private boolean hasAnyAnnotation(NodeWithAnnotations<?> node, Collection<String> annotationNames) {
        Set<String> existing = new HashSet<>(annotationNames(node));
        return annotationNames.stream().anyMatch(existing::contains);
    }

    private List<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();
    }

    private String resolveVisibility(CallableDeclaration<?> declaration) {
        if (declaration.isPublic()) {
            return "public";
        }
        if (declaration.isProtected()) {
            return "protected";
        }
        if (declaration.isPrivate()) {
            return "private";
        }
        return "package-private";
    }

    private EndpointResolution resolveEndpoint(TypeDeclaration<?> type,
                                               CompilationUnit unit,
                                               String packageName,
                                               List<String> imports,
                                               MethodDeclaration method,
                                               ConstantIndex constantIndex,
                                               Map<String, String> localStringConstants) {
        if (!"controller".equals(detectLayer(type, packageName))) {
            return EndpointResolution.empty();
        }

        List<MappingPath> rootPaths = extractMappingPaths(type.getAnnotations(), unit, imports, localStringConstants, constantIndex);
        if (rootPaths.isEmpty()) {
            rootPaths = List.of(MappingPath.literal(""));
        }

        MappingDetails methodMapping = extractMethodMapping(method, unit, imports, localStringConstants, constantIndex);
        if (methodMapping == null) {
            return EndpointResolution.empty();
        }

        List<MappingPath> methodPaths = methodMapping.paths().isEmpty() ? List.of(MappingPath.literal("")) : methodMapping.paths();
        List<String> endpoints = new ArrayList<>();
        List<String> endpointExpressions = new ArrayList<>();
        List<SymbolReference> endpointSymbols = new ArrayList<>();
        List<String> endpointSegments = new ArrayList<>();
        for (MappingPath rootPath : rootPaths) {
            for (MappingPath methodPath : methodPaths) {
                String resolvedEndpoint = joinPaths(rootPath.resolvedValue(), methodPath.resolvedValue());
                String symbolicEndpoint = joinSymbolicPaths(rootPath.rawValue(), methodPath.rawValue());
                endpoints.add(resolvedEndpoint);
                endpointSegments.addAll(splitPathSegments(resolvedEndpoint));
                Stream.concat(rootPath.symbols().stream(), methodPath.symbols().stream())
                        .forEach(endpointSymbols::add);
                if (symbolicEndpoint != null && !symbolicEndpoint.isBlank() && !symbolicEndpoint.equals(resolvedEndpoint)) {
                    endpointExpressions.add("[" + symbolicEndpoint + "]");
                }
            }
        }

        MappingPath primaryRootPath = rootPaths.get(0);
        return new EndpointResolution(
                methodMapping.httpMethods(),
                methodPaths.get(0).resolvedValue(),
                endpoints.stream().distinct().collect(Collectors.joining(" | ")),
                endpointExpressions.stream().distinct().collect(Collectors.joining(" | ")),
                primaryRootPath.resolvedValue(),
                primaryRootPath.rawValue().equals(primaryRootPath.resolvedValue()) ? null : primaryRootPath.rawValue(),
                deduplicateSymbolReferences(endpointSymbols),
                endpointSegments.stream().filter(segment -> !segment.isBlank()).distinct().toList(),
                buildEndpointAnalysis(methodMapping.httpMethods(),
                        primaryRootPath,
                        methodPaths.get(0),
                        endpoints.stream().distinct().collect(Collectors.joining(" | ")),
                        endpointExpressions.stream().distinct().collect(Collectors.joining(" | ")),
                        deduplicateSymbolReferences(endpointSymbols),
                        endpointSegments.stream().filter(segment -> !segment.isBlank()).distinct().toList())
        );
    }

    private EndpointAnalysis buildEndpointAnalysis(List<String> httpMethods,
                                                  MappingPath basePath,
                                                  MappingPath methodPath,
                                                  String fullPath,
                                                  String fullPathInCode,
                                                  List<SymbolReference> symbols,
                                                  List<String> segments) {
        String normalizedMethods = httpMethods == null || httpMethods.isEmpty()
                ? "REQUEST"
                : String.join("|", httpMethods);
        return EndpointAnalysis.builder()
                .httpMethods(httpMethods)
                .basePath(basePath.resolvedValue())
                .basePathInCode(basePath.rawValue().equals(basePath.resolvedValue()) ? null : basePath.rawValue())
                .methodPath(methodPath.resolvedValue())
                .methodPathInCode(methodPath.rawValue().equals(methodPath.resolvedValue()) ? null : methodPath.rawValue())
                .fullPath(fullPath)
                .fullPathInCode(fullPathInCode == null || fullPathInCode.isBlank() ? null : fullPathInCode)
                .canonicalEndpointId(normalizedMethods + " " + fullPath)
                .symbols(symbols)
                .segments(segments)
                .build();
    }

    private MappingDetails extractMethodMapping(MethodDeclaration method,
                                                CompilationUnit unit,
                                                List<String> imports,
                                                Map<String, String> localStringConstants,
                                                ConstantIndex constantIndex) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            List<MappingPath> paths = extractPathsFromMappingAnnotation(annotation, unit, imports, localStringConstants, constantIndex);
            if ("GetMapping".equals(name)) {
                return new MappingDetails(List.of("GET"), paths);
            }
            if ("PostMapping".equals(name)) {
                return new MappingDetails(List.of("POST"), paths);
            }
            if ("PutMapping".equals(name)) {
                return new MappingDetails(List.of("PUT"), paths);
            }
            if ("DeleteMapping".equals(name)) {
                return new MappingDetails(List.of("DELETE"), paths);
            }
            if ("PatchMapping".equals(name)) {
                return new MappingDetails(List.of("PATCH"), paths);
            }
            if ("RequestMapping".equals(name)) {
                return new MappingDetails(extractHttpMethods(annotation), paths);
            }
        }
        return null;
    }

    private List<String> extractHttpMethods(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normalAnnotationExpr)) {
            return List.of("REQUEST");
        }

        return normalAnnotationExpr.getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals("method"))
                .findFirst()
                .map(pair -> resolveHttpMethods(pair.getValue()))
                .filter(methods -> !methods.isEmpty())
                .orElse(List.of("REQUEST"));
    }

    private List<String> resolveHttpMethods(Expression expression) {
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return arrayInitializerExpr.getValues().stream()
                    .map(this::resolveHttpMethodToken)
                    .filter(token -> !token.isBlank())
                    .distinct()
                    .toList();
        }
        String token = resolveHttpMethodToken(expression);
        return token.isBlank() ? Collections.emptyList() : List.of(token);
    }

    private String resolveHttpMethodToken(Expression expression) {
        String raw = expression.toString();
        int lastDot = raw.lastIndexOf('.');
        return lastDot >= 0 ? raw.substring(lastDot + 1) : raw;
    }

    private List<MappingPath> extractMappingPaths(NodeList<AnnotationExpr> annotations,
                                                  CompilationUnit unit,
                                                  List<String> imports,
                                                  Map<String, String> localStringConstants,
                                                  ConstantIndex constantIndex) {
        for (AnnotationExpr annotation : annotations) {
            String annotationName = annotation.getNameAsString();
            if (isMappingAnnotation(annotationName)) {
                List<MappingPath> values = extractPathsFromMappingAnnotation(annotation, unit, imports, localStringConstants, constantIndex);
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return Collections.emptyList();
    }

    private boolean isMappingAnnotation(String annotationName) {
        return Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")
                .contains(annotationName);
    }

    private List<MappingPath> extractPathsFromMappingAnnotation(AnnotationExpr annotation,
                                                                CompilationUnit unit,
                                                                List<String> imports,
                                                                Map<String, String> localStringConstants,
                                                                ConstantIndex constantIndex) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotationExpr) {
            return resolveStringValues(singleMemberAnnotationExpr.getMemberValue(), unit, imports, localStringConstants, constantIndex);
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotationExpr) {
            for (String key : List.of("value", "path")) {
                Optional<List<MappingPath>> resolved = normalAnnotationExpr.getPairs().stream()
                        .filter(pair -> pair.getNameAsString().equals(key))
                        .findFirst()
                        .map(pair -> resolveStringValues(pair.getValue(), unit, imports, localStringConstants, constantIndex));
                if (resolved.isPresent() && !resolved.get().isEmpty()) {
                    return resolved.get();
                }
            }
        }
        return Collections.emptyList();
    }

    private List<MappingPath> resolveStringValues(Expression expression,
                                                  CompilationUnit unit,
                                                  List<String> imports,
                                                  Map<String, String> localStringConstants,
                                                  ConstantIndex constantIndex) {
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return arrayInitializerExpr.getValues().stream()
                        .flatMap(value -> resolveStringValues(value, unit, imports, localStringConstants, constantIndex).stream())
                    .filter(value -> !value.resolvedValue().isBlank())
                    .collect(Collectors.collectingAndThen(
                            Collectors.toMap(
                                    value -> value.rawValue() + "->" + value.resolvedValue(),
                                    value -> value,
                                    (left, right) -> left,
                                    LinkedHashMap::new
                            ),
                            map -> new ArrayList<>(map.values())
                    ));
        }

        return resolveMappingPath(expression, unit, imports, localStringConstants, constantIndex)
                .filter(value -> !value.resolvedValue().isBlank())
                .map(List::of)
                .orElse(Collections.emptyList());
    }

    private Optional<MappingPath> resolveMappingPath(Expression expression,
                                                     CompilationUnit unit,
                                                     List<String> imports,
                                                     Map<String, String> localStringConstants,
                                                     ConstantIndex constantIndex) {
        if (expression instanceof LiteralStringValueExpr literalStringValueExpr) {
            return Optional.of(MappingPath.literal(literalStringValueExpr.getValue()));
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<MappingPath> left = resolveMappingPath(binaryExpr.getLeft(), unit, imports, localStringConstants, constantIndex);
            Optional<MappingPath> right = resolveMappingPath(binaryExpr.getRight(), unit, imports, localStringConstants, constantIndex);
            if (left.isPresent() && right.isPresent()) {
                return Optional.of(MappingPath.composed(rawMappingExpression(expression), left.get(), right.get()));
            }
            return Optional.empty();
        }
        if (expression instanceof NameExpr nameExpr) {
            String fieldName = nameExpr.getNameAsString();
            if (localStringConstants.containsKey(fieldName)) {
                return Optional.of(MappingPath.symbolic(fieldName, localStringConstants.get(fieldName)));
            }
            return constantIndex.resolveSymbolBySimpleField(fieldName)
                    .map(symbolMatch -> MappingPath.of(fieldName,
                            symbolMatch.value(),
                            List.of(SymbolReference.builder().symbol(symbolMatch.symbol()).value(symbolMatch.value()).build())));
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return constantIndex.resolveSymbol(fieldAccessExpr.getScope().toString(), fieldAccessExpr.getNameAsString(), packageName(unit), imports)
                    .map(symbolMatch -> MappingPath.of(fieldAccessExpr.toString(),
                            symbolMatch.value(),
                            List.of(SymbolReference.builder().symbol(symbolMatch.symbol()).value(symbolMatch.value()).build())));
        }
        return resolveStringExpression(expression, unit, imports, localStringConstants, constantIndex)
                .map(resolvedValue -> MappingPath.of(rawMappingExpression(expression), resolvedValue, Collections.emptyList()));
    }

    private String rawMappingExpression(Expression expression) {
        if (expression instanceof LiteralStringValueExpr literalStringValueExpr) {
            return literalStringValueExpr.getValue();
        }
        return expression.toString();
    }

    private Optional<String> resolveStringExpression(Expression expression,
                                                     CompilationUnit unit,
                                                     List<String> imports,
                                                     Map<String, String> localStringConstants,
                                                     ConstantIndex constantIndex) {
        if (expression instanceof LiteralStringValueExpr literalStringValueExpr) {
            return Optional.of(literalStringValueExpr.getValue());
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = resolveStringExpression(binaryExpr.getLeft(), unit, imports, localStringConstants, constantIndex);
            Optional<String> right = resolveStringExpression(binaryExpr.getRight(), unit, imports, localStringConstants, constantIndex);
            if (left.isPresent() && right.isPresent()) {
                return Optional.of(left.get() + right.get());
            }
            return Optional.empty();
        }
        if (expression instanceof NameExpr nameExpr) {
            String fieldName = nameExpr.getNameAsString();
            if (localStringConstants.containsKey(fieldName)) {
                return Optional.ofNullable(localStringConstants.get(fieldName));
            }
            return constantIndex.resolveBySimpleField(fieldName);
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return constantIndex.resolve(fieldAccessExpr.getScope().toString(), fieldAccessExpr.getNameAsString(), packageName(unit), imports);
        }
        return Optional.empty();
    }

    private Map<String, String> extractLocalStringConstants(TypeDeclaration<?> type,
                                                            CompilationUnit unit,
                                                            List<String> imports,
                                                            ConstantIndex constantIndex) {
        Map<String, String> localConstants = new LinkedHashMap<>();
        for (FieldDeclaration fieldDeclaration : type.getFields()) {
            for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                Optional<String> resolved = variable.getInitializer()
                        .flatMap(initializer -> resolveStringExpression(initializer, unit, imports, localConstants, constantIndex));
                resolved.ifPresent(value -> localConstants.put(variable.getNameAsString(), value));
            }
        }
        return localConstants;
    }

    private String joinPaths(String rootPath, String methodPath) {
        String normalizedRoot = normalizePathSegment(rootPath);
        String normalizedMethod = normalizePathSegment(methodPath);

        if (normalizedRoot.isEmpty() && normalizedMethod.isEmpty()) {
            return "/";
        }
        if (normalizedRoot.isEmpty()) {
            return normalizedMethod;
        }
        if (normalizedMethod.isEmpty()) {
            return normalizedRoot;
        }
        if ("/".equals(normalizedRoot)) {
            return normalizedMethod;
        }
        return (normalizedRoot + "/" + normalizedMethod.substring(1)).replace("//", "/");
    }

    private String joinSymbolicPaths(String rootPath, String methodPath) {
        String normalizedRoot = normalizeSymbolicPathSegment(rootPath);
        String normalizedMethod = normalizeSymbolicPathSegment(methodPath);
        if (normalizedRoot.isEmpty() && normalizedMethod.isEmpty()) {
            return "/";
        }
        if (normalizedRoot.isEmpty()) {
            return normalizedMethod;
        }
        if (normalizedMethod.isEmpty()) {
            return normalizedRoot;
        }
        return normalizedRoot + " + " + normalizedMethod;
    }

    private String normalizeSymbolicPathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private String normalizePathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.replace("//", "/");
    }

    private List<String> splitPathSegments(String path) {
        String normalized = normalizePathSegment(path);
        if (normalized.isBlank() || "/".equals(normalized)) {
            return Collections.emptyList();
        }
        return Arrays.stream(normalized.substring(1).split("/"))
                .filter(segment -> !segment.isBlank())
                .toList();
    }

    private List<SymbolReference> deduplicateSymbolReferences(List<SymbolReference> symbols) {
        return new ArrayList<>(symbols.stream()
                .collect(Collectors.toMap(
                        symbol -> symbol.getSymbol() + "->" + symbol.getValue(),
                        symbol -> symbol,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values());
    }

    private String packageName(CompilationUnit unit) {
        return unit.getPackageDeclaration()
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse("");
    }

    private String qualify(CompilationUnit unit, String simpleName) {
        return unit.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString() + "." + simpleName)
                .orElse(simpleName);
    }

    private String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static final class InternalTypeIndex {
        private final Map<String, String> simpleNameToQualifiedName;
        private final Set<String> qualifiedNames;

        private InternalTypeIndex(Map<String, String> simpleNameToQualifiedName, Set<String> qualifiedNames) {
            this.simpleNameToQualifiedName = simpleNameToQualifiedName;
            this.qualifiedNames = qualifiedNames;
        }

        private boolean contains(String typeName) {
            if (typeName == null || typeName.isBlank()) {
                return false;
            }

            if (qualifiedNames.contains(typeName)) {
                return true;
            }

            String simpleName = typeName.contains(".")
                    ? typeName.substring(typeName.lastIndexOf('.') + 1)
                    : typeName;

            return simpleNameToQualifiedName.containsKey(simpleName);
        }
    }

    private static final class ConstantIndex {
        private final Map<String, String> qualifiedValues;
        private final Map<String, List<String>> simpleFieldOwners;
        private final Map<String, List<String>> reverseValues;

        private ConstantIndex(Map<String, String> qualifiedValues,
                              Map<String, List<String>> simpleFieldOwners,
                              Map<String, List<String>> reverseValues) {
            this.qualifiedValues = qualifiedValues;
            this.simpleFieldOwners = simpleFieldOwners;
            this.reverseValues = reverseValues;
        }

        private Optional<String> resolveBySimpleField(String fieldName) {
            List<String> owners = simpleFieldOwners.getOrDefault(fieldName, Collections.emptyList());
            if (owners.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(qualifiedValues.get(owners.get(0)));
        }

        private Optional<SymbolMatch> resolveSymbolBySimpleField(String fieldName) {
            List<String> owners = simpleFieldOwners.getOrDefault(fieldName, Collections.emptyList());
            if (owners.isEmpty()) {
                return Optional.empty();
            }
            String symbol = owners.get(0);
            return Optional.ofNullable(qualifiedValues.get(symbol))
                    .map(value -> new SymbolMatch(symbol, value));
        }

        private Optional<String> resolve(String owner, String fieldName, String packageName, List<String> imports) {
            return resolveSymbol(owner, fieldName, packageName, imports).map(SymbolMatch::value);
        }

        private Optional<SymbolMatch> resolveSymbol(String owner, String fieldName, String packageName, List<String> imports) {
            List<String> candidates = new ArrayList<>();
            candidates.add(owner + "." + fieldName);
            candidates.add(packageName + "." + owner + "." + fieldName);
            for (String importedType : imports) {
                if (importedType.endsWith("." + owner)) {
                    candidates.add(importedType + "." + fieldName);
                }
            }

            for (String candidate : candidates) {
                if (qualifiedValues.containsKey(candidate)) {
                    return Optional.ofNullable(qualifiedValues.get(candidate))
                            .map(value -> new SymbolMatch(candidate, value));
                }
            }
            return resolveSymbolBySimpleField(fieldName);
        }

        private List<SymbolEntry> symbolTable() {
            return qualifiedValues.entrySet().stream()
                    .map(entry -> SymbolEntry.builder().symbol(entry.getKey()).value(entry.getValue()).build())
                    .toList();
        }

        private Map<String, List<String>> reverseSymbolTable() {
            return reverseValues.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue()),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
        }
    }

    private record MappingPath(String rawValue, String resolvedValue, List<SymbolReference> symbols) {
        private static MappingPath of(String rawValue, String resolvedValue) {
            return new MappingPath(rawValue, resolvedValue, Collections.emptyList());
        }

        private static MappingPath of(String rawValue, String resolvedValue, List<SymbolReference> symbols) {
            return new MappingPath(rawValue, resolvedValue, symbols);
        }

        private static MappingPath literal(String value) {
            return new MappingPath(value, value, Collections.emptyList());
        }

        private static MappingPath symbolic(String symbol, String value) {
            return new MappingPath(symbol, value, List.of(SymbolReference.builder().symbol(symbol).value(value).build()));
        }

        private static MappingPath composed(String rawValue, MappingPath left, MappingPath right) {
            List<SymbolReference> symbols = Stream.concat(left.symbols().stream(), right.symbols().stream())
                    .toList();
            return new MappingPath(rawValue, left.resolvedValue() + right.resolvedValue(), symbols);
        }
    }

    private record MappingDetails(List<String> httpMethods, List<MappingPath> paths) {
    }

    private record EndpointResolution(List<String> httpMethods,
                                      String rawPath,
                                      String endpoint,
                                      String endpointInCode,
                                      String basePath,
                                      String basePathInCode,
                                      List<SymbolReference> endpointSymbols,
                                      List<String> endpointSegments,
                                      EndpointAnalysis endpointAnalysis) {
        private static EndpointResolution empty() {
            return new EndpointResolution(Collections.emptyList(), null, null, null, null, null,
                    Collections.emptyList(), Collections.emptyList(), null);
        }
    }

    private record SymbolMatch(String symbol, String value) {
    }

    private record CallBuckets(Set<String> internalCalls, Set<String> externalCalls, Set<String> frameworkCalls) {
    }

    private record TypeDependencyCollector(Set<String> dependencies,
                                           Set<String> internalDependencies,
                                           Map<String, DependencyReference> externalDependencies,
                                           InternalTypeIndex internalTypeIndex,
                                           List<String> imports,
                                           String basePackage) {
    }

    private record TypeDependencySummary(Set<String> internalDependencies,
                                         Map<String, DependencyReference> externalDependencies,
                                         Set<String> collaborators,
                                         Set<String> contractDependencies,
                                         Set<String> internalDataTypes,
                                         Set<String> externalDataTypes,
                                         Set<String> exceptionTypes,
                                         Set<String> annotationDependencies,
                                         Set<String> constantSources) {
    }
}
