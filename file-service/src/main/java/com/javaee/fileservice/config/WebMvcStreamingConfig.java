package com.javaee.fileservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcStreamingConfig implements WebMvcConfigurer {

    private final FileTransferProperties properties;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        int streams = Math.max(1, properties.getMaxConcurrentStreams());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("file-stream-");
        executor.setCorePoolSize(streams);
        executor.setMaxPoolSize(streams);
        executor.setQueueCapacity(streams * 2);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(properties.getMergeLockTtl().toMillis());
    }
}
