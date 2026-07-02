package org.example.astjavaparser;

import org.example.astjavaparser.model.RepositoryAnalysis;
import org.example.astjavaparser.model.MethodAnalysis;
import org.example.astjavaparser.model.TypeAnalysis;
import org.example.astjavaparser.service.CodeParserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class CodeParserServiceTests {

    private final CodeParserService codeParserService = new CodeParserService();

    @Test
    void parsesCurrentRepositoryStructure() throws IOException {
        RepositoryAnalysis analysis = codeParserService.parseRepository(Path.of("."));

        assertThat(analysis.getJavaFileCount()).isGreaterThanOrEqualTo(4);
        assertThat(analysis.getFiles()).isNotEmpty();

        TypeAnalysis serviceType = analysis.getFiles().stream()
                .flatMap(file -> file.getTypes().stream())
                .filter(type -> type.getName().equals("CodeParserService"))
                .findFirst()
                .orElseThrow();

        assertThat(serviceType.isStructureOnly()).isFalse();
        assertThat(serviceType.getMethods()).extracting("name").contains("parseRepository");
    }

    @Test
    void detectsServiceImplExceptionConfigAndControllerUrls(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example/service/impl"));
        Files.createDirectories(sourceRoot.resolve("com/example/controller"));
        Files.createDirectories(sourceRoot.resolve("com/example/constants"));
        Files.createDirectories(sourceRoot.resolve("com/example/config"));
        Files.createDirectories(sourceRoot.resolve("com/example/exception"));

        Files.writeString(sourceRoot.resolve("com/example/service/UserService.java"), """
                package com.example.service;

                public interface UserService {
                    String findByEmail(String email);
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/service/impl/UserServiceImpl.java"), """
                package com.example.service.impl;

                import com.example.service.UserService;

                public class UserServiceImpl implements UserService {
                    @Override
                    public String findByEmail(String email) {
                        return email;
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/constants/Paths.java"), """
                package com.example.constants;

                public final class Paths {
                    public static final String USERS = "/users";
                    public static final String GET_BY_EMAIL = "/by-email";
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/controller/UserController.java"), """
                package com.example.controller;

                import com.example.constants.Paths;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(Paths.USERS)
                public class UserController {
                    private static final String DETAILS = "/details";

                    @GetMapping(Paths.GET_BY_EMAIL)
                    public String getByEmail() {
                        return "ok";
                    }

                    @GetMapping(DETAILS)
                    public String getDetails() {
                        return "ok";
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/config/SecurityConfig.java"), """
                package com.example.config;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class SecurityConfig {
                    @Bean
                    public String token() {
                        return "token";
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/exception/UserNotFoundException.java"), """
                package com.example.exception;

                public class UserNotFoundException extends RuntimeException {
                }
                """);

        RepositoryAnalysis analysis = codeParserService.parseRepository(tempDir);

        TypeAnalysis implType = findType(analysis, "UserServiceImpl");
        assertThat(implType.getLayer()).isEqualTo("service");

        TypeAnalysis configType = findType(analysis, "SecurityConfig");
        assertThat(configType.getLayer()).isEqualTo("config");

        TypeAnalysis exceptionType = findType(analysis, "UserNotFoundException");
        assertThat(exceptionType.getType()).isEqualTo("exception");

        TypeAnalysis controllerType = findType(analysis, "UserController");
        MethodAnalysis getByEmail = controllerType.getMethods().stream()
                .filter(method -> method.getName().equals("getByEmail"))
                .findFirst()
                .orElseThrow();
        MethodAnalysis getDetails = controllerType.getMethods().stream()
                .filter(method -> method.getName().equals("getDetails"))
                .findFirst()
                .orElseThrow();

        assertThat(analysis.getSymbolTable()).extracting("symbol", "value").contains(
                tuple("com.example.constants.Paths.USERS", "/users"),
                tuple("com.example.constants.Paths.GET_BY_EMAIL", "/by-email")
        );
        assertThat(analysis.getReverseSymbolTable()).containsEntry("/users", List.of("com.example.constants.Paths.USERS"));
        assertThat(getByEmail.getEndpoint()).isEqualTo("/users/by-email");
        assertThat(getByEmail.getEndpointInCode()).isEqualTo("[Paths.USERS + Paths.GET_BY_EMAIL]");
        assertThat(getByEmail.getHttpMethods()).containsExactly("GET");
        assertThat(getByEmail.getRawPath()).isEqualTo("/by-email");
        assertThat(getByEmail.getBasePath()).isEqualTo("/users");
        assertThat(getByEmail.getBasePathInCode()).isEqualTo("Paths.USERS");
        assertThat(getByEmail.getEndpointAnalysis().getCanonicalEndpointId()).isEqualTo("GET /users/by-email");
        assertThat(getByEmail.getEndpointAnalysis().getFullPath()).isEqualTo("/users/by-email");
        assertThat(getByEmail.getEndpointAnalysis().getFullPathInCode()).isEqualTo("[Paths.USERS + Paths.GET_BY_EMAIL]");
        assertThat(getByEmail.getEndpointAnalysis().getMethodPath()).isEqualTo("/by-email");
        assertThat(getByEmail.getEndpointSymbols()).extracting("symbol", "value").containsExactly(
                tuple("com.example.constants.Paths.USERS", "/users"),
                tuple("com.example.constants.Paths.GET_BY_EMAIL", "/by-email")
        );
        assertThat(getByEmail.getEndpointSegments()).containsExactly("users", "by-email");
        assertThat(getDetails.getEndpoint()).isEqualTo("/users/details");
        assertThat(getDetails.getEndpointInCode()).isEqualTo("[Paths.USERS + DETAILS]");
        assertThat(getDetails.getRawPath()).isEqualTo("/details");
        assertThat(getDetails.getEndpointSymbols()).extracting("symbol", "value").containsExactly(
                tuple("com.example.constants.Paths.USERS", "/users"),
                tuple("DETAILS", "/details")
        );
        assertThat(getDetails.getEndpointSegments()).containsExactly("users", "details");
    }

    @Test
    void cleansMethodCallsAndResolvesExternalDependenciesFromImports(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example/config"));
        Files.createDirectories(sourceRoot.resolve("com/example/service"));

        Files.writeString(sourceRoot.resolve("com/example/service/UserService.java"), """
                package com.example.service;

                public class UserService {
                    public void execute() {
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/config/SecurityConfiguration.java"), """
                package com.example.config;

                import com.example.service.UserService;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.Customizer;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
                import org.springframework.security.config.http.SessionCreationPolicy;
                import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
                import org.springframework.security.web.SecurityFilterChain;

                @Configuration
                public class SecurityConfiguration {
                    private final UserService userService;

                    public SecurityConfiguration(UserService userService) {
                        this.userService = userService;
                    }

                    @Bean
                    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                        userService.execute();
                        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .httpBasic(Customizer.withDefaults())
                                .csrf(AbstractHttpConfigurer::disable)
                                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
                        return http.build();
                    }

                    private JwtAuthenticationConverter jwtAuthenticationConverter() {
                        return new JwtAuthenticationConverter();
                    }
                }
                """);

        RepositoryAnalysis analysis = codeParserService.parseRepository(tempDir);
        TypeAnalysis configType = findType(analysis, "SecurityConfiguration");
        MethodAnalysis method = configType.getMethods().stream()
                .filter(item -> item.getName().equals("securityFilterChain"))
                .findFirst()
                .orElseThrow();
        MethodAnalysis helperMethod = configType.getMethods().stream()
                .filter(item -> item.getName().equals("jwtAuthenticationConverter"))
                .findFirst()
                .orElseThrow();

        assertThat(method.getMethodCalls()).contains(
                "UserService.execute",
                "HttpSecurity.authorizeHttpRequests",
                "HttpSecurity.sessionManagement",
                "HttpSecurity.sessionManagement.sessionCreationPolicy",
                "HttpSecurity.httpBasic",
                "Customizer.withDefaults",
                "HttpSecurity.csrf",
                "HttpSecurity.oauth2ResourceServer",
                "HttpSecurity.oauth2ResourceServer.jwt",
                "HttpSecurity.oauth2ResourceServer.jwt.jwtAuthenticationConverter",
                "SecurityConfiguration.jwtAuthenticationConverter",
                "HttpSecurity.build"
        );
        assertThat(method.getMethodCalls()).doesNotContain("http.httpBasic(Customizer.withDefaults()).build");
        assertThat(method.getMethodCalls()).doesNotContain(
                "httpBasic.build",
                "build.httpBasic",
                "requests.anyRequest",
                "session.sessionCreationPolicy",
                "HttpSecurity.authorizeHttpRequests.anyRequest",
                "HttpSecurity.authorizeHttpRequests.authenticated",
                "HttpSecurity.authorizeHttpRequests.permitAll"
        );
        assertThat(method.getFrameworkCalls()).contains(
                "HttpSecurity.authorizeHttpRequests",
                "HttpSecurity.sessionManagement",
                "HttpSecurity.sessionManagement.sessionCreationPolicy",
                "HttpSecurity.httpBasic",
                "Customizer.withDefaults",
                "HttpSecurity.csrf",
                "HttpSecurity.oauth2ResourceServer",
                "HttpSecurity.oauth2ResourceServer.jwt",
                "HttpSecurity.oauth2ResourceServer.jwt.jwtAuthenticationConverter",
                "HttpSecurity.build"
        );
        assertThat(method.getExternalDependencies()).extracting("fullName").contains(
                "org.springframework.security.config.annotation.web.builders.HttpSecurity",
                "org.springframework.security.config.Customizer",
                "org.springframework.security.config.http.SessionCreationPolicy",
                "org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer"
        );
        assertThat(method.getDependencies()).contains("UserService", "HttpSecurity", "Customizer", "SessionCreationPolicy", "AbstractHttpConfigurer");
        assertThat(method.getInternalDependencies()).contains("UserService");
        assertThat(method.getVariableTypes()).containsEntry("http", "HttpSecurity").containsEntry("userService", "UserService");
        assertThat(helperMethod.getMethodCalls()).isEmpty();
        assertThat(helperMethod.getInstantiations()).contains("JwtAuthenticationConverter");
        assertThat(helperMethod.getMethodCalls()).doesNotContain(
                "JwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter.getClaimAsMap",
                "JwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter.get"
        );
    }

    @Test
    void detectsInternalDependenciesAndFiltersJdkNoise(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example/service"));
        Files.createDirectories(sourceRoot.resolve("com/example/controller"));

        Files.writeString(sourceRoot.resolve("com/example/service/UserService.java"), """
                package com.example.service;

                public class UserService {
                    public String loadUser(String id) {
                        return id;
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/controller/UserController.java"), """
                package com.example.controller;

                import com.example.service.UserService;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/users")
                public class UserController {
                    private final UserService userService;

                    public UserController(UserService userService) {
                        this.userService = userService;
                    }

                    @GetMapping("/search")
                    public String search(@RequestParam String id) {
                        String normalized = String.format("user-%s", id);
                        return userService.loadUser(normalized);
                    }
                }
                """);

        RepositoryAnalysis analysis = codeParserService.parseRepository(tempDir);
        TypeAnalysis controllerType = findType(analysis, "UserController");
        MethodAnalysis method = controllerType.getMethods().stream()
                .filter(item -> item.getName().equals("search"))
                .findFirst()
                .orElseThrow();

        assertThat(method.getDependencies()).contains("UserService").doesNotContain("String");
        assertThat(method.getInternalDependencies()).contains("UserService");
        assertThat(method.getMethodCalls()).contains("UserService.loadUser").doesNotContain("String.format");
        assertThat(method.getInternalCalls()).contains("UserService.loadUser");
        assertThat(method.getExternalDependencies()).extracting("name").doesNotContain("String");
        assertThat(method.getEndpointAnalysis().getCanonicalEndpointId()).isEqualTo("GET /users/search");
        assertThat(method.getEdges()).extracting("from", "to").contains(
                tuple("GET /users/search", "UserService.loadUser")
        );
    }

    @Test
    void analyzesApplicationRepositoriesAndInterfaceContracts(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example"));
        Files.createDirectories(sourceRoot.resolve("com/example/repositories"));
        Files.createDirectories(sourceRoot.resolve("com/example/services"));
        Files.createDirectories(sourceRoot.resolve("com/example/services/implementations"));

        Files.writeString(sourceRoot.resolve("com/example/Application.java"), """
                package com.example;

                import com.example.services.ExternalClient;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
                import org.springframework.context.annotation.Import;
                import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

                @SpringBootApplication(exclude = SecurityAutoConfiguration.class)
                @EnableJpaRepositories(basePackages = "com.example.repositories")
                @Import(ExternalClient.class)
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/repositories/RewardRepository.java"), """
                package com.example.repositories;

                import com.example.services.Reward;
                import java.util.Optional;
                import org.springframework.data.domain.Page;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.Query;

                public interface RewardRepository extends JpaRepository<Reward, Long> {
                    Page<Reward> findByUserId(String userId, Pageable pageable);

                    @Query("select r from Reward r where r.uuid = ?1")
                    Optional<Reward> findByUuid(String rewardId);
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/services/NotificationService.java"), """
                package com.example.services;

                public interface NotificationService {
                    String send(String message);
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/services/implementations/NotificationServiceImpl.java"), """
                package com.example.services.implementations;

                import com.example.services.NotificationService;

                public class NotificationServiceImpl implements NotificationService {
                    @Override
                    public String send(String message) {
                        return message;
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/services/Reward.java"), """
                package com.example.services;

                public class Reward {
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/services/ExternalClient.java"), """
                package com.example.services;

                public class ExternalClient {
                }
                """);

        RepositoryAnalysis analysis = codeParserService.parseRepository(tempDir);

        TypeAnalysis applicationType = findType(analysis, "Application");
        assertThat(applicationType.getLayer()).isEqualTo("application");
        assertThat(applicationType.getMethods()).extracting("name", "methodRole")
                .contains(tuple("main", "entrypoint"));
        assertThat(applicationType.getExternalDependencies()).contains(
                "SpringApplication",
                "SecurityAutoConfiguration"
        );
        assertThat(applicationType.getAnnotationDependencies()).contains(
                "SpringBootApplication",
                "EnableJpaRepositories",
                "Import"
        );
        assertThat(applicationType.getInternalDependencies()).contains("ExternalClient");

        TypeAnalysis repositoryType = findType(analysis, "RewardRepository");
        assertThat(repositoryType.getLayer()).isEqualTo("repository");
        assertThat(repositoryType.isStructureOnly()).isTrue();
        assertThat(repositoryType.getMethods()).extracting("name", "methodRole")
                .contains(
                        tuple("findByUserId", "query"),
                        tuple("findByUuid", "query")
                );
        assertThat(repositoryType.getMethods()).filteredOn(method -> method.getName().equals("findByUserId"))
                .singleElement()
                .satisfies(method -> assertThat(method.getDependencies()).contains("Pageable"));
        assertThat(repositoryType.getExternalDependencies()).contains("JpaRepository", "Pageable");
        assertThat(repositoryType.getInternalDependencies()).contains("Reward");
        assertThat(repositoryType.getAnnotationDependencies()).contains("Query");
        assertThat(repositoryType.getContractDependencies()).contains("JpaRepository");
        assertThat(repositoryType.getExternalDataTypes()).contains("Page", "Pageable");

        TypeAnalysis serviceImplType = findType(analysis, "NotificationServiceImpl");
        assertThat(serviceImplType.getLayer()).isEqualTo("service");
        assertThat(serviceImplType.getInternalDependencies()).contains("NotificationService");
        assertThat(serviceImplType.getContractDependencies()).contains("NotificationService");
    }

    @Test
    void splitsRepositoryContractFromGenericEntityType(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example/repositories"));

        Files.writeString(sourceRoot.resolve("com/example/repositories/NotificationRepository.java"), """
                package com.example.repositories;

                import com.shared.models.Notification;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.stereotype.Repository;

                @Repository
                public interface NotificationRepository extends JpaRepository<Notification, Long> {
                }
                """);

        RepositoryAnalysis analysis = codeParserService.parseRepository(tempDir);
        TypeAnalysis repositoryType = findType(analysis, "NotificationRepository");

        assertThat(repositoryType.getContractDependencies()).containsExactly("JpaRepository");
        assertThat(repositoryType.getExternalDataTypes()).contains("Notification");
        assertThat(repositoryType.getContractDependencies()).doesNotContain("Notification");
    }

    @Test
    void separatesServiceDependencyBucketsAndFiltersLowValueFrameworkCalls(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("com/example/service"));
        Files.createDirectories(sourceRoot.resolve("com/example/repository"));
        Files.createDirectories(sourceRoot.resolve("com/example/dto"));
        Files.createDirectories(sourceRoot.resolve("com/example/constants"));

        Files.writeString(sourceRoot.resolve("com/example/service/PointService.java"), """
                package com.example.service;

                public interface PointService {
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/repository/PointRepository.java"), """
                package com.example.repository;

                public interface PointRepository {
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/dto/UserResponse.java"), """
                package com.example.dto;

                public class UserResponse {
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/constants/NameUri.java"), """
                package com.example.constants;

                public final class NameUri {
                    public static final String USER = "/users/";
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/service/NameUrl.java"), """
                package com.example.service;

                public class NameUrl {
                    public String getUserService() {
                        return "http://user-service";
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/service/MCRestTemplateService.java"), """
                package com.example.service;

                import org.springframework.http.ResponseEntity;

                public class MCRestTemplateService {
                    public <T> ResponseEntity<T> getForObject(String url, Class<T> type) {
                        return null;
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("com/example/service/PointServiceImpl.java"), """
                package com.example.service;

                import com.example.constants.NameUri;
                import com.example.dto.UserResponse;
                import com.example.repository.PointRepository;
                import org.springframework.http.ResponseEntity;
                import org.springframework.stereotype.Service;

                @Service
                public class PointServiceImpl implements PointService {
                    private PointRepository pointRepository;
                    private NameUrl nameUrl;
                    private MCRestTemplateService mcRestTemplateService;

                    private UserResponse getUserFromUserService(String userUuid) {
                        String url = nameUrl.getUserService() + NameUri.USER + userUuid;
                        ResponseEntity<UserResponse> response = mcRestTemplateService.getForObject(url, UserResponse.class);
                        return response.getBody();
                    }
                }
                """);

        RepositoryAnalysis analysis = codeParserService.parseRepository(tempDir);
        TypeAnalysis serviceType = findType(analysis, "PointServiceImpl");
        MethodAnalysis helperMethod = serviceType.getMethods().stream()
                .filter(method -> method.getName().equals("getUserFromUserService"))
                .findFirst()
                .orElseThrow();

        assertThat(serviceType.getCollaborators()).contains("PointRepository", "NameUrl", "MCRestTemplateService");
        assertThat(serviceType.getContractDependencies()).contains("PointService");
        assertThat(serviceType.getInternalDataTypes()).contains("UserResponse");
        assertThat(serviceType.getConstantSources()).contains("NameUri");
        assertThat(serviceType.getConstantSources()).doesNotContain("System", "Sort", "TechnicalExceptionType");
        assertThat(serviceType.getAnnotationDependencies()).contains("Service");
        assertThat(serviceType.getExceptionTypes()).isEmpty();
        assertThat(serviceType.getExternalDependencies()).doesNotContain("Service", "NameUri", "void");
        assertThat(serviceType.getExternalDataTypes()).contains("ResponseEntity").doesNotContain("Service", "NameUri");
        assertThat(helperMethod.getMethodCalls()).contains("NameUrl.getUserService", "MCRestTemplateService.getForObject")
                .doesNotContain("ResponseEntity.getBody");
        assertThat(helperMethod.getFrameworkCalls()).doesNotContain("ResponseEntity.getBody");

        Files.writeString(sourceRoot.resolve("com/example/service/DebugService.java"), """
                package com.example.service;

                import com.example.constants.NameUri;
                import org.springframework.http.ResponseEntity;

                public class DebugService {
                    public void debug(ResponseEntity<String> response) {
                        System.out.println(NameUri.USER + response.getStatusCode());
                    }
                }
                """);

        RepositoryAnalysis debugAnalysis = codeParserService.parseRepository(tempDir);
        TypeAnalysis debugServiceType = findType(debugAnalysis, "DebugService");
        MethodAnalysis debugMethod = debugServiceType.getMethods().stream()
                .filter(method -> method.getName().equals("debug"))
                .findFirst()
                .orElseThrow();

        assertThat(debugMethod.getConstantsUsed()).contains("NameUri.USER").doesNotContain("System.out");
        assertThat(debugMethod.getMethodCalls()).doesNotContain("System.println", "ResponseEntity.getStatusCode");
    }

    @Test
    void detectsLombokConstructorInjectionAndFiltersControllerAnnotationAndAccessorNoise(@TempDir Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("org/example/appointmentrequest/controller"));
        Files.createDirectories(sourceRoot.resolve("org/example/appointmentrequest/service"));
        Files.createDirectories(sourceRoot.resolve("org/example/appointmentrequest/DTO"));
        Files.createDirectories(sourceRoot.resolve("org/example/appointmentrequest/security"));

        Files.writeString(sourceRoot.resolve("org/example/appointmentrequest/service/AppointmentRequestService.java"), """
                package org.example.appointmentrequest.service;

                import org.example.appointmentrequest.DTO.AppointmentRequestCreationDTO;

                public interface AppointmentRequestService {
                    void createAppointmentRequest(AppointmentRequestCreationDTO dto, Long clinicId, String cin);
                }
                """);

        Files.writeString(sourceRoot.resolve("org/example/appointmentrequest/DTO/AppointmentRequestCreationDTO.java"), """
                package org.example.appointmentrequest.DTO;

                public class AppointmentRequestCreationDTO {
                }
                """);

        Files.writeString(sourceRoot.resolve("org/example/appointmentrequest/security/UserPrincipal.java"), """
                package org.example.appointmentrequest.security;

                public class UserPrincipal {
                    public String getCin() {
                        return "cin";
                    }
                }
                """);

        Files.writeString(sourceRoot.resolve("org/example/appointmentrequest/controller/AppointmentRequestController.java"), """
                package org.example.appointmentrequest.controller;

                import io.swagger.v3.oas.annotations.Operation;
                import io.swagger.v3.oas.annotations.tags.Tag;
                import jakarta.validation.Valid;
                import lombok.RequiredArgsConstructor;
                import lombok.extern.slf4j.Slf4j;
                import org.example.appointmentrequest.DTO.AppointmentRequestCreationDTO;
                import org.example.appointmentrequest.security.UserPrincipal;
                import org.example.appointmentrequest.service.AppointmentRequestService;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.security.access.prepost.PreAuthorize;
                import org.springframework.security.core.annotation.AuthenticationPrincipal;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @Slf4j
                @RestController
                @RequestMapping("/api/appointments-request")
                @RequiredArgsConstructor
                @Tag(name = "appointment request")
                public class AppointmentRequestController {
                    private final AppointmentRequestService appointmentRequestService;

                    @PostMapping("/clinic/{clinicId}")
                    @Operation(summary = "Create a new appointment request for a clinic")
                    @PreAuthorize("hasRole('ADMIN')")
                    public ResponseEntity<Void> createAppointmentRequest(@PathVariable Long clinicId,
                                                                         @Valid @RequestBody AppointmentRequestCreationDTO dto,
                                                                         @AuthenticationPrincipal UserPrincipal principal) {
                        String cin = principal.getCin();
                        appointmentRequestService.createAppointmentRequest(dto, clinicId, cin);
                        return new ResponseEntity<>(HttpStatus.CREATED);
                    }
                }
                """);

        RepositoryAnalysis analysis = codeParserService.parseRepository(tempDir);
        TypeAnalysis controllerType = findType(analysis, "AppointmentRequestController");
        MethodAnalysis method = controllerType.getMethods().stream()
                .filter(item -> item.getName().equals("createAppointmentRequest"))
                .findFirst()
                .orElseThrow();

        assertThat(controllerType.getFields()).singleElement()
                .satisfies(field -> assertThat(field.isConstructorInjected()).isTrue());
        assertThat(controllerType.getAnnotationDependencies()).contains("Slf4j", "RequiredArgsConstructor", "Tag");
        assertThat(controllerType.getAnnotationDependencies()).doesNotContain("Operation", "PreAuthorize");
        assertThat(method.getMethodCalls()).contains("AppointmentRequestService.createAppointmentRequest")
                .doesNotContain("UserPrincipal.getCin");
        assertThat(method.getEdges()).extracting("to")
                .contains("AppointmentRequestService.createAppointmentRequest")
                .doesNotContain("UserPrincipal.getCin");
    }

    private TypeAnalysis findType(RepositoryAnalysis analysis, String typeName) {
        return analysis.getFiles().stream()
                .flatMap(file -> file.getTypes().stream())
                .filter(type -> type.getName().equals(typeName))
                .findFirst()
                .orElseThrow();
    }
}
