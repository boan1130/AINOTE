package com.ld.ainotepj.models;

public class Annotation {
    private String text;
    private int highlightStart;
    private int highlightEnd;
    private String color;

    public Annotation() {}

    public Annotation(String text, int highlightStart, int highlightEnd, String color) {
        this.text = text;
        this.highlightStart = highlightStart;
        this.highlightEnd = highlightEnd;
        this.color = color;
    }

    public String getText() { return text; }
    public int getHighlightStart() { return highlightStart; }
    public int getHighlightEnd() { return highlightEnd; }
    public String getColor() { return color; }
}
