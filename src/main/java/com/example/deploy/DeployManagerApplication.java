package com.example.deploy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.deploy.config.DeployProperties;

/**
 * Java 包远程发布 / 重启管理工具。
 */
@SpringBootApplication
@EnableConfigurationProperties(DeployProperties.class)
public class DeployManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeployManagerApplication.class, args);
    }
}
