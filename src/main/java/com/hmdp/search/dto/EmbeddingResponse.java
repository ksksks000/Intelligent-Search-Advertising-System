package com.hmdp.search.dto;

import lombok.Data;

import java.util.List;

@Data
public class EmbeddingResponse {
    private List<List<Float>> vectors;
    private Integer dim;
    private String model;
}
