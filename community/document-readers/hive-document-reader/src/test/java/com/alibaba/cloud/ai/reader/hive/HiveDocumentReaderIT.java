package com.alibaba.cloud.ai.reader.hive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

public class HiveDocumentReaderIT {

    private static HiveResource resource;
    private static HiveDocumentReader reader;

    @BeforeEach
    public void beforeEach() {
        resource = HiveResource.builder()
                .jdbcUrl(System.getProperty("hive.jdbc.url"))
                .username(System.getProperty("hive.username"))
                .password(System.getProperty("hive.password"))
                .database(System.getProperty("hive.database"))
                .table(System.getProperty("hive.table"))
                .poolSize(10)
                .connectTimeout(5000)
                .build();

        reader = HiveDocumentReader.builder()
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
        String hql = "SELECT * FROM " + resource.getDatabase() + "." + resource.getTable() +
                " WHERE column_name = 'test_value'";
        List<Document> queryDocs = reader.findByQuery(hql);
        printDocuments(queryDocs);
    }

    @Test
    public void findWithPagination() {
        String hql = "SELECT * FROM " + resource.getDatabase() + "." + resource.getTable();
        List<Document> pagedDocs = reader.findWithPagination(hql, 0, 10);
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