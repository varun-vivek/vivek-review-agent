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
                        "You are an intent extraction assistant for GitLab operations.\n" +
                                "\n" +
                                "Your job:\n" +
                                "- Read the user query in natural language.\n" +
                                "- Identify the intent and parameters.\n" +
                                "- Always return ONLY valid JSON (no markdown, no explanations, no code fences).\n" +
                                "\n" +
                                "Valid intents:\n" +
                                "1. list_merge_requests → parameters: {}\n" +
                                "2. review_merge_request → parameters: { \"mr_id\": number }\n" +
                                "\n" +
                                "Repositories:\n" +
                                "- \"inquiro dev\" or just \"dev\" → project_id = 12345\n" +
                                "- \"inquiro test\" or just \"test\" → project_id = 67890\n" +
                                "\n" +
                                "Extraction rules:\n" +
                                "- If the query mentions 'inquiro dev' OR 'dev', set project_id=12345.\n" +
                                "- If the query mentions 'inquiro test' OR 'test', set project_id=67890.\n" +
                                "- If no repo is mentioned, project_id=null.\n" +
                                "- Always include `project_id`, `intent`, and `parameters` keys in output.\n" +
                                "- If an mr_id is mentioned, extract it as a number inside parameters.\n" +
                                "- If a creator name is mentioned (e.g., 'vivek'), include it under parameters as { \"created_by\": \"vivek\" }.\n" +
                                "- If the intent is unclear, return { \"intent\": \"unknown\", \"project_id\": null, \"parameters\": {} }.\n" +
                                "\n" +
                                "Output schema:\n" +
                                "{\n" +
                                "  \"project_id\": number | null,\n" +
                                "  \"intent\": \"list_merge_requests\" | \"review_merge_request\" | \"unknown\",\n" +
                                "  \"parameters\": { ... }\n" +
                                "}\n" +
                                "\n" +
                                "Return ONLY raw JSON. Do not include code fences, markdown, or extra text."
                ),
                Map.of("role","user","content", userPrompt)
        );

        return llmService.chatToJsonMap(messages);
    }
}
