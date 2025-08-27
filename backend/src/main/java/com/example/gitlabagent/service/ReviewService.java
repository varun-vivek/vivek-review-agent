package com.example.gitlabagent.service;

import com.example.gitlabagent.dto.LlmComment;
import com.example.gitlabagent.dto.LlmReviewResponse;
import com.example.gitlabagent.helper.JavaChunkHelper;
import com.example.gitlabagent.helper.TsChunkHelper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReviewService {

    private final LlmService llmService;
    private final JavaChunkHelper javaChunkHelper;
    private final TsChunkHelper tsChunkHelper;
    private static final Set<String> ALLOWED_SUFFIX = Set.of(".java", ".ts");

    public ReviewService(LlmService llmService, JavaChunkHelper javaChunkHelper, TsChunkHelper tsChunkHelper) {
        this.llmService = llmService;
        this.javaChunkHelper = javaChunkHelper;
        this.tsChunkHelper = tsChunkHelper;
    }

    public boolean shouldReview(String filePath) {
        if (filePath == null) return false;
        return ALLOWED_SUFFIX.stream().anyMatch(filePath::endsWith);
    }

    public LlmReviewResponse reviewDiff(String filePath, String diff) {
        if (!shouldReview(filePath)) {
            LlmReviewResponse empty = new LlmReviewResponse();
            empty.setComments(List.of());
            return empty;
        }

        List<String> chunks = null;
        if(filePath.endsWith(".java")) {
            chunks = this.javaChunkHelper.processDiffLines(preprocessDiff((diff)));
        } else {
            chunks = this.tsChunkHelper.processDiffLines(preprocessDiff((diff)));
        }

        List<LlmComment> allComments = new ArrayList<>();
        for (String chunk : chunks) {
            List<Map<String,Object>> messages = List.of(
                Map.of("role","system","content",
                        "You are an expert code reviewer specializing in Java, TypeScript, Spring Framework, and comprehensive software engineering standards.\n" +
                            "You will receive code diffs with explicit line numbers in the format: line_number: code_content.\n" +
                            "\n" +
                            "Your responsibility:\n" +
                            "- Conduct thorough code review covering ALL aspects: security vulnerabilities, performance bottlenecks, code style violations, naming conventions, architectural issues, design pattern misuse, error handling gaps, Spring-specific anti-patterns, maintainability concerns, and testing implications.\n" +
                            "- Output ONLY a valid stringified JSON response with all internal quotes properly escaped.\n" +
                            "Respond ONLY in valid JSON using this exact schema (no extra text outside JSON):\n" +
                            "{\n" +
                            "  \"comments\": [\n" +
                            "    {\n" +
                            "      \"filePath\": \"string (exact file path from input)\",\n" +
                            "      \"line\": number (target line number - use last line number for multi-line issues),\n" +
                            "      \"comment\": \"string (detailed issue explanation with severity classification)\",\n" +
                            "      \"suggestion\": \"string (actionable solution with code examples when applicable)\"\n" +
                            "    }\n" +
                            "  ]\n" +
                            "}\n" +
                            "Review criteria:\n" +
                            "- Security: SQL injection, XSS vulnerabilities, input validation, authentication flaws, sensitive data exposure\n" +
                            "- Performance: Algorithm efficiency, memory management, resource leaks, database optimization\n" +
                            "- Code Quality: Naming conventions, formatting, unused imports, method complexity, code duplication\n" +
                            "- Architecture: SOLID principles, design patterns, separation of concerns, coupling issues\n" +
                            "- Spring Framework: Dependency injection patterns, transaction management, security configurations, REST API design, bean lifecycle\n" +
                            "- Error Handling: Exception management, null safety, edge cases, resource cleanup\n" +
                            "- Maintainability: Code readability, documentation needs, testability, refactoring opportunities\n" +
                            "\n" +
                            "Output requirements:\n" +
                            "- Use exact line numbers from the input diff\n" +
                            "- For issues spanning multiple lines, reference the last affected line number\n" +
                            "- Include severity indicators: [CRITICAL], [HIGH], [MEDIUM], [LOW] in comments\n" +
                            "- Provide specific, implementable suggestions with code snippets when fixes are under 20 lines\n" +
                            "- Review every single line provided - comprehensive coverage required\n" +
                            "- Return only the stringified JSON, no additional text, explanations, or markdown formatting\n" +
                            "- Output must be raw JSON string only"
                ),
                Map.of("role","user","content",
                        "File: " + filePath + "\n---diff---\n" + chunk
                )
            );

            LlmReviewResponse response = null;
            int attempts = 0;
            Exception lastEx = null;

            while (attempts < 3 && response == null) {  // max 3 tries
                try {
                    response = llmService.chatToObject(messages, LlmReviewResponse.class);
                } catch (Exception ex) {
                    attempts++;
                    lastEx = ex;
                    if (attempts < 3) {
                        try {
                            Thread.sleep(500); // optional backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            if (response == null) {
                // skip this chunk after 3 failures
                System.err.println("Skipping chunk for file " + filePath +
                        " after 3 failed attempts. Last error: " +
                        (lastEx != null ? lastEx.getMessage() : "unknown"));
                continue;
            }

            if (response.getComments() != null) {
                allComments.addAll(response.getComments());
            }
        }

        LlmReviewResponse finalResponse = new LlmReviewResponse();
        finalResponse.setComments(allComments);
        return finalResponse;
    }

    private String preprocessDiff(String diff) {
        StringBuilder prompt = new StringBuilder().append("\n");
        String[] lines = diff.split("\n");
        int currentLine = 0;

        for (String line : lines) {
            if (line.startsWith("@@")) {
                // Extract starting line from hunk
                String[] parts = line.split(" ");
                String newFilePart = Arrays.stream(parts)
                        .filter(p -> p.startsWith("+"))
                        .findFirst().orElse("+1");
                String[] nums = newFilePart.substring(1).split(",");
                currentLine = Integer.parseInt(nums[0]);
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                prompt.append(currentLine).append(": ").append(line.substring(1)).append("\n");
                currentLine++;
            } else if (line.startsWith(" ") || line.startsWith("-")) {
                if (!line.startsWith("-")) {
                    currentLine++;
                }
            }
        }

        return prompt.toString();
    }
}
