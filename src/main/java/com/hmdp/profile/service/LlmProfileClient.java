package com.hmdp.profile.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.ProfileProperties;
import com.hmdp.profile.dto.AiProfileResult;
import com.hmdp.profile.dto.BehaviorSummary;
import com.hmdp.profile.dto.ProfileTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 画像客户端：OpenAI-compatible /chat/completions（也可直接返回画像 JSON）。
 * URL 为空或调用失败返回 null，由 ProfileGenerationService 走结构化降级。
 */
@Slf4j
@Component
public class LlmProfileClient {

    @Resource
    private ProfileProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public AiProfileResult generate(BehaviorSummary summary, String prompt) {
        if (properties.getLlmUrl() == null || properties.getLlmUrl().trim().isEmpty()) {
            return null;
        }
        try {
            String body = objectMapper.writeValueAsString(new Request(
                    properties.getLlmModel(), prompt));
            HttpURLConnection connection = (HttpURLConnection) new URL(properties.getLlmUrl())
                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout((int) properties.getLlmTimeoutMs());
            connection.setReadTimeout((int) properties.getLlmTimeoutMs());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                log.warn("LLM 画像调用失败 status={}", status);
                return null;
            }
            String response;
            try (InputStream in = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line);
                }
                response = text.toString();
            }
            return parse(response);
        } catch (Exception e) {
            log.warn("LLM 画像调用异常: {}", e.getMessage());
            return null;
        }
    }

    private AiProfileResult parse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(stripFences(response));
        JsonNode content = root;
        if (root.has("choices")) {
            JsonNode choices = root.get("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    String text = stripFences(message.get("content").asText());
                    content = objectMapper.readTree(text);
                }
            }
        }
        AiProfileResult result = new AiProfileResult();
        result.setNarrative(content.path("narrative").asText(""));
        result.setSemanticQuery(content.path("semantic_query").asText(""));
        result.setInterestTags(tags(content.path("interest_tags")));
        result.setAdAffinity(tags(content.path("ad_affinity")));
        List<String> avoid = new ArrayList<>();
        if (content.path("avoid_tags").isArray()) {
            content.path("avoid_tags").forEach(node -> avoid.add(node.asText()));
        }
        result.setAvoidTags(avoid);
        return result;
    }

    /**
     * 真实 LLM 常返回 ```json ... ``` 或前后带说明文字；去掉代码块围栏。
     */
    private String stripFences(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstBrace = cleaned.indexOf('{');
            if (firstBrace >= 0) {
                cleaned = cleaned.substring(firstBrace);
            } else {
                int firstLine = cleaned.indexOf('\n');
                if (firstLine > 0) {
                    cleaned = cleaned.substring(firstLine + 1);
                }
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
        }
        return cleaned.trim();
    }

    private List<ProfileTag> tags(JsonNode node) {
        List<ProfileTag> tags = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return tags;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                tags.add(new ProfileTag(item.asText(), 1D));
            } else {
                String tag = item.path("tag").asText();
                double weight = item.path("weight").asDouble(1D);
                if (!tag.isEmpty()) {
                    tags.add(new ProfileTag(tag, weight));
                }
            }
        }
        return tags;
    }

    private static final class Request {
        public String model;
        public String prompt;

        private Request(String model, String prompt) {
            this.model = model;
            this.prompt = prompt;
        }
    }
}
