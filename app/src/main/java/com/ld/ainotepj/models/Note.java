package com.ld.ainotepj.models;

public class Note {
    private String title;
    private String content;

    // Firestore 要求有空的 constructor！
    public Note() {}

    public Note(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
