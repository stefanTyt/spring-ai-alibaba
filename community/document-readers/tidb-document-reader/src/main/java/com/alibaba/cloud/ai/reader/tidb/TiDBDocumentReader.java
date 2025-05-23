package com.alibaba.cloud.ai.reader.tidb;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TiDB文档读取器实现类
 */
public class TiDBDocumentReader implements DocumentReader, Closeable {
    private static final Logger log = LoggerFactory.getLogger(TiDBDocumentReader.class);

    private final JdbcTemplate jdbcTemplate;
    private final TiDBResource properties;
    private volatile boolean closed = false;

    // TiDB JDBC URL格式验证
    private static final Pattern TIDB_JDBC_URL_PATTERN = Pattern.compile("jdbc:mysql://[^/]+(:\\d+)?(/[^?]+)?(\\?.*)?");

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder模式构建器
     */
    public static class Builder {
        private JdbcTemplate jdbcTemplate;
        private TiDBResource resource;
        private DataSource dataSource;

        public Builder withJdbcTemplate(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            return this;
        }

        public Builder withResource(TiDBResource resource) {
            this.resource = resource;
            return this;
        }

        public Builder withDataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * 创建TiDB数据源
         */
        private static DataSource createDataSource(TiDBResource resource) {
            Assert.notNull(resource, "TiDBResource must not be null");
            Assert.hasText(resource.getJdbcUrl(), "TiDB JDBC URL must not be empty");

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(resource.getJdbcUrl());

            if (StringUtils.hasText(resource.getUsername())) {
                dataSource.setUsername(resource.getUsername());
            }
            if (StringUtils.hasText(resource.getPassword())) {
                dataSource.setPassword(resource.getPassword());
            }

            return dataSource;
        }

        public TiDBDocumentReader build() {
            Assert.notNull(resource, "TiDBResource must not be null");

            if (jdbcTemplate == null && dataSource == null) {
                dataSource = createDataSource(resource);
                jdbcTemplate = new JdbcTemplate(dataSource);
            } else if (jdbcTemplate == null) {
                jdbcTemplate = new JdbcTemplate(dataSource);
            }

            jdbcTemplate.setFetchSize(resource.getBatchSize());
            jdbcTemplate.setQueryTimeout(resource.getConnectTimeout() / 1000);

            return new TiDBDocumentReader(this);
        }
    }

    private TiDBDocumentReader(Builder builder) {
        this.properties = builder.resource;
        this.jdbcTemplate = builder.jdbcTemplate;

        validateConfiguration();
    }

    /**
     * 验证配置的有效性
     */
    private void validateConfiguration() {
        validateTiDBJdbcUrl(properties.getJdbcUrl());
        validateDatabaseAndTable(properties);
    }

    private void validateTiDBJdbcUrl(String url) {
        Assert.hasText(url, "TiDB JDBC URL must not be empty");
        if (!TIDB_JDBC_URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException("Invalid TiDB JDBC URL format");
        }
    }

    private void validateDatabaseAndTable(TiDBResource resource) {
        Assert.hasText(resource.getDatabase(), "Database name must not be empty");
        Assert.hasText(resource.getTable(), "Table name must not be empty");
    }

    /**
     * 执行查询并记录性能指标
     */
    private <T> T executeWithMetrics(String operation, Supplier<T> query) {
        checkState();
        Timer.Sample sample = Timer.start();
        try {
            log.debug("Executing operation: {}", operation);
            T result = query.get();
            log.debug("Operation completed successfully: {}", operation);
            return result;
        } catch (Exception e) {
            log.error("Operation failed: {}", operation, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 检查读取器状态
     */
    private void checkState() {
        if (closed) {
            throw new IllegalStateException("TiDBDocumentReader has been closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            synchronized (this) {
                if (!closed) {
                    try {
                        log.info("Closing TiDBDocumentReader...");
                        if (jdbcTemplate != null && jdbcTemplate.getDataSource() != null) {
                            jdbcTemplate.getDataSource().getConnection().close();
                        }
                        log.info("TiDBDocumentReader closed successfully");
                    } catch (SQLException e) {
                        log.error("Error while closing TiDBDocumentReader", e);
                    } finally {
                        closed = true;
                    }
                }
            }
        }
    }

    /**
     * 根据SQL查询语句查询文档
     */
    public List<Document> findByQuery(String sql) {
        if (!StringUtils.hasText(sql)) {
            return Collections.emptyList();
        }
        return executeWithMetrics("findByQuery", () -> processQuery(sql));
    }

    /**
     * 分页查询文档
     */
    public List<Document> findWithPagination(String sql, int offset, int limit) {
        Assert.isTrue(offset >= 0, "Offset must not be negative");
        Assert.isTrue(limit > 0, "Limit must be greater than 0");

        String paginatedSql = sql + " LIMIT " + limit + " OFFSET " + offset;
        return findByQuery(paginatedSql);
    }

    /**
     * 实现DocumentReader接口的get方法
     */
    @Override
    public List<Document> get() {
        validateConfiguration();
        String defaultQuery = buildDefaultQuery();
        return processQuery(defaultQuery);
    }

    /**
     * 构建默认查询语句
     */
    private String buildDefaultQuery() {
        StringBuilder query = new StringBuilder();
        query.append("SELECT * FROM ").append(properties.getDatabase())
                .append(".").append(properties.getTable());

        if (StringUtils.hasText(properties.getQuery())) {
            query.append(" WHERE ").append(properties.getQuery());
        }

        return query.toString();
    }

    /**
     * 处理查询结果并转换为文档格式
     */
    private List<Document> processQuery(String sql) {
        return executeWithMetrics("processQuery", () -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            return rows.stream()
                    .map(this::convertRowToDocument)
                    .collect(Collectors.toList());
        });
    }

    /**
     * 将数据行转换为文档对象
     */
    private Document convertRowToDocument(Map<String, Object> row) {
        // 将行数据转换为字符串形式的内容
        String content = row.values().stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.joining(" "));

        // 创建元数据
        Map<String, Object> metadata = new HashMap<>(row);
        metadata.put("database", properties.getDatabase());
        metadata.put("table", properties.getTable());

        return new Document(content, metadata);
    }
} 