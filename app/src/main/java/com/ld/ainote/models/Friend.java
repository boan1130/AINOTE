package com.ld.ainote.models;

public class Friend {
    private String uid;
    private String displayName;
    private String email;

    private long lastOnline; // epoch millis，沒資料給 0
    private int noteCount;

    public Friend() {}

    public Friend(String uid, String displayName, String email) {
        this.uid = uid;
        this.displayName = displayName;
        this.email = email;
    }

    public String getUid() { return uid; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public long getLastOnline() { return lastOnline; }
    public int getNoteCount() { return noteCount; }

    public void setUid(String uid) { this.uid = uid; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEmail(String email) { this.email = email; }
    public void setLastOnline(long lastOnline) { this.lastOnline = lastOnline; }
    public void setNoteCount(int noteCount) { this.noteCount = noteCount; }
}
