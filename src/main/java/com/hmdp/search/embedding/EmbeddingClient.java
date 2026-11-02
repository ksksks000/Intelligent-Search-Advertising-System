package com.hmdp.search.embedding;

import com.hmdp.search.dto.EmbeddingRequest;
import com.hmdp.search.dto.EmbeddingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 调用独立的 Python embedding 服务，不在此处加载模型。
 * 服务不可用时抛出 EmbeddingException，由搜索层降级为纯倒排。
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final int dim;
    private final ExecutorService queryTimeoutExecutor = Executors.newFixedThreadPool(16, runnable -> {
        Thread thread = new Thread(runnable, "embedding-query-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public EmbeddingClient(RestTemplateBuilder builder,
                           @Value("${hmdp.embedding.url:http://127.0.0.1:8090}") String baseUrl,
                           @Value("${hmdp.embedding.dim:512}") int dim,
                           @Value("${hmdp.embedding.connect-timeout-ms:500}") long connectTimeoutMs,
                           @Value("${hmdp.embedding.read-timeout-ms:8000}") long readTimeoutMs) {
        this.baseUrl = baseUrl;
        this.dim = dim;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    public float[] encodeQuery(String text) {
        return encodeBatch(Collections.singletonList(text), "query").get(0);
    }

    /**
     * 搜索在线查询专用：超过 timeout 直接放弃，调用方降级纯倒排，不阻塞请求。
     */
    public float[] encodeQueryWithTimeout(String text, long timeoutMs) {
        Future<float[]> future = queryTimeoutExecutor.submit(() -> encodeQuery(text));
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("DEGRADED: query embedding timeout {}ms, text={}", timeoutMs,
                    text.length() > 40 ? text.substring(0, 40) : text);
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        queryTimeoutExecutor.shutdownNow();
    }

    public List<float[]> encodeDocuments(List<String> texts) {
        return encodeBatch(texts, "document");
    }

    public List<float[]> encodeBatch(List<String> texts, String type) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        EmbeddingRequest request = new EmbeddingRequest();
        request.setTexts(texts);
        request.setType(type);
        try {
            EmbeddingResponse response = restTemplate.postForObject(
                    baseUrl + "/encode", request, EmbeddingResponse.class);
            return validate(response, texts.size());
        } catch (Exception e) {
            log.warn("embedding 服务不可用，type={}, texts={}, error={}", type, texts.size(), e.getMessage());
            throw new EmbeddingException("embedding service unavailable", e);
        }
    }

    public boolean isHealthy() {
        try {
            restTemplate.getForObject(baseUrl + "/health", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<float[]> validate(EmbeddingResponse response, int expected) {
        if (response == null || response.getVectors() == null
                || response.getVectors().size() != expected) {
            throw new EmbeddingException("embedding response size mismatch");
        }
        List<float[]> result = new ArrayList<>(expected);
        for (List<Float> vector : response.getVectors()) {
            if (vector == null || vector.size() != dim) {
                throw new EmbeddingException("embedding dim mismatch, expected=" + dim);
            }
            float[] array = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                array[i] = vector.get(i);
            }
            result.add(array);
        }
        return result;
    }

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
