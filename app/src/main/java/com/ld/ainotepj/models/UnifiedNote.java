// UnifiedNote.java
package com.ld.ainotepj.models;

public class UnifiedNote {
    private String title;
    private String content;
    private String type; // "note" or "highlight"
    private String timestamp;

    public UnifiedNote() {}

    public UnifiedNote(String title, String content, String type, String timestamp) {
        this.title = title;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
    }

    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getType() { return type; }
    public String getTimestamp() { return timestamp; }
}