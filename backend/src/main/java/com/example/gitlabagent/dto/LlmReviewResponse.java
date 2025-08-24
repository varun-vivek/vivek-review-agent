package com.example.gitlabagent.dto;

import java.util.List;

public class LlmReviewResponse {
    private List<LlmComment> comments;
    public List<LlmComment> getComments() { return comments; }
    public void setComments(List<LlmComment> comments) { this.comments = comments; }
}
