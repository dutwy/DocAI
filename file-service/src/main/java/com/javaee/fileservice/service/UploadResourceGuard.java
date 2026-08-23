package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.fileservice.config.FileTransferProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class UploadResourceGuard {

    private final Semaphore permits;
    private final FileTransferProperties properties;

    public UploadResourceGuard(FileTransferProperties properties) {
        this.properties = properties;
        this.permits = new Semaphore(properties.getMaxConcurrentStreams(), true);
    }

    public Permit acquire() {
        try {
            boolean acquired = permits.tryAcquire(
                    properties.getAcquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException("当前上传任务过多，请稍后重试");
            }
            return new Permit(permits);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("上传任务已中断");
        }
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean released;

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }
}
