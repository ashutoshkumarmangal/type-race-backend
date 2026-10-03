package com.typerush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.typerush.config.GameProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(GameProperties.class)
public class TypeRushApplication {

    public static void main(String[] args) {
        SpringApplication.run(TypeRushApplication.class, args);
    }
}