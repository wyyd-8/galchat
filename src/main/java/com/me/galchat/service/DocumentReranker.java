package com.me.galchat.service;

import org.springframework.ai.document.Document;

import java.util.List;

public interface DocumentReranker {

    List<Document> rerank(String query, List<Document> documents, int topN);
}
