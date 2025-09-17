package com.ld.ainote;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.ld.ainote.data.FriendRepository;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Friend;
import com.ld.ainote.models.Note;
import com.ld.ainote.utils.HighlightUtils;

import java.util.ArrayList;
import java.util.List;

public class NoteEditActivity extends AppCompatActivity {

    private TextInputEditText etTitle, etContent, etStack;
    private MaterialButton btnSave, btnDelete, btnShare, btnMark, btnUnmark, btnToggleHighlights;
    private LinearLayout highlightList;

    private NoteRepository repo;
    private String noteId;
    private String ownerId;
    private Note current;

    private boolean highlightExpanded = false;

    private final List<String> currentCollaborators = new ArrayList<>();

    private TextInputEditText etChapter, etSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note_edit);

        repo = new NoteRepository();
        etTitle   = findViewById(R.id.etTitle);
        etContent = findViewById(R.id.etContent);
        etStack   = findViewById(R.id.etStack);
        btnSave   = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);
        btnShare  = findViewById(R.id.btnShare);
        btnMark   = findViewById(R.id.btnMark);
        btnUnmark = findViewById(R.id.btnUnmark);
        btnToggleHighlights = findViewById(R.id.btnToggleHighlights);
        highlightList = findViewById(R.id.highlightList);

        etChapter = findViewById(R.id.etChapter);                    // [NEW] 章
        etSection = findViewById(R.id.etSection);                    // [NEW] 節

        noteId  = getIntent().getStringExtra("note_id");
        ownerId = getIntent().getStringExtra("owner_id");
        if (TextUtils.isEmpty(ownerId)) {
            ownerId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }

        if (!TextUtils.isEmpty(noteId)) {
            btnDelete.setEnabled(true);
            btnShare.setEnabled(true);

            repo.getNoteById(ownerId, noteId, note -> {
                current = note;
                if (note != null) {
                    etTitle.setText(note.getTitle());
                    etContent.setText(note.getContent());
                    etStack.setText(note.getStack());
                    // ↓↓↓ 帶回章、節到 UI（舊資料沒有時留空）
                    if (note.getChapter() > 0) {                     // [NEW]
                        etChapter.setText(String.valueOf(note.getChapter()));
                    } else {
                        etChapter.setText("");                       // [NEW]
                    }
                    if (note.getSection() > 0) {                     // [NEW]
                        etSection.setText(String.valueOf(note.getSection()));
                    } else {
                        etSection.setText("");                        // [NEW]
                    }
                    refreshHighlightPanel();
                    updateToggleText();
                }
            }, e -> Toast.makeText(this, "載入失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());

            repo.getCollaborators(ownerId, noteId, list -> {
                currentCollaborators.clear();
                currentCollaborators.addAll(list);
            });

        } else {
            btnDelete.setEnabled(false);
            btnShare.setEnabled(false);
        }

        btnMark.setOnClickListener(v -> {
            HighlightUtils.addMark(etContent);
            refreshHighlightPanel();
            updateToggleText();
        });
        btnUnmark.setOnClickListener(v -> {
            HighlightUtils.removeNearestMark(etContent);
            refreshHighlightPanel();
            updateToggleText();
        });

        btnToggleHighlights.setOnClickListener(v -> {
            highlightExpanded = !highlightExpanded;
            highlightList.setVisibility(highlightExpanded ? View.VISIBLE : View.GONE);
            updateToggleText();
        });

        btnSave.setOnClickListener(v -> {
            String title = s(etTitle), content = s(etContent), stack = s(etStack);
            int chapter = parseIntSafe(etChapter);                    // [NEW]
            int section = parseIntSafe(etSection);                    // [NEW]

            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(content)) {
                Toast.makeText(this, "請輸入標題或內容", Toast.LENGTH_SHORT).show();
                return;
            }

            if (TextUtils.isEmpty(stack)) {                           // [NEW] 強制要求大類別
                Toast.makeText(this, "請填寫『大類別』", Toast.LENGTH_SHORT).show();
                return;
            }

            if (TextUtils.isEmpty(noteId)) {
                Note n = new Note(title, content);
                n.setStack(stack);
                n.setChapter(chapter);                                // [NEW]
                n.setSection(section);
                repo.addNote(n, task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(this, "筆記已新增", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "新增失敗：" + (task.getException()!=null?task.getException().getMessage():""), Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                Note n = (current != null) ? current : new Note();
                n.setId(noteId);
                n.setTitle(title);
                n.setContent(content);
                n.setStack(stack);                                    // [CHANGED]
                n.setChapter(chapter);                                // [NEW]
                n.setSection(section);
                repo.updateNote(n, ownerId).addOnCompleteListener(t -> {
                    if (t.isSuccessful()) {
                        Toast.makeText(this, "已儲存變更", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "更新失敗：" + (t.getException()!=null?t.getException().getMessage():""), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        btnDelete.setOnClickListener(v -> {
            if (TextUtils.isEmpty(noteId)) return;
            String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            if (!myUid.equals(ownerId)) {
                Toast.makeText(this, "僅擁有者可刪除此筆記", Toast.LENGTH_SHORT).show();
                return;
            }
            repo.deleteNote(noteId).addOnCompleteListener(t -> {
                if (t.isSuccessful()) {
                    Toast.makeText(this, "筆記已刪除", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(this, "刪除失敗：" + (t.getException()!=null?t.getException().getMessage():""), Toast.LENGTH_LONG).show();
                }
            });
        });

        btnShare.setOnClickListener(v -> openShareDialog());
    }

    private void openShareDialog() {
        if (TextUtils.isEmpty(noteId)) {
            Toast.makeText(this, "請先儲存筆記再設定共編", Toast.LENGTH_SHORT).show();
            return;
        }

        new FriendRepository().getFriends(friends -> {
            if (friends.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "尚未有好友可共編", Toast.LENGTH_SHORT).show());
                return;
            }
            String[] names = new String[friends.size()];
            boolean[] checked = new boolean[friends.size()];
            List<String> uids = new ArrayList<>();

            for (int i = 0; i < friends.size(); i++) {
                Friend f = friends.get(i);
                names[i] = formatFriendLabel(f);      // ← 這裡改用帶「帳號」的顯示字串
                uids.add(f.getUid());
                checked[i] = currentCollaborators.contains(f.getUid());
            }

            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("選取共編者")
                    .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton("套用", (d, w) -> {
                        List<String> picked = new ArrayList<>();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(uids.get(i));
                        repo.setCollaborators(ownerId, noteId, picked, task -> {
                            Toast.makeText(this, task.isSuccessful() ? "已更新共編者" : "更新失敗", Toast.LENGTH_SHORT).show();
                            if (task.isSuccessful()) {
                                currentCollaborators.clear();
                                currentCollaborators.addAll(picked);
                            }
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show());
        });
    }

    private void refreshHighlightPanel() {
        List<String> hs = HighlightUtils.extractHighlights(s(etContent));
        btnToggleHighlights.setText("顯示重點 (" + hs.size() + ")");
        highlightList.removeAllViews();
        for (String h : hs) {
            TextView tv = new TextView(this);
            tv.setText("• " + h);
            tv.setPadding(8, 6, 8, 6);
            tv.setTextSize(14);
            tv.setBackgroundColor(0x10FFF59D);
            highlightList.addView(tv);
        }
    }

    private void updateToggleText() {
        List<String> hs = HighlightUtils.extractHighlights(s(etContent));
        String base = "顯示重點 (" + hs.size() + ")";
        btnToggleHighlights.setText(highlightExpanded ? base + " ▲" : base + " ▼");
    }

    private String s(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int parseIntSafe(TextInputEditText et) {                  // [NEW] 解析章/節數字；空字串回 0
        String v = s(et);
        try { return TextUtils.isEmpty(v) ? 0 : Integer.parseInt(v); }
        catch (Exception ex) { return 0; }
    }

    // 依你的 Friend 資料模型調整欄位名稱：假設有 getEmail()；若沒有，就用 getUid() 當備援
    private String formatFriendLabel(Friend f) {
        if (f.getDisplayName() != null && !f.getDisplayName().isEmpty()) {
            return f.getDisplayName();
        } else if (f.getEmail() != null && !f.getEmail().isEmpty()) {
            return f.getEmail();
        } else {
            return f.getUid();
        }
    }
}
