package com.example.gitlabagent.helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class JavaChunkHelper {

    private static final int MAX_CHUNK_SIZE = 100; // non-empty lines
    private static final Pattern METHOD_START_PATTERN = Pattern.compile(
            "^\\s*(?:public|private|protected)?\\s*(?:static)?\\s*(?:synchronized)?\\s*(?:final)?\\s*(?:\\w+\\s+)*\\w+\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w,\\s]+)?\\s*\\{?\\s*$"
    );

    public List<String> processDiffLines(String diffString) {
        // Parse diff lines
        List<DiffLine> diffLines = parseDiffLines(diffString);

        // Group into methods/blocks
        List<CodeBlock> codeBlocks = identifyCodeBlocks(diffLines);

        // Create chunks respecting method boundaries and size limits
        return createChunks(codeBlocks, diffLines);
    }

    private List<DiffLine> parseDiffLines(String diffString) {
        List<DiffLine> diffLines = new ArrayList<>();

        String[] lines = diffString.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            // Parse format: "lineNumber: content"
            int colonIndex = line.indexOf(": ");
            if (colonIndex > 0) {
                try {
                    int lineNumber = Integer.parseInt(line.substring(0, colonIndex).trim());
                    String content = line.substring(colonIndex + 2); // +2 to skip ": "

                    // Skip if content is empty after trimming
                    if (content.trim().isEmpty()) {
                        continue;
                    }

                    diffLines.add(new DiffLine(lineNumber, content));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid line format: " + line);
                }
            }
        }

        return diffLines;
    }

    private List<CodeBlock> identifyCodeBlocks(List<DiffLine> diffLines) {
        List<CodeBlock> blocks = new ArrayList<>();

        int i = 0;
        while (i < diffLines.size()) {
            DiffLine currentLine = diffLines.get(i);

            if (isMethodStart(currentLine.content)) {
                // Find the complete method
                CodeBlock methodBlock = findCompleteMethod(diffLines, i);
                blocks.add(methodBlock);
                i = methodBlock.endIndex + 1;
            } else {
                // Single line or group of non-method lines
                CodeBlock singleLineBlock = new CodeBlock(i, i, "SINGLE_LINE");
                blocks.add(singleLineBlock);
                i++;
            }
        }

        return blocks;
    }

    private boolean isMethodStart(String line) {
        String trimmed = line.trim();

        // Skip comments, imports, package declarations, class declarations
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                trimmed.startsWith("package ") || trimmed.startsWith("import ") ||
                trimmed.startsWith("public class ") || trimmed.startsWith("class ") ||
                trimmed.isEmpty()) {
            return false;
        }

        // Check if it looks like a method declaration
        return METHOD_START_PATTERN.matcher(line).matches() ||
                (line.contains("(") && line.contains(")") &&
                        (line.contains("public") || line.contains("private") || line.contains("protected")));
    }

    private CodeBlock findCompleteMethod(List<DiffLine> diffLines, int startIndex) {
        int braceCount = 0;
        boolean foundOpenBrace = false;
        int endIndex = startIndex;

        for (int i = startIndex; i < diffLines.size(); i++) {
            String line = diffLines.get(i).content;

            // Count braces
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpenBrace = true;
                } else if (c == '}') {
                    braceCount--;
                }
            }

            endIndex = i;

            // If we found the opening brace and braces are balanced, method is complete
            if (foundOpenBrace && braceCount == 0) {
                break;
            }
        }

        return new CodeBlock(startIndex, endIndex, "METHOD");
    }

    private List<String> createChunks(List<CodeBlock> blocks, List<DiffLine> diffLines) {
        List<String> chunks = new ArrayList<>();

        List<CodeBlock> currentChunkBlocks = new ArrayList<>();
        int currentNonEmptyCount = 0;

        for (CodeBlock block : blocks) {
            int blockNonEmptyCount = countNonEmptyLinesInBlock(block, diffLines);

            // If this block alone exceeds MAX_CHUNK_SIZE, make it a separate chunk
            if (blockNonEmptyCount > MAX_CHUNK_SIZE) {
                // Finish current chunk if it has content
                if (!currentChunkBlocks.isEmpty()) {
                    chunks.add(buildChunk(currentChunkBlocks, diffLines));
                    currentChunkBlocks.clear();
                    currentNonEmptyCount = 0;
                }

                // Create chunk with just this large block
                chunks.add(buildChunk(Arrays.asList(block), diffLines));
            }
            // If adding this block would exceed limit, finish current chunk
            else if (currentNonEmptyCount + blockNonEmptyCount > MAX_CHUNK_SIZE) {
                if (!currentChunkBlocks.isEmpty()) {
                    chunks.add(buildChunk(currentChunkBlocks, diffLines));
                    currentChunkBlocks.clear();
                    currentNonEmptyCount = 0;
                }
                currentChunkBlocks.add(block);
                currentNonEmptyCount = blockNonEmptyCount;
            }
            // Add block to current chunk
            else {
                currentChunkBlocks.add(block);
                currentNonEmptyCount += blockNonEmptyCount;
            }
        }

        // Don't forget the last chunk
        if (!currentChunkBlocks.isEmpty()) {
            chunks.add(buildChunk(currentChunkBlocks, diffLines));
        }

        return chunks;
    }

    private int countNonEmptyLinesInBlock(CodeBlock block, List<DiffLine> diffLines) {
        int count = 0;
        for (int i = block.startIndex; i <= block.endIndex; i++) {
            if (!diffLines.get(i).content.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private String buildChunk(List<CodeBlock> blocks, List<DiffLine> diffLines) {
        StringBuilder chunk = new StringBuilder();

        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            CodeBlock block = blocks.get(blockIndex);

            // Add all lines in this block with their original line numbers
            for (int i = block.startIndex; i <= block.endIndex; i++) {
                DiffLine diffLine = diffLines.get(i);
                chunk.append(diffLine.lineNumber).append(": ").append(diffLine.content);
                if (i < block.endIndex || blockIndex < blocks.size() - 1) {
                    chunk.append("\n");
                }
            }

            // Add newline between blocks (but not after the last block)
            if (blockIndex < blocks.size() - 1) {
                chunk.append("\n");
            }
        }

        return chunk.toString();
    }

    private void outputChunks(List<String> chunks) {
        System.out.println("Generated " + chunks.size() + " chunks:");
        System.out.println("=".repeat(50));

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int nonEmptyLines = (int) chunk.lines().filter(line -> !line.trim().isEmpty()).count();

            System.out.println("\n--- CHUNK " + (i + 1) + " ---");
            System.out.println("Non-empty lines: " + nonEmptyLines);
            System.out.println("Content:");
            System.out.println(chunk);
            System.out.println("\n" + "=".repeat(50));
        }
    }

    // Helper classes
    static class DiffLine {
        int lineNumber;
        String content;

        DiffLine(int lineNumber, String content) {
            this.lineNumber = lineNumber;
            this.content = content;
        }
    }

    static class CodeBlock {
        int startIndex; // Index in the diffLines list
        int endIndex;   // Index in the diffLines list
        String type;

        CodeBlock(int startIndex, int endIndex, String type) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.type = type;
        }
    }
}
