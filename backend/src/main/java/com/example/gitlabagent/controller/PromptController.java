package com.example.gitlabagent.controller;

import com.example.gitlabagent.dto.LlmComment;
import com.example.gitlabagent.dto.LlmReviewResponse;
import com.example.gitlabagent.dto.PromptRequest;
import com.example.gitlabagent.service.GitlabService;
import com.example.gitlabagent.service.IntentService;
import com.example.gitlabagent.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class PromptController {

    @Autowired
    private GitlabService gitlabService;

    @Autowired
    private IntentService intentService;

    @Autowired
    private ReviewService reviewService;

    @PostMapping(value = "/prompt", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handlePrompt(@RequestBody PromptRequest request) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        CompletableFuture.runAsync(() -> {
            try {
                String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
                emitter.send("Received prompt: " + prompt);

                Map<String,Object> intentObj = intentService.extractIntent(prompt);
                if (intentObj == null || !intentObj.containsKey("intent")) {
                    emitter.send("LLM did not return a valid intent.\n" + intentObj);
                    emitter.complete();
                    return;
                }

                String intent = String.valueOf(intentObj.get("intent"));
                emitter.send("Detected intent: " + intent);

                switch (intent) {
                    case "list_merge_requests" -> {
                        emitter.send("Fetching merge requests..."); 
                        var mrs = gitlabService.getAllMergeRequests();
                        emitter.send("Found " + (mrs == null ? 0 : mrs.size()) + " merge requests");
                        emitter.send(mrs == null ? List.of() : mrs);
                    }
                    case "review_merge_request" -> {
                        Map<String,Object> params = (Map<String,Object>) intentObj.get("parameters");
                        if (params == null || !params.containsKey("mr_id")) {
                            emitter.send("Intent missing parameter: mr_id");
                            break;
                        }
                        int mrId = ((Number) params.get("mr_id")).intValue();
                        emitter.send("Fetching changes for MR #" + mrId + "..."); 
                        List<Map<String,Object>> changes = gitlabService.getMergeRequestChanges(mrId);
                        emitter.send("Found " + (changes == null ? 0 : changes.size()) + " changed files");

                        // Fetch real SHAs from MR details
                        Map<String,String> shas = gitlabService.getMergeRequestShas(mrId);
                        String baseSha = shas.getOrDefault("base_sha", "");
                        String startSha = shas.getOrDefault("start_sha", "");
                        String headSha = shas.getOrDefault("head_sha", "");
                        emitter.send("Using SHAs base=" + baseSha + ", start=" + startSha + ", head=" + headSha);

                        if (changes != null) {
                            for (var fileDiff : changes) {
                                String newPath = (String) fileDiff.get("new_path"); 
                                if (!reviewService.shouldReview(newPath)) {
                                    emitter.send("Skipping non-Java/TS file: " + newPath);
                                    continue;
                                }
                                emitter.send("Reviewing file: " + newPath);
                                String diff = (String) fileDiff.getOrDefault("diff", "");
                                LlmReviewResponse resp = reviewService.reviewDiff(newPath, diff);
                                if (resp == null || resp.getComments() == null || resp.getComments().isEmpty()) {
                                    emitter.send("No issues found by LLM for " + newPath);
                                    continue;
                                }
                                for (LlmComment c : resp.getComments()) {
                                    emitter.send("Posting inline comment at " + c.getFilePath() + ":" + c.getLine());
                                    boolean success = false;
                                    int attempts = 0;
                                    Exception lastEx = null;

                                    while (attempts < 3 && !success) {  // total 3 attempts (1 original + 2 retries)
                                        try {
                                            gitlabService.addInlineComment(mrId, c, baseSha, startSha, headSha);
                                            success = true;
                                        } catch (Exception ex) {
                                            attempts++;
                                            lastEx = ex;
                                            emitter.send("Failed to post comment (attempt " + attempts + "): " + ex.getMessage());
                                            if (attempts < 3) {
                                                try {
                                                    Thread.sleep(500); // small backoff, optional
                                                } catch (InterruptedException ie) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            }
                                        }
                                    }

                                    if (!success) {
                                        emitter.send("Skipping comment at " + c.getFilePath() + ":" + c.getLine()
                                                + " after 3 failed attempts. Last error: "
                                                + (lastEx != null ? lastEx.getMessage() : "unknown"));
                                    }
                                }
                            }
                        }
                        emitter.send("MR review completed ✅");
                    }
                    default -> {
                        emitter.send("Unknown or unsupported intent: " + intent + ". Try: list_merge_requests or review_merge_request.");
                    }
                }

                emitter.complete();
            } catch (Exception ex) {
                try { emitter.send("Error: " + ex.getMessage()); } catch (Exception ignored) {}
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }
}
