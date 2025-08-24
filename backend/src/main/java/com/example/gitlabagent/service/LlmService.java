package com.example.gitlabagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    @Value("${app.llm.base-url}")
    private String llmBaseUrl;

    @Value("${app.llm.chat-path:/api/chat}")
    private String chatPath;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public String chat(List<Map<String, Object>> messages) {
        String url = llmBaseUrl + chatPath;
        // Request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "llama3.1:8b"); // or "mistral", "codellama" etc.

        // Messages array (chat style)
        requestBody.put("messages", messages);
        requestBody.put("stream", false);

        // Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Build HTTP request
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        // Send POST request
        Map resp = rest.exchange(
                url,
                HttpMethod.POST,
                requestEntity,
                Map.class
        ).getBody();

        if (resp == null) throw new RuntimeException("LLM returned null response");

        // cast to Map
        Map<String, Object> messageMap = (Map<String, Object>) resp.get("message");

        Object message = (String) messageMap.get("content");
        if (message == null) {
            throw new RuntimeException("Invalid LLM response: " + message);
        }
        return String.valueOf(message).trim();
    }

    public Map<String,Object> chatToJsonMap(List<Map<String, Object>> messages) {
        try {
            String content = chat(messages);
            return mapper.readValue(content, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM JSON into Map", e);
        }
    }

    public <T> T chatToObject(List<Map<String, Object>> messages, Class<T> clazz) {
        try {
            String content = chat(messages);
            return mapper.readValue(content, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM JSON into " + clazz.getSimpleName(), e);
        }
    }
}
