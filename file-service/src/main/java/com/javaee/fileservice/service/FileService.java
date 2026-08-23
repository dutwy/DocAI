package com.javaee.fileservice.service;

/**
 * 文件变更服务接口。上传、下载和分片由专用的流式服务负责。
 */
public interface FileService {
    /**
     * 文件删除
     */
    void delete(String fileId);

    /**
     * 文件重命名
     */
    void rename(String fileId, String newName);

    /**
     * 文件移动
     */
    void move(String fileId, String targetPath);

    /**
     * 文件复制
     */
    String copy(String fileId, String targetPath);

}
