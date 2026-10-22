package com.hdsl.server;

import com.hdsl.server.config.PclProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** PCL-DSH-Linux WebUI server entry point. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(PclProperties.class)
public class PclDshLinuxApplication {
    public static void main(String[] args) {
        SpringApplication.run(PclDshLinuxApplication.class, args);
    }
}
