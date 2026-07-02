package org.example.astjavaparser;

import org.example.astjavaparser.model.ConfigurationFileAnalysis;
import org.example.astjavaparser.model.ConfigurationKeyUsage;
import org.example.astjavaparser.model.ConfigurationPropertiesAnalysis;
import org.example.astjavaparser.model.ConfigurationPropertyEntry;
import org.example.astjavaparser.model.ExternalServiceDependency;
import org.example.astjavaparser.model.GroupedConfigurationProperty;
import org.example.astjavaparser.service.ConfigurationPropertiesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationPropertiesServiceTests {

    private final ConfigurationPropertiesService configurationPropertiesService = new ConfigurationPropertiesService();

    @Test
    void flattensDefaultAndEnvironmentYamlFiles(@TempDir Path tempDir) throws IOException {
        Path resourceRoot = tempDir.resolve("src/main/resources");
        Path javaRoot = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(resourceRoot);
        Files.createDirectories(javaRoot);

        Files.writeString(resourceRoot.resolve("application.yaml"), """
                server:
                  port: 8091
                spring:
                  datasource:
                    url: jdbc:postgresql://dbs-prerec.mc-intern.com:5432/mc-intra-timesheet-db
                    username: mc-intra-timesheet-user
                    password: shswh76WBVSFA
                  mail:
                    host: smtp.titan.email
                    port: 587
                  security:
                    oauth2:
                      resourceserver:
                        jwt:
                          jwt-set-uri: https://keycloak-rec.mc-intern.com/auth/realms/mc-intra-local/protocol/openid-connect/certs
                auth:
                  clientSecret: ${KEYCLOAK_CLIENT_SECRET:}
                content:
                  segment:
                    length: 2500
                aws:
                  credentials:
                    accessKey: AKIA3FLDZT774BKNIHMU
                    secretKey: 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
                userService: http://localhost:8081/
                deploymentService: http://localhost:8084/
                """);

        Files.writeString(resourceRoot.resolve("application-rec.yaml"), """
                URL_BOT: https://rec-bot.internal
                spring:
                  datasource:
                    url: jdbc:postgresql://rec-host:5432/app
                logging:
                  level:
                    root: DEBUG
                """);

        Files.writeString(javaRoot.resolve("McBotController.java"), """
                package com.example;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class McBotController {
                    @Value("${URL_BOT}")
                    private String URL_BOT;

                    @Value("${userService}")
                    private String userService;

                    @GetMapping("/present-users")
                    public String getPresentUsers() {
                        return URL_BOT + "/present-users";
                    }

                    @GetMapping("/weekly-report")
                    public String getWeeklyReport() {
                        return URL_BOT + "/weekly-report";
                    }

                    public String getUserServiceBase() {
                        return userService;
                    }
                }
                """);

        Files.writeString(javaRoot.resolve("NameUrl.java"), """
                package com.example;

                import org.springframework.beans.factory.annotation.Value;

                public class NameUrl {
                    @Value("${userService}")
                    private String userService;

                    public String getUserService() {
                        return userService;
                    }
                }
                """);

        Files.writeString(javaRoot.resolve("PointService.java"), """
                package com.example;

                public class PointService {
                    private NameUrl nameUrl = new NameUrl();

                    public String fetchUser() {
                        return nameUrl.getUserService() + "/users";
                    }
                }
                """);

        Files.writeString(javaRoot.resolve("RewardService.java"), """
                package com.example;

                import com.shared.config.NameUrl;

                public class RewardService {
                    private NameUrl nameUrl;

                    public String fetchUsers() {
                        return nameUrl.getUserService() + "/api/user/ids";
                    }
                }
                """);

        ConfigurationPropertiesAnalysis analysis = configurationPropertiesService.parseRepositoryProperties(tempDir);

        assertThat(analysis.getConfigFileCount()).isEqualTo(2);
        assertThat(analysis.getResourceRoots()).hasSize(1);

        ConfigurationFileAnalysis defaultFile = analysis.getConfigFiles().stream()
                .filter(ConfigurationFileAnalysis::isDefaultConfig)
                .findFirst()
                .orElseThrow();
        ConfigurationFileAnalysis recFile = analysis.getConfigFiles().stream()
                .filter(file -> file.getEnvironment().equals("rec"))
                .findFirst()
                .orElseThrow();

        Map<String, ConfigurationPropertyEntry> defaultProperties = defaultFile.getProperties().stream()
                .collect(Collectors.toMap(ConfigurationPropertyEntry::getKey, Function.identity()));

        assertThat(defaultFile.getFileName()).isEqualTo("application.yaml");
        assertThat(defaultFile.getEnvironment()).isEqualTo("default");
        assertThat(defaultProperties.get("server.port").getValue()).isEqualTo(8091);
        assertThat(defaultProperties.get("server.port").getCategory()).isEqualTo("server");
        assertThat(defaultProperties.get("spring.datasource.url").getCategory()).isEqualTo("database");
        assertThat(defaultProperties.get("spring.datasource.password").isSensitive()).isTrue();
        assertThat(defaultProperties.get("spring.datasource.password").getValue()).isEqualTo("****");
        assertThat(defaultProperties.get("spring.datasource.username").isSensitive()).isTrue();
        assertThat(defaultProperties.get("spring.datasource.username").getValue()).isEqualTo("mc-intra-timesheet-user");
        assertThat(defaultProperties.get("spring.mail.host").getCategory()).isEqualTo("external_service");
        assertThat(defaultProperties.get("spring.security.oauth2.resourceserver.jwt.jwt-set-uri").getCategory()).isEqualTo("security");
        assertThat(defaultProperties.get("spring.datasource.url").getDescription()).isEqualTo("PostgreSQL database connection URL");
        assertThat(defaultProperties.get("auth.clientSecret").isSensitive()).isTrue();
        assertThat(defaultProperties.get("auth.clientSecret").getValue()).isEqualTo("****");
        assertThat(defaultProperties.get("aws.credentials.accessKey").getValue()).isEqualTo("****");
        assertThat(defaultProperties.get("userService").getCategory()).isEqualTo("external_api");
        assertThat(defaultProperties.get("content.segment.length").getCategory()).isEqualTo("internal_config");
        assertThat(defaultProperties.get("deploymentService").getCategory()).isEqualTo("external_api");
        assertThat(defaultProperties.get("deploymentService").getDescription()).isEqualTo("Base URL for deployment microservice");
        assertThat(defaultProperties.get("userService").getUsedBy()).contains("McBotController.getUserServiceBase", "NameUrl.getUserService", "PointService.fetchUser");

        Map<String, ConfigurationPropertyEntry> recProperties = recFile.getProperties().stream()
                .collect(Collectors.toMap(ConfigurationPropertyEntry::getKey, Function.identity()));
        assertThat(recFile.isDefaultConfig()).isFalse();
        assertThat(recProperties.get("spring.datasource.url").getValue()).isEqualTo("jdbc:postgresql://rec-host:5432/app");
        assertThat(recProperties.get("logging.level.root").getCategory()).isEqualTo("logging");
        assertThat(recProperties.get("URL_BOT").getUsedBy()).containsExactlyInAnyOrder(
                "McBotController.getPresentUsers",
                "McBotController.getWeeklyReport"
        );

        Map<String, ConfigurationKeyUsage> usages = analysis.getPropertyUsages().stream()
                .collect(Collectors.toMap(ConfigurationKeyUsage::getKey, Function.identity()));
        assertThat(usages.get("URL_BOT").getUsedBy()).containsExactlyInAnyOrder(
                "McBotController.getPresentUsers",
                "McBotController.getWeeklyReport"
        );
        assertThat(usages.get("userService").getUsedBy()).contains("NameUrl.getUserService", "PointService.fetchUser", "RewardService.fetchUsers");

        Map<String, GroupedConfigurationProperty> grouped = analysis.getGroupedProperties().stream()
                .collect(Collectors.toMap(GroupedConfigurationProperty::getKey, Function.identity()));
        assertThat(grouped.get("spring.datasource.url").getEnvironments())
                .containsEntry("default", "jdbc:postgresql://dbs-prerec.mc-intern.com:5432/mc-intra-timesheet-db")
                .containsEntry("rec", "jdbc:postgresql://rec-host:5432/app");
        assertThat(grouped.get("URL_BOT").getUsedBy()).containsExactlyInAnyOrder(
                "McBotController.getPresentUsers",
                "McBotController.getWeeklyReport"
        );
        assertThat(grouped.get("URL_BOT").getEnvironments()).containsEntry("rec", "https://rec-bot.internal");
        assertThat(grouped.get("content.segment.length").getCategory()).isEqualTo("internal_config");

        assertThat(analysis.getConfigChunks()).anySatisfy(chunk -> {
            assertThat(chunk.getType()).isEqualTo("config");
            assertThat(chunk.getKey()).isEqualTo("userService");
            assertThat(chunk.getCategory()).isEqualTo("external_api");
            assertThat(chunk.getUsedBy()).contains("McBotController.getUserServiceBase", "PointService.fetchUser");
            assertThat(chunk.getValues()).containsEntry("default", "http://localhost:8081/");
        });

        Map<String, ExternalServiceDependency> serviceDependencies = analysis.getServiceDependencies().stream()
                .collect(Collectors.toMap(ExternalServiceDependency::getName, Function.identity()));
        assertThat(serviceDependencies.get("deployment-service").getConfigs()).containsExactly("deploymentService");
        assertThat(serviceDependencies.get("deployment-service").getCategory()).isEqualTo("external_api");
        assertThat(serviceDependencies.get("deployment-service").getDescription()).isEqualTo("Base URL for deployment microservice");
        assertThat(serviceDependencies.get("deployment-service").isInternal()).isTrue();
        assertThat(serviceDependencies.get("deployment-service").getEnvironments())
                .containsEntry("default", "http://localhost:8084/");
        assertThat(serviceDependencies.get("user-service").getUsedBy()).contains("McBotController.getUserServiceBase", "PointService.fetchUser", "RewardService.fetchUsers");
    }
}
