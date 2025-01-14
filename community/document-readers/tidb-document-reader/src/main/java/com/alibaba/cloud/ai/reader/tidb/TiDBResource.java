package com.alibaba.cloud.ai.reader.tidb;

import lombok.Builder;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * TiDB文档读取器配置属性类
 * 用于配置TiDB连接、文档处理和性能相关的参数
 *
 * @author AI
 * @version 1.0.0
 */
@Builder
public class TiDBResource implements Resource {

    /**
     * TiDB JDBC URL
     * 格式: jdbc:mysql://[host][:port][/database][?property=value]
     * 默认值: jdbc:mysql://localhost:4000
     */
    private String jdbcUrl;

    /**
     * TiDB用户名
     * 可选项，用于身份验证
     */
    private String username;

    /**
     * TiDB密码
     * 可选项，用于身份验证
     */
    private String password;

    /**
     * TiDB数据库名称
     * 必填项，指定要连接的数据库
     */
    private String database;

    /**
     * TiDB表名称
     * 必填项，指定要读取的表
     */
    private String table;

    /**
     * SQL查询语句
     * 可选项，用于过滤要读取的数据
     * 示例: "SELECT * FROM table WHERE status = 'active'"
     */
    private String query;

    /**
     * 文档分块大小（字符数）
     * 用于将大文档分割成小块进行处理
     * 默认值: 1000字符
     */
    @Builder.Default
    private int chunkSize = 1000;

    /**
     * 分块重叠大小（字符数）
     * 相邻分块之间的重叠字符数，用于保持上下文连贯性
     * 默认值: 200字符
     */
    @Builder.Default
    private int overlap = 200;

    /**
     * 批处理大小
     * 每次从TiDB批量读取的记录数量
     * 默认值: 100
     */
    @Builder.Default
    private int batchSize = 100;

    /**
     * TiDB连接池大小
     * 默认值: 10
     */
    @Builder.Default
    private int poolSize = 10;

    /**
     * TiDB连接超时时间（毫秒）
     * 默认值: 5000ms (5秒)
     */
    @Builder.Default
    private int connectTimeout = 5000;

    // Getters and Setters
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    @Override
    public boolean exists() {
        return false;
    }

    @Override
    public URL getURL() throws IOException {
        return null;
    }

    @Override
    public URI getURI() throws IOException {
        return null;
    }

    @Override
    public File getFile() throws IOException {
        return null;
    }

    @Override
    public long contentLength() throws IOException {
        return 0;
    }

    @Override
    public long lastModified() throws IOException {
        return 0;
    }

    @Override
    public Resource createRelative(String relativePath) throws IOException {
        return null;
    }

    @Override
    public String getFilename() {
        return "";
    }

    @Override
    public String getDescription() {
        return "";
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return null;
    }
} 