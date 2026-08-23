package com.javaee.fileservice.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;

@Getter
@AllArgsConstructor
public class FileDownloadResource implements AutoCloseable {
    private final InputStream inputStream;
    private final String fileName;
    private final String contentType;
    private final long contentLength;
    private final AutoCloseable resourcePermit;

    @Override
    public void close() throws IOException {
        try {
            inputStream.close();
        } finally {
            try {
                resourcePermit.close();
            } catch (Exception e) {
                throw new IOException("释放下载资源失败", e);
            }
        }
    }
}
