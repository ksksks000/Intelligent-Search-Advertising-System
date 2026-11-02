package com.hmdp.search.dto;

import lombok.Data;

import java.util.List;

@Data
public class EmbeddingRequest {
    private List<String> texts;
    /** query 会加 BGE 检索指令前缀，document 不加 */
    private String type = "document";
}
