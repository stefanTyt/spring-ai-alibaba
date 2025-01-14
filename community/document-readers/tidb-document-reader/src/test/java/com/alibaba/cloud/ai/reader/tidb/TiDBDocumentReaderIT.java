package com.alibaba.cloud.ai.reader.tidb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

public class TiDBDocumentReaderIT {

    private static TiDBResource resource;
    private static TiDBDocumentReader reader;

    @BeforeEach
    public void beforeEach() {
        resource = TiDBResource.builder()
                .jdbcUrl(System.getProperty("tidb.jdbc.url"))
                .username(System.getProperty("tidb.username"))
                .password(System.getProperty("tidb.password"))
                .database(System.getProperty("tidb.database"))
                .table(System.getProperty("tidb.table"))
                .poolSize(10)
                .connectTimeout(5000)
                .build();

        reader = TiDBDocumentReader.builder()
                .withResource(resource)
                .build();
    }

    @Test
    public void findAll() {
        List<Document> documents = reader.get();
        printDocuments(documents);
    }

    @Test
    public void findByQuery() {
        String sql = "SELECT * FROM " + resource.getDatabase() + "." + resource.getTable() +
                " WHERE column_name = 'test_value'";
        List<Document> queryDocs = reader.findByQuery(sql);
        printDocuments(queryDocs);
    }

    @Test
    public void findWithPagination() {
        String sql = "SELECT * FROM " + resource.getDatabase() + "." + resource.getTable();
        List<Document> pagedDocs = reader.findWithPagination(sql, 0, 10);
        printDocuments(pagedDocs);
    }

    private static void printDocuments(List<Document> documents) {
        if (documents.isEmpty()) {
            System.out.println("没有找到文档");
            return;
        }

        System.out.println("找到 " + documents.size() + " 个文档：");
        for (Document doc : documents) {
            System.out.println("- " + doc.getContent());
            System.out.println("  Metadata: " + doc.getMetadata());
        }
    }
} 