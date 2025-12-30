package com.ld.ainote.models;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Note {
    // ===== 客戶端用（不入庫） =====
    @Exclude private String id;
    @Exclude private boolean shared = false;     // ✅ 是否為共筆（ownerId != 我的 uid）
    @Exclude private String ownerDisplay;        // ✅ 顯示用（可選）
    @Exclude private String ownerEmail;          // ✅ 顯示用（可選）

    // ===== 雲端欄位 =====
    private String ownerId;
    private String title;
    private String content;
    private List<String> collaborators;
    private String stack;

    // 標籤（可選）
    private List<String> tags;

    // 章、節（允許 null；Getter 以 0 回退，避免 NPE）
    private Integer chapter;
    private Integer section;

    // 舊欄位：你列表可能用 timestamp 排序
    @ServerTimestamp private Date timestamp;

    // 與後端對齊（可選）
    @ServerTimestamp private Date createdAt;
    @ServerTimestamp private Date updatedAt;

    public Note() {}

    public Note(String title, String content) {
        this.title = title;
        this.content = content;
        this.collaborators = new ArrayList<>();
        this.tags = new ArrayList<>();
    }

    // ===== Exclude 欄位 =====
    @Exclude public String getId() { return id; }
    @Exclude public void setId(String id) { this.id = id; }

    @Exclude public boolean isShared() { return shared; }
    @Exclude public void setShared(boolean shared) { this.shared = shared; }

    @Exclude public String getOwnerDisplay() { return ownerDisplay; }
    @Exclude public void setOwnerDisplay(String ownerDisplay) { this.ownerDisplay = ownerDisplay; }

    @Exclude public String getOwnerEmail() { return ownerEmail; }
    @Exclude public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }

    // ===== 雲端欄位存取 =====
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getCollaborators() {
        if (collaborators == null) collaborators = new ArrayList<>();
        return collaborators;
    }
    public void setCollaborators(List<String> collaborators) { this.collaborators = collaborators; }

    public String getStack() { return stack; }
    public void setStack(String stack) { this.stack = stack; }

    public Integer getChapter() { return chapter == null ? 0 : chapter; }
    public void setChapter(Integer chapter) { this.chapter = chapter; }

    public Integer getSection() { return section == null ? 0 : section; }
    public void setSection(Integer section) { this.section = section; }

    public List<String> getTags() {
        if (tags == null) tags = new ArrayList<>();
        return tags;
    }
    public void setTags(List<String> tags) { this.tags = tags; }

    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
