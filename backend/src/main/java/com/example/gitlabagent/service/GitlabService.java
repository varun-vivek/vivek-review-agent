package com.example.gitlabagent.service;

import com.example.gitlabagent.dto.LlmComment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GitlabService {
    private final RestTemplate rest = new RestTemplate();

    @Value("${app.gitlab.base-url}")
    private String gitlabBase;

    @Value("${app.gitlab.project-id}")
    private String projectId;

    @Value("${app.gitlab.token}")
    private String token;

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("PRIVATE-TOKEN", token);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> getAllMergeRequests() {
        String url = gitlabBase + "/projects/{projectId}/merge_requests";
        HttpEntity<Void> ent = new HttpEntity<>(authHeaders());
        ResponseEntity<List> resp = rest.exchange(url, HttpMethod.GET, ent, List.class, Map.of("projectId", projectId));
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> getMergeRequestChanges(int mrIid) {
        String url = gitlabBase + "/projects/{projectId}/merge_requests/{mrId}/changes";
        HttpEntity<Void> ent = new HttpEntity<>(authHeaders());
        ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.GET, ent, Map.class, Map.of("projectId", projectId, "mrId", mrIid));
        Map body = resp.getBody();
        if (body == null) return List.of();
        Object changes = body.get("changes");
        if (changes instanceof List) return (List<Map<String,Object>>) changes;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String,String> getMergeRequestShas(int mrIid) {
        String url = gitlabBase + "/projects/{projectId}/merge_requests/{mrId}";
        HttpEntity<Void> ent = new HttpEntity<>(authHeaders());
        try {
            ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.GET, ent, Map.class, Map.of("projectId", projectId, "mrId", mrIid));
            Map body = resp.getBody();
            Map<String,String> out = new HashMap<>();
            if (body != null && body.get("diff_refs") instanceof Map<?,?> refs) {
                Object base = refs.get("base_sha"); Object start = refs.get("start_sha"); Object head = refs.get("head_sha");
                out.put("base_sha", base == null ? "" : base.toString());
                out.put("start_sha", start == null ? "" : start.toString());
                out.put("head_sha", head == null ? "" : head.toString());
            } else {
                out.put("base_sha", ""); out.put("start_sha", ""); out.put("head_sha", "");
            }
            return out;
        } catch (HttpClientErrorException e) {
            return Map.of("base_sha","","start_sha","","head_sha","");
        }
    }

    public void addInlineComment(int mrIid, LlmComment comment, String baseSha, String startSha, String headSha) {
        String url = gitlabBase + "/projects/{projectId}/merge_requests/{mrId}/discussions";
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = comment.getComment();
        if (comment.getSuggestion() != null && !comment.getSuggestion().isBlank()) {
            String lang = detectLanguage(comment.getFilePath());
            body += "\n\n**Suggested fix:**\n```" + lang + "\n" + comment.getSuggestion() + "\n```";
        }

        var position = Map.of(
                "base_sha", baseSha,
                "start_sha", startSha,
                "head_sha", headSha,
                "old_path", comment.getFilePath(),
                "new_path", comment.getFilePath(),
                "new_line", comment.getLine(),
                "position_type", "text"
        );

        var payload = Map.of("body", body, "position", position);
        HttpEntity<Object> ent = new HttpEntity<>(payload, headers);
        rest.postForEntity(url, ent, String.class, Map.of("projectId", projectId, "mrId", mrIid));
    }

    private String detectLanguage(String filePath) {
        if (filePath == null) return "";
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".ts")) return "typescript";
        return "";
    }
}
