package org.example.astjavaparser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AstJavaParserApplication {

    public static void main(String[] args) {
        SpringApplication.run(AstJavaParserApplication.class, args);
    }

}
