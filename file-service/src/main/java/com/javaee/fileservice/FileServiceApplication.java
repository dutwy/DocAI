package com.javaee.fileservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件服务应用启动类
 */
@SpringBootApplication(scanBasePackages = {"com.javaee.fileservice", "com.javaee.common"})
@EnableDiscoveryClient
@EnableScheduling
@MapperScan(basePackages = "com.javaee.fileservice.mapper")
public class FileServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(FileServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(FileServiceApplication.class);
        Environment environment = application.run(args).getEnvironment();
        log.info("application_started name={} port={}", environment.getProperty("spring.application.name"),
                environment.getProperty("server.port"));
    }

}
