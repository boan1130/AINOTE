package com.ld.ainotepj.models;

public class Passage {
    private String title;
    private String content;

    public Passage() {}

    public Passage(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public String getTitle() { return title; }
    public String getContent() { return content; }
}
