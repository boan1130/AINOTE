//package com.ld.ainote.models;
/*
import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;
import java.util.*;

public class Note {
    @Exclude private String id;

    private String ownerId;
    private String title;
    private String content;
    private List<String> collaborators;
    private String stack;

    @ServerTimestamp private Date timestamp;

    public Note() {}

    public Note(String title, String content) {
        this.title = title;
        this.content = content;
        this.collaborators = new ArrayList<>();
    }

    @Exclude public String getId() { return id; }
    @Exclude public void setId(String id) { this.id = id; }

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

    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
}
*/

package com.ld.ainote.models;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;
import java.util.*;

public class Note {
    @Exclude private String id;

    private String ownerId;
    private String title;
    private String content;
    private List<String> collaborators;
    private String stack;

    // [NEW] 章與節
    private int chapter;
    private int section;

    @ServerTimestamp private Date timestamp;

    public Note() {}

    public Note(String title, String content) {
        this.title = title;
        this.content = content;
        this.collaborators = new ArrayList<>();
    }

    @Exclude public String getId() { return id; }
    @Exclude public void setId(String id) { this.id = id; }

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

    // [NEW] chapter
    public int getChapter() { return chapter; }
    public void setChapter(int chapter) { this.chapter = chapter; }

    // [NEW] section
    public int getSection() { return section; }
    public void setSection(int section) { this.section = section; }

    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
}
