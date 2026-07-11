package com.jinbon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JinbonApplication {

    public static void main(String[] args) {
        SpringApplication.run(JinbonApplication.class, args);
    }
}
