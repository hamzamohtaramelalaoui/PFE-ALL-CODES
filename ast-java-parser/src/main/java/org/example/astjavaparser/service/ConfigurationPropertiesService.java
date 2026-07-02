package org.example.astjavaparser.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.example.astjavaparser.model.ConfigurationChunk;
import org.example.astjavaparser.model.ConfigurationFileAnalysis;
import org.example.astjavaparser.model.ConfigurationKeyUsage;
import org.example.astjavaparser.model.ConfigurationPropertiesAnalysis;
import org.example.astjavaparser.model.ConfigurationPropertyEntry;
import org.example.astjavaparser.model.ExternalServiceDependency;
import org.example.astjavaparser.model.GroupedConfigurationProperty;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ConfigurationPropertiesService {
    private static final Pattern APPLICATION_YAML_PATTERN =
            Pattern.compile("^application(?:-([^.]+))?\\.ya?ml$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^:}]+)");

    private final JavaParser javaParser;

    public ConfigurationPropertiesService() {
        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(parserConfiguration);
    }

    public ConfigurationPropertiesAnalysis parseRepositoryProperties(Path repositoryRoot) throws IOException {
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        List<Path> resourceRoots = findResourceRoots(normalizedRoot);
        List<ConfigurationFileAnalysis> configFiles = new ArrayList<>();

        for (Path resourceRoot : resourceRoots) {
            for (Path configFile : listApplicationYamlFiles(resourceRoot)) {
                configFiles.add(parseConfigFile(configFile, normalizedRoot));
            }
        }

        Map<String, List<String>> usageMap = discoverPropertyUsages(normalizedRoot);
        List<ConfigurationKeyUsage> propertyUsages = usageMap.entrySet().stream()
                .map(entry -> ConfigurationKeyUsage.builder()
                        .key(entry.getKey())
                        .usedBy(entry.getValue())
                        .build())
                .sorted(Comparator.comparing(ConfigurationKeyUsage::getKey))
                .toList();

        configFiles.replaceAll(file -> enrichConfigFile(file, usageMap));
        configFiles.sort(Comparator
                .comparing(ConfigurationFileAnalysis::isDefaultConfig).reversed()
                .thenComparing(ConfigurationFileAnalysis::getEnvironment)
                .thenComparing(ConfigurationFileAnalysis::getFilePath));

        List<GroupedConfigurationProperty> groupedProperties = buildGroupedProperties(configFiles, usageMap);
        List<ExternalServiceDependency> serviceDependencies = buildServiceDependencies(groupedProperties);
        List<ConfigurationChunk> configChunks = groupedProperties.stream()
                .map(property -> ConfigurationChunk.builder()
                        .type("config")
                        .key(property.getKey())
                        .summary(property.getDescription())
                        .values(property.getEnvironments())
                        .usedBy(property.getUsedBy())
                        .category(property.getCategory())
                        .sensitive(property.isSensitive())
                        .build())
                .toList();

        return ConfigurationPropertiesAnalysis.builder()
                .repositoryRoot(normalizedRoot.toString())
                .resourceRoots(resourceRoots.stream().map(Path::toString).toList())
                .configFileCount(configFiles.size())
                .configFiles(configFiles)
                .propertyUsages(propertyUsages)
                .groupedProperties(groupedProperties)
                .serviceDependencies(serviceDependencies)
                .configChunks(configChunks)
                .build();
    }

    private List<Path> findResourceRoots(Path repositoryRoot) throws IOException {
        Path conventionalResourcesRoot = repositoryRoot.resolve("src").resolve("main").resolve("resources");
        if (Files.isDirectory(conventionalResourcesRoot)) {
            return List.of(conventionalResourcesRoot);
        }

        try (Stream<Path> stream = Files.walk(repositoryRoot, 6)) {
            List<Path> roots = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().equals("resources"))
                    .filter(path -> !path.toString().contains("\\target\\"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .sorted()
                    .toList();
            return roots.isEmpty() ? Collections.emptyList() : roots;
        }
    }

    private List<Path> listApplicationYamlFiles(Path resourceRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(resourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> APPLICATION_YAML_PATTERN.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }
    }

    private ConfigurationFileAnalysis parseConfigFile(Path configFile, Path repositoryRoot) throws IOException {
        String fileName = configFile.getFileName().toString();
        String environment = extractEnvironment(fileName).orElse("default");
        boolean defaultConfig = environment.equals("default");
        Map<String, ConfigurationPropertyEntry> flattenedProperties = new LinkedHashMap<>();
        Yaml yaml = new Yaml();

        try (Reader reader = Files.newBufferedReader(configFile)) {
            for (Object document : yaml.loadAll(reader)) {
                if (document instanceof Map<?, ?> map) {
                    flattenMap("", map, flattenedProperties);
                }
            }
        }

        return ConfigurationFileAnalysis.builder()
                .filePath(repositoryRoot.relativize(configFile.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                .fileName(fileName)
                .environment(environment)
                .defaultConfig(defaultConfig)
                .properties(new ArrayList<>(flattenedProperties.values()))
                .build();
    }

    private ConfigurationFileAnalysis enrichConfigFile(ConfigurationFileAnalysis file, Map<String, List<String>> usageMap) {
        List<ConfigurationPropertyEntry> enrichedProperties = file.getProperties().stream()
                .map(property -> ConfigurationPropertyEntry.builder()
                        .key(property.getKey())
                        .value(property.getValue())
                        .sensitive(property.isSensitive())
                        .category(property.getCategory())
                        .description(describeKey(property.getKey(), property.getValue()))
                        .usedBy(usageMap.getOrDefault(property.getKey(), List.of()))
                        .build())
                .toList();

        return ConfigurationFileAnalysis.builder()
                .filePath(file.getFilePath())
                .fileName(file.getFileName())
                .environment(file.getEnvironment())
                .defaultConfig(file.isDefaultConfig())
                .properties(enrichedProperties)
                .build();
    }

    private List<GroupedConfigurationProperty> buildGroupedProperties(List<ConfigurationFileAnalysis> configFiles,
                                                                     Map<String, List<String>> usageMap) {
        Map<String, List<ConfigurationPropertyEntryWithEnv>> propertiesByKey = new LinkedHashMap<>();
        for (ConfigurationFileAnalysis file : configFiles) {
            for (ConfigurationPropertyEntry property : file.getProperties()) {
                propertiesByKey.computeIfAbsent(property.getKey(), ignored -> new ArrayList<>())
                        .add(new ConfigurationPropertyEntryWithEnv(file.getEnvironment(), file.isDefaultConfig(), property));
            }
        }

        return propertiesByKey.entrySet().stream()
                .map(entry -> buildGroupedProperty(entry.getKey(), entry.getValue(), configFiles, usageMap))
                .sorted(Comparator.comparing(GroupedConfigurationProperty::getKey))
                .toList();
    }

    private GroupedConfigurationProperty buildGroupedProperty(String key,
                                                             List<ConfigurationPropertyEntryWithEnv> values,
                                                             List<ConfigurationFileAnalysis> configFiles,
                                                             Map<String, List<String>> usageMap) {
        ConfigurationPropertyEntry defaultEntry = values.stream()
                .filter(ConfigurationPropertyEntryWithEnv::defaultConfig)
                .map(ConfigurationPropertyEntryWithEnv::property)
                .findFirst()
                .orElse(null);

        Map<String, Object> environments = new LinkedHashMap<>();
        if (defaultEntry != null) {
            environments.put("default", defaultEntry.getValue());
        }

        configFiles.stream()
                .map(ConfigurationFileAnalysis::getEnvironment)
                .filter(environment -> !environment.equals("default"))
                .distinct()
                .forEach(environment -> {
                    Object effectiveValue = values.stream()
                            .filter(item -> item.environment().equals(environment))
                            .map(item -> item.property().getValue())
                            .findFirst()
                            .orElse(defaultEntry != null ? defaultEntry.getValue() : null);
                    if (effectiveValue != null) {
                        environments.put(environment, effectiveValue);
                    }
                });

        ConfigurationPropertyEntry representative = values.get(0).property();
        return GroupedConfigurationProperty.builder()
                .key(key)
                .description(describeKey(key, representative.getValue()))
                .category(values.stream()
                        .map(item -> item.property().getCategory())
                        .filter(category -> !category.isBlank())
                        .findFirst()
                        .orElse("internal_config"))
                .sensitive(values.stream().anyMatch(item -> item.property().isSensitive()))
                .environments(environments)
                .usedBy(usageMap.getOrDefault(key, List.of()))
                .build();
    }

    private List<ExternalServiceDependency> buildServiceDependencies(List<GroupedConfigurationProperty> groupedProperties) {
        Map<String, List<GroupedConfigurationProperty>> propertiesByService = new LinkedHashMap<>();
        groupedProperties.forEach(property -> deriveServiceDependencyKey(property)
                .ifPresent(serviceKey -> propertiesByService.computeIfAbsent(serviceKey, ignored -> new ArrayList<>()).add(property)));

        return propertiesByService.entrySet().stream()
                .map(entry -> buildServiceDependency(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ExternalServiceDependency::getName))
                .toList();
    }

    private ExternalServiceDependency buildServiceDependency(String serviceKey, List<GroupedConfigurationProperty> properties) {
        GroupedConfigurationProperty primaryProperty = selectPrimaryServiceProperty(properties);
        Set<String> usedBy = properties.stream()
                .flatMap(property -> property.getUsedBy().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return ExternalServiceDependency.builder()
                .type(resolveServiceDependencyType(primaryProperty))
                .name(serviceKey)
                .description(describeServiceProperty(primaryProperty))
                .category(primaryProperty.getCategory())
                .internal(isInternalService(primaryProperty))
                .configs(properties.stream().map(GroupedConfigurationProperty::getKey).sorted().toList())
                .environments(new LinkedHashMap<>(primaryProperty.getEnvironments()))
                .usedBy(new ArrayList<>(usedBy))
                .build();
    }

    private GroupedConfigurationProperty selectPrimaryServiceProperty(List<GroupedConfigurationProperty> properties) {
        return properties.stream()
                .sorted(Comparator
                        .comparing((GroupedConfigurationProperty property) -> !isPrimaryServiceProperty(property))
                        .thenComparing(GroupedConfigurationProperty::getKey))
                .findFirst()
                .orElseThrow();
    }

    private Optional<String> extractEnvironment(String fileName) {
        Matcher matcher = APPLICATION_YAML_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1));
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(String prefix,
                            Map<?, ?> source,
                            Map<String, ConfigurationPropertyEntry> flattenedProperties) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = prefix.isBlank() ? entry.getKey().toString() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nestedMap) {
                flattenMap(key, nestedMap, flattenedProperties);
            } else if (value instanceof Collection<?> collection) {
                flattenCollection(key, collection, flattenedProperties);
            } else {
                flattenedProperties.put(key, buildPropertyEntry(key, value));
            }
        }
    }

    private void flattenCollection(String prefix,
                                   Collection<?> values,
                                   Map<String, ConfigurationPropertyEntry> flattenedProperties) {
        int index = 0;
        for (Object value : values) {
            String key = prefix + "[" + index + "]";
            if (value instanceof Map<?, ?> nestedMap) {
                flattenMap(key, nestedMap, flattenedProperties);
            } else if (value instanceof Collection<?> nestedCollection) {
                flattenCollection(key, nestedCollection, flattenedProperties);
            } else {
                flattenedProperties.put(key, buildPropertyEntry(key, value));
            }
            index++;
        }
    }

    private ConfigurationPropertyEntry buildPropertyEntry(String key, Object rawValue) {
        boolean sensitive = isSensitiveKey(key);
        boolean shouldMask = shouldMaskValue(key);
        Object value = shouldMask && rawValue != null ? "****" : rawValue;
        String category = categorizeKey(key, rawValue);

        return ConfigurationPropertyEntry.builder()
                .key(key)
                .value(value)
                .sensitive(sensitive)
                .category(category)
                .description(describeKey(key, rawValue))
                .usedBy(List.of())
                .build();
    }

    private Map<String, List<String>> discoverPropertyUsages(Path repositoryRoot) throws IOException {
        Path sourceRoot = repositoryRoot.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(sourceRoot)) {
            return Collections.emptyMap();
        }

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }

        AccessorIndex accessorIndex = buildAccessorIndex(javaFiles);
        Map<String, Set<String>> usageMap = new LinkedHashMap<>();
        for (Path javaFile : javaFiles) {
            parseJavaFileForConfigUsage(javaFile, usageMap, accessorIndex);
        }

        return usageMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream().sorted().toList(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private AccessorIndex buildAccessorIndex(List<Path> javaFiles) throws IOException {
        Map<String, String> accessorPropertyKeys = new LinkedHashMap<>();
        for (Path javaFile : javaFiles) {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                continue;
            }
            CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
            for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
                Map<String, String> fieldKeys = extractFieldConfigKeys(type);
                for (MethodDeclaration method : type.getMethods()) {
                    extractAccessorFieldName(method)
                            .flatMap(fieldName -> Optional.ofNullable(fieldKeys.get(fieldName)))
                            .ifPresent(propertyKey -> accessorPropertyKeys.put(type.getNameAsString() + "." + method.getNameAsString(), propertyKey));
                }
            }
        }
        return new AccessorIndex(accessorPropertyKeys);
    }

    private void parseJavaFileForConfigUsage(Path javaFile,
                                             Map<String, Set<String>> usageMap,
                                             AccessorIndex accessorIndex) throws IOException {
        ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFile);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return;
        }
        CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
        for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
            Map<String, String> fieldKeys = extractFieldConfigKeys(type);
            Map<String, String> fieldTypes = extractFieldTypes(type);
            for (MethodDeclaration method : type.getMethods()) {
                String usageTarget = type.getNameAsString() + "." + method.getNameAsString();
                Map<String, String> variableTypes = buildVariableTypes(method, fieldTypes);
                fieldKeys.forEach((fieldName, propertyKey) -> {
                    if (referencesField(method, fieldName)) {
                        usageMap.computeIfAbsent(propertyKey, ignored -> new LinkedHashSet<>()).add(usageTarget);
                    }
                });

                method.findAll(MethodCallExpr.class).forEach(methodCall -> {
                    if ((methodCall.getNameAsString().equals("getProperty")
                            || methodCall.getNameAsString().equals("getRequiredProperty"))
                            && !methodCall.getArguments().isEmpty()
                            && methodCall.getArgument(0).isStringLiteralExpr()) {
                        String key = methodCall.getArgument(0).asStringLiteralExpr().getValue();
                        usageMap.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(usageTarget);
                    }
                    resolveAccessorPropertyKey(methodCall, variableTypes, accessorIndex)
                            .ifPresent(key -> usageMap.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(usageTarget));
                });
            }
        }
    }

    private Map<String, String> extractFieldTypes(TypeDeclaration<?> type) {
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (FieldDeclaration fieldDeclaration : type.getFields()) {
            fieldDeclaration.getVariables().forEach(variable ->
                    fieldTypes.put(variable.getNameAsString(), simplifyTypeName(variable.getType().asString())));
        }
        return fieldTypes;
    }

    private Map<String, String> buildVariableTypes(MethodDeclaration method, Map<String, String> fieldTypes) {
        Map<String, String> variableTypes = new LinkedHashMap<>(fieldTypes);
        method.getParameters().forEach(parameter ->
                variableTypes.put(parameter.getNameAsString(), simplifyTypeName(parameter.getType().asString())));
        method.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(variable ->
                variableTypes.put(variable.getNameAsString(), simplifyTypeName(variable.getType().asString())));
        return variableTypes;
    }

    private Optional<String> resolveAccessorPropertyKey(MethodCallExpr methodCallExpr,
                                                        Map<String, String> variableTypes,
                                                        AccessorIndex accessorIndex) {
        if (methodCallExpr.getScope().isEmpty()) {
            return Optional.empty();
        }
        String methodName = methodCallExpr.getNameAsString();
        Expression scope = methodCallExpr.getScope().orElseThrow();
        if (scope.isNameExpr()) {
            String scopeName = scope.asNameExpr().getNameAsString();
            String ownerType = variableTypes.getOrDefault(scopeName, scopeName);
            return accessorIndex.find(ownerType + "." + methodName)
                    .or(() -> derivePropertyKeyFromExternalAccessor(ownerType, methodName));
        }
        if (scope.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = scope.asFieldAccessExpr();
            if (fieldAccessExpr.getScope().isThisExpr()) {
                String ownerType = variableTypes.get(fieldAccessExpr.getNameAsString());
                if (ownerType != null) {
                    return accessorIndex.find(ownerType + "." + methodName)
                            .or(() -> derivePropertyKeyFromExternalAccessor(ownerType, methodName));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> derivePropertyKeyFromExternalAccessor(String ownerType, String methodName) {
        String normalizedOwner = ownerType.toLowerCase(Locale.ROOT);
        if (!(normalizedOwner.endsWith("url")
                || normalizedOwner.endsWith("urls")
                || normalizedOwner.endsWith("properties")
                || normalizedOwner.endsWith("property")
                || normalizedOwner.endsWith("config"))) {
            return Optional.empty();
        }
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Optional.of(decapitalize(methodName.substring(3)));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Optional.of(decapitalize(methodName.substring(2)));
        }
        return Optional.empty();
    }

    private Map<String, String> extractFieldConfigKeys(TypeDeclaration<?> type) {
        Map<String, String> fieldKeys = new LinkedHashMap<>();
        for (FieldDeclaration fieldDeclaration : type.getFields()) {
            Optional<String> propertyKey = extractPropertyKey(fieldDeclaration.getAnnotations());
            if (propertyKey.isEmpty()) {
                continue;
            }
            fieldDeclaration.getVariables().forEach(variable ->
                    fieldKeys.put(variable.getNameAsString(), propertyKey.orElseThrow()));
        }
        return fieldKeys;
    }

    private Optional<String> extractAccessorFieldName(MethodDeclaration method) {
        if (method.getBody().isEmpty() || !method.getParameters().isEmpty()) {
            return Optional.empty();
        }
        if (method.getBody().orElseThrow().getStatements().size() != 1) {
            return Optional.empty();
        }
        return method.findFirst(ReturnStmt.class)
                .flatMap(ReturnStmt::getExpression)
                .flatMap(this::extractReturnedFieldName);
    }

    private Optional<String> extractReturnedFieldName(Expression expression) {
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        if (expression.isFieldAccessExpr()) {
            FieldAccessExpr fieldAccessExpr = expression.asFieldAccessExpr();
            if (fieldAccessExpr.getScope().isThisExpr()) {
                return Optional.of(fieldAccessExpr.getNameAsString());
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractPropertyKey(NodeWithAnnotationsWrapper annotationsHolder) {
        return annotationsHolder.annotations().stream()
                .filter(annotation -> annotation.getNameAsString().equals("Value"))
                .map(this::extractValueAnnotationKey)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<String> extractPropertyKey(com.github.javaparser.ast.NodeList<AnnotationExpr> annotations) {
        return extractPropertyKey(new NodeWithAnnotationsWrapper(annotations));
    }

    private Optional<String> extractValueAnnotationKey(AnnotationExpr annotationExpr) {
        if (annotationExpr.isSingleMemberAnnotationExpr()) {
            Expression memberValue = annotationExpr.asSingleMemberAnnotationExpr().getMemberValue();
            if (memberValue.isStringLiteralExpr()) {
                Matcher matcher = VALUE_PLACEHOLDER_PATTERN.matcher(memberValue.asStringLiteralExpr().getValue());
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }

    private boolean referencesField(MethodDeclaration method, String fieldName) {
        boolean nameReferenced = method.findAll(NameExpr.class).stream()
                .anyMatch(nameExpr -> nameExpr.getNameAsString().equals(fieldName));
        if (nameReferenced) {
            return true;
        }
        return method.findAll(FieldAccessExpr.class).stream()
                .anyMatch(fieldAccessExpr -> fieldAccessExpr.getNameAsString().equals(fieldName)
                        && fieldAccessExpr.getScope().isThisExpr());
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("accesskey")
                || normalized.contains("secretkey")
                || normalized.endsWith(".username")
                || normalized.contains("adminusername");
    }

    private boolean shouldMaskValue(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("accesskey")
                || normalized.contains("secretkey");
    }

    private String categorizeKey(String key, Object value) {
        String normalized = key.toLowerCase(Locale.ROOT);
        String stringValue = value == null ? "" : value.toString().toLowerCase(Locale.ROOT);

        if (normalized.startsWith("spring.security")
                || normalized.startsWith("keycloak")
                || normalized.startsWith("auth.")
                || normalized.contains(".jwt")
                || normalized.contains("oauth2")) {
            return "security";
        }
        if (normalized.startsWith("spring.datasource")) {
            return "database";
        }
        if (normalized.startsWith("spring.mail")) {
            return "external_service";
        }
        if (normalized.startsWith("aws.")) {
            return "cloud";
        }
        if (normalized.startsWith("logging.")) {
            return "logging";
        }
        if (normalized.startsWith("server.")) {
            return "server";
        }
        if (normalized.startsWith("spring.cache") || normalized.startsWith("ehcache")) {
            return "cache";
        }
        if (looksLikeUrl(stringValue)) {
            if (normalized.contains("auth") || normalized.contains("keycloak") || normalized.contains("jwt")) {
                return "security";
            }
            if (!normalized.contains(".")) {
                return "external_api";
            }
            return "external_service";
        }
        return "internal_config";
    }

    private String describeKey(String key, Object value) {
        String normalized = key.toLowerCase(Locale.ROOT);
        String stringValue = value == null ? "" : value.toString().toLowerCase(Locale.ROOT);

        if (normalized.equals("server.port")) {
            return "HTTP server port used by the application";
        }
        if (normalized.equals("spring.datasource.url")) {
            if (stringValue.startsWith("jdbc:postgresql:")) {
                return "PostgreSQL database connection URL";
            }
            if (stringValue.startsWith("jdbc:mysql:")) {
                return "MySQL database connection URL";
            }
            return "Database connection URL";
        }
        if (normalized.endsWith(".username")) {
            return "Username used to authenticate against the configured service";
        }
        if (normalized.endsWith("clientid") || normalized.endsWith(".clientid")) {
            return "OAuth client identifier used to authenticate with the external identity provider";
        }
        if (normalized.endsWith("clientsecret") || normalized.endsWith(".clientsecret")) {
            return "OAuth client secret used to authenticate with the external identity provider";
        }
        if (normalized.endsWith(".realm")) {
            return "Security realm name used by the identity provider";
        }
        if (normalized.endsWith(".password")) {
            return "Password used to authenticate against the configured service";
        }
        if (normalized.equals("cors.allowed-origins")) {
            return "Allowed origins for browser cross-origin requests";
        }
        if (normalized.equals("spring.mail.host")) {
            return "SMTP host used for outgoing mail";
        }
        if (normalized.equals("spring.mail.port")) {
            return "SMTP port used for outgoing mail";
        }
        if (normalized.contains("jwt-set-uri")) {
            return "JWT key set endpoint used to validate access tokens";
        }
        if (normalized.startsWith("keycloak.")) {
            return "Keycloak configuration used by the application";
        }
        if (normalized.equals("auth.serverurl") || normalized.equals("keycloak.auth-server-url")) {
            return "Base URL for the Keycloak authentication server";
        }
        if (normalized.startsWith("auth.")) {
            return "Authentication service configuration";
        }
        if (normalized.startsWith("aws.")) {
            return "AWS infrastructure configuration";
        }
        if (normalized.startsWith("logging.level.")) {
            return "Configured logging level for " + key.substring("logging.level.".length());
        }
        if (normalized.equals("rest-template-config-timeout-connect")) {
            return "Connection timeout in milliseconds for outbound REST calls";
        }
        if (normalized.equals("rest-template-config-timeout-read")) {
            return "Read timeout in milliseconds for outbound REST calls";
        }
        if (normalized.equals("rest-template-config-timeout-headers-authorization")) {
            return "Authorization header value used by the shared REST template";
        }
        if (normalized.endsWith("service") && looksLikeUrl(stringValue)) {
            return "Base URL for " + humanizeServiceName(key) + " microservice";
        }
        if (normalized.endsWith("url") && looksLikeUrl(stringValue)) {
            return "Base URL for " + humanizeServiceName(key.substring(0, Math.max(1, key.length() - 3))) + " service";
        }
        if (normalized.endsWith(".host")) {
            return "Host used by " + humanizePropertyKey(key) + "";
        }
        if (looksLikeUrl(stringValue)) {
            return "Base URL used to reach an external service or API";
        }

        return humanizePropertyKey(key) + " configuration value";
    }

    private boolean looksLikeUrl(String value) {
        return value.startsWith("http://")
                || value.startsWith("https://")
                || value.startsWith("jdbc:");
    }

    private Optional<String> deriveServiceDependencyKey(GroupedConfigurationProperty property) {
        String normalizedKey = property.getKey().toLowerCase(Locale.ROOT);
        if (normalizedKey.startsWith("spring.mail.")) {
            return Optional.of("smtp-service");
        }
        if (normalizedKey.startsWith("spring.datasource.")) {
            return Optional.of(resolveDatabaseServiceName(property));
        }
        if (normalizedKey.startsWith("keycloak.")
                || normalizedKey.startsWith("auth.")
                || normalizedKey.contains("jwt-set-uri")
                || normalizedKey.equals("jwkseturi")) {
            return Optional.of("keycloak-service");
        }
        if (normalizedKey.equals("cors.allowed-origins")) {
            return Optional.empty();
        }
        boolean hasUrlValue = property.getEnvironments().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(this::looksLikeUrl);
        if (isPrimaryServiceProperty(property) && hasUrlValue) {
            return Optional.of(toServiceName(property.getKey()));
        }
        return Optional.empty();
    }

    private String toServiceName(String key) {
        String simpleKey = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        String normalized = simpleKey
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replace('_', '-')
                .replace('.', '-')
                .toLowerCase(Locale.ROOT);
        if (normalized.endsWith("-service")) {
            return normalized;
        }
        if (normalized.endsWith("service")) {
            return normalized.substring(0, normalized.length() - "service".length()) + "-service";
        }
        if (normalized.endsWith("-url")) {
            return normalized.substring(0, normalized.length() - 4) + "-service";
        }
        return normalized;
    }

    private String describeServiceProperty(GroupedConfigurationProperty property) {
        if (property.getDescription() != null && property.getDescription().startsWith("Base URL for ")) {
            return property.getDescription();
        }
        if (property.getKey().startsWith("spring.mail.")) {
            return "SMTP service used for outbound email delivery";
        }
        if (property.getKey().startsWith("spring.datasource.")) {
            return "Database service used by the application";
        }
        if (property.getKey().startsWith("keycloak.")
                || property.getKey().startsWith("auth.")
                || property.getKey().contains("jwt-set-uri")
                || property.getKey().equals("jwkSetUri")) {
            return "Keycloak authentication service used by the application";
        }
        return "External service dependency configured through " + property.getKey();
    }

    private boolean isInternalService(GroupedConfigurationProperty property) {
        return property.getEnvironments().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::toLowerCase)
                .allMatch(value -> value.contains("localhost")
                        || value.contains("127.0.0.1")
                        || value.contains(".svc")
                        || value.contains(".cluster.local"));
    }

    private boolean isPrimaryServiceProperty(GroupedConfigurationProperty property) {
        String normalizedKey = property.getKey().toLowerCase(Locale.ROOT);
        return normalizedKey.endsWith("service")
                || normalizedKey.endsWith("url")
                || normalizedKey.endsWith(".host")
                || normalizedKey.endsWith("auth-server-url")
                || normalizedKey.endsWith("jwt-set-uri");
    }

    private String resolveServiceDependencyType(GroupedConfigurationProperty property) {
        return switch (property.getCategory()) {
            case "database" -> "database";
            case "security" -> "security_service";
            default -> "external_service";
        };
    }

    private String resolveDatabaseServiceName(GroupedConfigurationProperty property) {
        return property.getEnvironments().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::toLowerCase)
                .filter(this::looksLikeUrl)
                .findFirst()
                .map(value -> {
                    if (value.startsWith("jdbc:postgresql:")) {
                        return "postgres-database";
                    }
                    if (value.startsWith("jdbc:mysql:")) {
                        return "mysql-database";
                    }
                    return "database-service";
                })
                .orElse("database-service");
    }

    private String humanizeServiceName(String key) {
        String serviceName = toServiceName(key);
        String words = serviceName.replace("-service", "").replace('-', ' ').trim();
        if (words.isBlank()) {
            words = serviceName.replace('-', ' ');
        }
        return words;
    }

    private String humanizePropertyKey(String key) {
        String leaf = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        String spaced = leaf
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('-', ' ')
                .replace('_', ' ')
                .trim();
        if (spaced.isBlank()) {
            return "Property";
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private String simplifyTypeName(String rawType) {
        String type = rawType;
        int genericStart = type.indexOf('<');
        if (genericStart >= 0) {
            type = type.substring(0, genericStart);
        }
        if (type.endsWith("...")) {
            type = type.substring(0, type.length() - 3);
        }
        if (type.contains(".")) {
            type = type.substring(type.lastIndexOf('.') + 1);
        }
        return type.trim();
    }

    private record ConfigurationPropertyEntryWithEnv(String environment,
                                                     boolean defaultConfig,
                                                     ConfigurationPropertyEntry property) {
    }

    private record NodeWithAnnotationsWrapper(com.github.javaparser.ast.NodeList<AnnotationExpr> annotations) {
    }

    private record AccessorIndex(Map<String, String> accessorPropertyKeys) {
        private Optional<String> find(String accessorSignature) {
            return Optional.ofNullable(accessorPropertyKeys.get(accessorSignature));
        }
    }
}
