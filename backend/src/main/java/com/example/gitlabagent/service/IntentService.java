package com.example.gitlabagent.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class IntentService {

    private final LlmService llmService;

    public IntentService(LlmService llmService) {
        this.llmService = llmService;
    }

    public Map<String,Object> extractIntent(String userPrompt) {
        List<Map<String,Object>> messages = List.of(
            Map.of("role","system","content",
                "You are an assistant that extracts intent from user queries. " +
                "Return ONLY JSON in the format: { \"intent\": \"...\", \"parameters\": { ... } }. " +
                "Valid intents: list_merge_requests, review_merge_request, help. " +
                "- list_merge_requests: no params. " +
                "- review_merge_request: requires { \"mr_id\": number }. " +
                "If intent is unknown, reply { \"intent\": \"unknown\" }."
            ),
            Map.of("role","user","content", userPrompt)
        );
        return llmService.chatToJsonMap(messages);
    }
}
