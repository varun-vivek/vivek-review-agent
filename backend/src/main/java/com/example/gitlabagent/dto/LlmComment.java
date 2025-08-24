package com.example.gitlabagent.dto;

public class LlmComment {
    private String filePath;
    private int line;
    private String comment;
    private String suggestion;

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
}
