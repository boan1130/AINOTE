package com.ld.ainote.models;

import com.google.firebase.firestore.ServerTimestamp;
import java.util.Date;

public class NoteBlock {
    private String id;
    private int index;
    private String type;
    private String text;
    private long version;
    private String updatedBy;
    @ServerTimestamp private Date updatedAt;
    private String lockHolder;
    private Date lockUntil;  // 🔹由後端 API 計算加時間，不用 @ServerTimestamp
    // 🆕 顯示資訊欄位
    private String updatedByDisplayName;
    private String updatedByEmail;
    public NoteBlock() {}

    // ----- Getters / Setters -----
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }

    public String getLockHolder() { return lockHolder; }
    public void setLockHolder(String lockHolder) { this.lockHolder = lockHolder; }

    public Date getLockUntil() { return lockUntil; }
    public void setLockUntil(Date lockUntil) { this.lockUntil = lockUntil; }

    public String getUpdatedByDisplayName() { return updatedByDisplayName; }
    public void setUpdatedByDisplayName(String updatedByDisplayName) { this.updatedByDisplayName = updatedByDisplayName; }

    public String getUpdatedByEmail() { return updatedByEmail; }
    public void setUpdatedByEmail(String updatedByEmail) { this.updatedByEmail = updatedByEmail; }

}
