/*package com.ld.ainote;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.ld.ainote.adapters.BlockAdapter;
import com.ld.ainote.data.BlockRepository;
import com.ld.ainote.data.FriendRepository;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Friend;
import com.ld.ainote.models.Note;
import com.ld.ainote.models.NoteBlock;
import com.ld.ainote.utils.HighlightUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class NoteEditActivity extends AppCompatActivity {

    // ====== 基本欄位 ======
    private TextInputEditText etTitle, etStack, etChapter, etSection;

    // 舊單欄位內容（保留相容：UI 隱藏）
    private TextInputEditText etContent;

    // ====== 區塊編輯 ======
    private RecyclerView rvBlocks;
    private BlockAdapter blockAdapter;
    private BlockRepository blockRepo;
    private ListenerRegistration blocksReg;
    private Handler lockUiTicker;

    // ====== 其他 UI ======
    private MaterialButton btnSave, btnDelete, btnShare, btnToggleHighlights;
    private MaterialButton btnMark, btnUnmark; // 舊版按鈕，會隱藏
    private LinearLayout highlightList;

    // ====== 資料/狀態 ======
    private NoteRepository noteRepo;
    private String noteId;
    private String ownerId;
    private Note current;
    private final List<String> currentCollaborators = new ArrayList<>();

    private boolean highlightExpanded = false;
    private String myUid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note_edit);

        // Repo
        noteRepo = new NoteRepository();
        blockRepo = new BlockRepository();

        // Intent 參數
        noteId  = getIntent().getStringExtra("note_id");
        ownerId = getIntent().getStringExtra("owner_id");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        if (TextUtils.isEmpty(ownerId)) ownerId = myUid;

        // 綁 UI
        etTitle   = findViewById(R.id.etTitle);
        etStack   = findViewById(R.id.etStack);
        etChapter = findViewById(R.id.etChapter);
        etSection = findViewById(R.id.etSection);
        etContent = findViewById(R.id.etContent);

        btnSave   = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);
        btnShare  = findViewById(R.id.btnShare);
        btnMark   = findViewById(R.id.btnMark);
        btnUnmark = findViewById(R.id.btnUnmark);
        btnToggleHighlights = findViewById(R.id.btnToggleHighlights);
        highlightList = findViewById(R.id.highlightList);

        // 隱藏舊內容/舊標記按鈕
        if (etContent != null) etContent.setVisibility(View.GONE);
        if (btnMark != null) btnMark.setVisibility(View.GONE);
        if (btnUnmark != null) btnUnmark.setVisibility(View.GONE);

        // 區塊列表
        rvBlocks = findViewById(R.id.rvBlocks);
        rvBlocks.setLayoutManager(new LinearLayoutManager(this));
        blockAdapter = new BlockAdapter(new BlockAdapter.Listener() {
            @Override
            public void onAcquireLock(@NonNull NoteBlock block) {
                blockRepo.acquireLock(ownerId, noteId, block.getId())
                        .addOnFailureListener(e -> Log.d("NoteEditActivity", "鎖定失敗：" + e.getMessage()));
            }

            @Override
            public void onSave(@NonNull NoteBlock block, @NonNull String newText, long currentVersion) {
                blockRepo.updateBlock(ownerId, noteId, block.getId(), newText, block.getType(), block.getIndex(), (int) currentVersion)
                        .addOnSuccessListener(v -> blockRepo.renewLock(ownerId, noteId, block.getId()))
                        .addOnFailureListener(e ->
                                Toast.makeText(NoteEditActivity.this, "儲存失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onReleaseLock(@NonNull NoteBlock block) {
                blockRepo.releaseLock(ownerId, noteId, block.getId())
                        .addOnFailureListener(e -> { });
            }

            @Override
            public void onAddAfter(@NonNull NoteBlock block) {
                int nextIndex = block.getIndex() + 1;
                blockRepo.createBlock(ownerId, noteId, nextIndex, "text", "")
                        .addOnFailureListener(e ->
                                Toast.makeText(NoteEditActivity.this, "新增區塊失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onDelete(@NonNull NoteBlock block) {
                new AlertDialog.Builder(NoteEditActivity.this)
                        .setTitle("刪除此段落？")
                        .setMessage("確定要刪除這個區塊嗎？此操作無法復原。")
                        .setPositiveButton("刪除", (d, w) -> {
                            blockRepo.deleteBlock(ownerId, noteId, block.getId())
                                    .addOnSuccessListener(r ->
                                            Toast.makeText(NoteEditActivity.this, "已刪除", Toast.LENGTH_SHORT).show())
                                    .addOnFailureListener(e ->
                                            Toast.makeText(NoteEditActivity.this, "刪除失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
                            // 不需要手動刷新：Firestore snapshot 會自動回補最新列表
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        blockAdapter.setMyUid(myUid);
        rvBlocks.setAdapter(blockAdapter);

        // 權限：僅擁有者可刪除/設共編
        boolean iAmOwner = myUid.equals(ownerId);
        btnDelete.setEnabled(!TextUtils.isEmpty(noteId) && iAmOwner);
        btnShare.setEnabled(!TextUtils.isEmpty(noteId) && iAmOwner);

        // 載入基本資料
        if (!TextUtils.isEmpty(noteId)) {
            noteRepo.getNoteById(ownerId, noteId, note -> {
                current = note;
                if (note != null) {
                    etTitle.setText(note.getTitle());
                    etStack.setText(note.getStack());
                    etChapter.setText(note.getChapter() > 0 ? String.valueOf(note.getChapter()) : "");
                    etSection.setText(note.getSection() > 0 ? String.valueOf(note.getSection()) : "");
                    refreshHighlightPanel();
                    updateToggleText();
                }
            }, e -> Toast.makeText(this, "載入失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());

            noteRepo.getCollaborators(ownerId, noteId, list -> {
                currentCollaborators.clear();
                currentCollaborators.addAll(list);
            });
        } else {
            btnDelete.setEnabled(false);
            btnShare.setEnabled(false);
        }

        // 重點清單開關
        btnToggleHighlights.setOnClickListener(v -> {
            highlightExpanded = !highlightExpanded;
            highlightList.setVisibility(highlightExpanded ? View.VISIBLE : View.GONE);
            updateToggleText();
        });

        // 儲存基本資料
        btnSave.setOnClickListener(v -> onSaveBasic(ownerId));

        // 刪除（僅擁有者）
        btnDelete.setOnClickListener(v -> {
            if (TextUtils.isEmpty(noteId)) return;
            String curUid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
            if (!curUid.equals(ownerId)) {
                Toast.makeText(this, "僅擁有者可刪除此筆記", Toast.LENGTH_SHORT).show();
                return;
            }
            noteRepo.deleteNote(noteId).addOnCompleteListener(t -> {
                if (t.isSuccessful()) {
                    Toast.makeText(this, "筆記已刪除", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    String msg = (t.getException() != null) ? t.getException().getMessage() : "未知錯誤";
                    Toast.makeText(this, "刪除失敗：" + msg, Toast.LENGTH_LONG).show();
                }
            });
        });

        // 設共編（僅擁有者）
        btnShare.setOnClickListener(v -> openShareDialog(iAmOwner));

    }

    // ======================= Lifecycle：監聽 blocks + UI 倒數 =======================

    @Override
    protected void onStart() {
        super.onStart();

        // 即時監聽 blocks
        startBlocksListener();

        // 每秒更新鎖倒數
        if (lockUiTicker == null) {
            lockUiTicker = new Handler(Looper.getMainLooper());
            lockUiTicker.post(lockClockTick);
        }
    }

    private void startBlocksListener() {
        if (blocksReg != null) return;
        if (TextUtils.isEmpty(ownerId) || TextUtils.isEmpty(noteId)) return;

        blocksReg = FirebaseFirestore.getInstance()
                .collection("users").document(ownerId)
                .collection("notes").document(noteId)
                .collection("blocks")
                .orderBy("index")
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;

                    List<NoteBlock> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        NoteBlock b = d.toObject(NoteBlock.class);
                        if (b == null) continue;
                        b.setId(d.getId());
                        list.add(b);
                    }

                    // 若空 → 一次性補第一個 block（把舊 content 搬進來；沒有就空字串）
                    if (list.isEmpty()) {
                        String initialText = (current != null && !TextUtils.isEmpty(current.getContent()))
                                ? current.getContent() : "";
                        blockRepo.createBlock(ownerId, noteId, 0, "text", initialText)
                                .addOnFailureListener(err ->
                                        Toast.makeText(NoteEditActivity.this, "自動建立區塊失敗：" + err.getMessage(), Toast.LENGTH_LONG).show());
                        return; // 等下一輪 snapshot 回來
                    }

                    blockAdapter.submitSorted(list);
                    refreshHighlightPanel();
                    updateToggleText();
                });
    }

    private final Runnable lockClockTick = new Runnable() {
        @Override public void run() {
            if (blockAdapter != null) blockAdapter.updateLockClock();
            if (lockUiTicker != null) lockUiTicker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onStop() {
        super.onStop();
        if (blocksReg != null) {
            blocksReg.remove();
            blocksReg = null;
        }
        if (lockUiTicker != null) {
            lockUiTicker.removeCallbacksAndMessages(null);
            lockUiTicker = null;
        }
        // 最佳努力釋放我持有的鎖
        if (blockAdapter != null) {
            List<NoteBlock> cur = blockAdapter.current();
            for (NoteBlock b : cur) {
                if (myUid.equals(b.getLockHolder()) && !lockExpired(b)) {
                    blockRepo.releaseLock(ownerId, noteId, b.getId());
                }
            }
        }
    }

    private boolean lockExpired(NoteBlock b) {
        Date until = b.getLockUntil();
        return until == null || System.currentTimeMillis() > until.getTime();
    }

    // ======================= 儲存標題/分類/章節/節 =======================

    private void onSaveBasic(String noteOwnerId) {
        String title = s(etTitle), stack = s(etStack);
        int chapter = parseIntSafe(etChapter);
        int section = parseIntSafe(etSection);

        if (TextUtils.isEmpty(title)) {
            Toast.makeText(this, "請輸入標題", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(stack)) {
            Toast.makeText(this, "請填寫『大類別』", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);

        if (TextUtils.isEmpty(noteId)) {
            // 新增 note（僅基本屬性；內容改用 blocks）
            Note n = new Note(title, "");
            n.setStack(stack);
            n.setChapter(chapter);
            n.setSection(section);

            noteRepo.addNote(n, task -> {
                btnSave.setEnabled(true);
                if (task != null && task.isSuccessful() && task.getResult() != null) {
                    // 切換成編輯模式
                    String newId = task.getResult().getId();
                    noteId = newId;
                    ownerId = myUid; // 新筆記一定是自己
                    Toast.makeText(this, "筆記已新增，已切換到區塊編輯", Toast.LENGTH_SHORT).show();

                    // 開始監聽 blocks（下一輪會自動補第一個 block）
                    startBlocksListener();

                    // 啟用刪除/共編
                    btnDelete.setEnabled(true);
                    btnShare.setEnabled(true);

                } else {
                    String msg = (task != null && task.getException() != null)
                            ? task.getException().getMessage() : "未知錯誤";
                    Toast.makeText(this, "新增失敗：" + msg, Toast.LENGTH_LONG).show();
                }
            });

        } else {
            // 更新（支援共筆）
            Note n = (current != null) ? current : new Note();
            n.setId(noteId);
            n.setTitle(title);
            n.setStack(stack);
            n.setChapter(chapter);
            n.setSection(section);

            noteRepo.updateNote(n, noteOwnerId).addOnCompleteListener(t -> {
                btnSave.setEnabled(true);
                if (t.isSuccessful()) {
                    Toast.makeText(this, "已儲存變更", Toast.LENGTH_SHORT).show();
                } else {
                    String msg = (t.getException() != null) ? t.getException().getMessage() : "未知錯誤";
                    Toast.makeText(this, "更新失敗：" + msg, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // ======================= 共編設定（僅擁有者） =======================

    private void openShareDialog(boolean iAmOwner) {
        if (TextUtils.isEmpty(noteId)) {
            Toast.makeText(this, "請先儲存筆記再設定共編", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!iAmOwner) {
            Toast.makeText(this, "僅擁有者可設定共編", Toast.LENGTH_SHORT).show();
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
                names[i] = formatFriendLabel(f);
                uids.add(f.getUid());
                checked[i] = currentCollaborators.contains(f.getUid());
            }

            runOnUiThread(() -> {
                final AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle("選取共編者")
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton("套用", null)
                        .setNegativeButton("取消", null)
                        .create();
                dlg.show();

                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    v.setEnabled(false);
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(uids.get(i));

                    noteRepo.setCollaborators(ownerId, noteId, picked, task -> {
                        v.setEnabled(true);
                        boolean ok = task != null && task.isSuccessful();
                        Toast.makeText(this, ok ? "已更新共編者" : "更新失敗", Toast.LENGTH_SHORT).show();
                        if (ok) {
                            currentCollaborators.clear();
                            currentCollaborators.addAll(picked);
                            dlg.dismiss();
                        }
                    });
                });
            });
        });
    }

    private String formatFriendLabel(Friend f) {
        if (!TextUtils.isEmpty(f.getDisplayName())) return f.getDisplayName();
        if (!TextUtils.isEmpty(f.getEmail())) return f.getEmail();
        return f.getUid();
    }

    // ======================= 重點清單（從 blocks 萃取） =======================

    private void refreshHighlightPanel() {
        if (btnToggleHighlights == null || highlightList == null || blockAdapter == null) return;

        StringBuilder sb = new StringBuilder();
        for (NoteBlock b : blockAdapter.current()) {
            String type = b.getType() == null ? "" : b.getType().toLowerCase();
            if ("text".equals(type) || "paragraph".equals(type) || TextUtils.isEmpty(type)) {
                if (!TextUtils.isEmpty(b.getText())) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(b.getText());
                }
            }
        }
        List<String> hs = HighlightUtils.extractHighlights(sb.toString());
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
        if (btnToggleHighlights == null || blockAdapter == null) return;

        StringBuilder sb = new StringBuilder();
        for (NoteBlock b : blockAdapter.current()) {
            String type = b.getType() == null ? "" : b.getType().toLowerCase();
            if ("text".equals(type) || "paragraph".equals(type) || TextUtils.isEmpty(type)) {
                if (!TextUtils.isEmpty(b.getText())) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(b.getText());
                }
            }
        }
        int count = HighlightUtils.extractHighlights(sb.toString()).size();
        String base = "顯示重點 (" + count + ")";
        btnToggleHighlights.setText(highlightExpanded ? base + " ▲" : base + " ▼");
    }

    // ======================= 小工具 =======================

    private String s(TextInputEditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int parseIntSafe(TextInputEditText et) {
        String v = s(et);
        try { return TextUtils.isEmpty(v) ? 0 : Integer.parseInt(v); }
        catch (Exception ex) { return 0; }
    }
}
*/

package com.ld.ainote;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.ld.ainote.adapters.BlockAdapter;
import com.ld.ainote.data.BlockRepository;
import com.ld.ainote.data.FriendRepository;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Friend;
import com.ld.ainote.models.Note;
import com.ld.ainote.models.NoteBlock;
import com.ld.ainote.utils.HighlightUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class NoteEditActivity extends AppCompatActivity {

    // ====== 基本欄位 ======
    private TextInputEditText etTitle, etStack, etChapter, etSection;

    // 舊單欄位內容（保留相容：UI 隱藏）
    private TextInputEditText etContent;

    // ====== 區塊編輯 ======
    private RecyclerView rvBlocks;
    private BlockAdapter blockAdapter;
    private BlockRepository blockRepo;
    private ListenerRegistration blocksReg;
    private Handler lockUiTicker;

    // ====== 其他 UI ======
    private MaterialButton btnSave, btnDelete, btnShare, btnToggleHighlights;
    private MaterialButton btnMark, btnUnmark; // 舊版按鈕，會隱藏
    private LinearLayout highlightList;

    // ====== 資料/狀態 ======
    private NoteRepository noteRepo;
    private String noteId;
    private String ownerId;
    private Note current;
    private final List<String> currentCollaborators = new ArrayList<>();

    private boolean highlightExpanded = false;
    private String myUid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note_edit);

        // Repo
        noteRepo = new NoteRepository();
        blockRepo = new BlockRepository();

        // Intent 參數
        noteId  = getIntent().getStringExtra("note_id");
        ownerId = getIntent().getStringExtra("owner_id");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        if (TextUtils.isEmpty(ownerId)) ownerId = myUid;

        // 綁 UI
        etTitle   = findViewById(R.id.etTitle);
        etStack   = findViewById(R.id.etStack);
        etChapter = findViewById(R.id.etChapter);
        etSection = findViewById(R.id.etSection);
        etContent = findViewById(R.id.etContent);

        btnSave   = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);
        btnShare  = findViewById(R.id.btnShare);
        btnMark   = findViewById(R.id.btnMark);
        btnUnmark = findViewById(R.id.btnUnmark);
        btnToggleHighlights = findViewById(R.id.btnToggleHighlights);
        highlightList = findViewById(R.id.highlightList);

        // 隱藏舊內容/舊標記按鈕
        if (etContent != null) etContent.setVisibility(View.GONE);
        if (btnMark != null) btnMark.setVisibility(View.GONE);
        if (btnUnmark != null) btnUnmark.setVisibility(View.GONE);

        // 區塊列表（注意：去掉了 onSave；其餘維持原本）
        rvBlocks = findViewById(R.id.rvBlocks);
        rvBlocks.setLayoutManager(new LinearLayoutManager(this));
        blockAdapter = new BlockAdapter(new BlockAdapter.Listener() {
            @Override
            public void onAcquireLock(@NonNull NoteBlock block) {
                blockRepo.acquireLock(ownerId, noteId, block.getId())
                        .addOnFailureListener(e -> Log.d("NoteEditActivity", "鎖定失敗：" + e.getMessage()));
            }

            @Override
            public void onReleaseLock(@NonNull NoteBlock block) {
                blockRepo.releaseLock(ownerId, noteId, block.getId())
                        .addOnFailureListener(e -> { /* ignore */ });
            }

            @Override
            public void onAddAfter(@NonNull NoteBlock block) {
                int nextIndex = block.getIndex() + 1;
                blockRepo.createBlock(ownerId, noteId, nextIndex, "text", "")
                        .addOnFailureListener(e ->
                                Toast.makeText(NoteEditActivity.this, "新增區塊失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onDelete(@NonNull NoteBlock block) {
                new AlertDialog.Builder(NoteEditActivity.this)
                        .setTitle("刪除此段落？")
                        .setMessage("確定要刪除這個區塊嗎？此操作無法復原。")
                        .setPositiveButton("刪除", (d, w) -> {
                            blockRepo.deleteBlock(ownerId, noteId, block.getId())
                                    .addOnSuccessListener(r ->
                                            Toast.makeText(NoteEditActivity.this, "已刪除", Toast.LENGTH_SHORT).show())
                                    .addOnFailureListener(e ->
                                            Toast.makeText(NoteEditActivity.this, "刪除失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        blockAdapter.setMyUid(myUid);
        rvBlocks.setAdapter(blockAdapter);

        // 權限：僅擁有者可刪除/設共編
        boolean iAmOwner = myUid.equals(ownerId);
        btnDelete.setEnabled(!TextUtils.isEmpty(noteId) && iAmOwner);
        btnShare.setEnabled(!TextUtils.isEmpty(noteId) && iAmOwner);

        // 載入基本資料
        if (!TextUtils.isEmpty(noteId)) {
            noteRepo.getNoteById(ownerId, noteId, note -> {
                current = note;
                if (note != null) {
                    etTitle.setText(note.getTitle());
                    etStack.setText(note.getStack());
                    etChapter.setText(note.getChapter() > 0 ? String.valueOf(note.getChapter()) : "");
                    etSection.setText(note.getSection() > 0 ? String.valueOf(note.getSection()) : "");
                    refreshHighlightPanel();
                    updateToggleText();
                }
            }, e -> Toast.makeText(this, "載入失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());

            noteRepo.getCollaborators(ownerId, noteId, list -> {
                currentCollaborators.clear();
                currentCollaborators.addAll(list);
            });
        } else {
            btnDelete.setEnabled(false);
            btnShare.setEnabled(false);
        }

        // 重點清單開關
        btnToggleHighlights.setOnClickListener(v -> {
            highlightExpanded = !highlightExpanded;
            highlightList.setVisibility(highlightExpanded ? View.VISIBLE : View.GONE);
            updateToggleText();
        });

        // 底部「儲存」：先存基本欄位再批次送出 blocks 的 drafts，最後釋放我的鎖
        btnSave.setOnClickListener(v -> {
            // 1) 先存標題/分類/章節/節（原本流程）
            if (!saveNoteBasics(ownerId)) {
                return; // 檢核失敗時已提示
            }
            // 2) 送出 blocks drafts
            saveAllBlockDraftsThenReleaseLocks();
        });

        // 刪除（僅擁有者）
        btnDelete.setOnClickListener(v -> {
            if (TextUtils.isEmpty(noteId)) return;
            String curUid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
            if (!curUid.equals(ownerId)) {
                Toast.makeText(this, "僅擁有者可刪除此筆記", Toast.LENGTH_SHORT).show();
                return;
            }
            noteRepo.deleteNote(noteId).addOnCompleteListener(t -> {
                if (t.isSuccessful()) {
                    Toast.makeText(this, "筆記已刪除", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    String msg = (t.getException() != null) ? t.getException().getMessage() : "未知錯誤";
                    Toast.makeText(this, "刪除失敗：" + msg, Toast.LENGTH_LONG).show();
                }
            });
        });

        // 設共編（僅擁有者）
        btnShare.setOnClickListener(v -> openShareDialog(iAmOwner));
    }

    // ======================= Lifecycle：監聽 blocks + UI 倒數 =======================

    @Override
    protected void onStart() {
        super.onStart();
        startBlocksListener();
        if (lockUiTicker == null) {
            lockUiTicker = new Handler(Looper.getMainLooper());
            lockUiTicker.post(lockClockTick);
        }
    }

    private void startBlocksListener() {
        if (blocksReg != null) return;
        if (TextUtils.isEmpty(ownerId) || TextUtils.isEmpty(noteId)) return;

        blocksReg = FirebaseFirestore.getInstance()
                .collection("users").document(ownerId)
                .collection("notes").document(noteId)
                .collection("blocks")
                .orderBy("index")
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;

                    List<NoteBlock> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        NoteBlock b = d.toObject(NoteBlock.class);
                        if (b == null) continue;
                        b.setId(d.getId());
                        list.add(b);
                    }

                    // 若空 → 一次性補第一個 block（把舊 content 搬進來；沒有就空字串）
                    if (list.isEmpty()) {
                        String initialText = (current != null && !TextUtils.isEmpty(current.getContent()))
                                ? current.getContent() : "";
                        blockRepo.createBlock(ownerId, noteId, 0, "text", initialText)
                                .addOnFailureListener(err ->
                                        Toast.makeText(NoteEditActivity.this, "自動建立區塊失敗：" + err.getMessage(), Toast.LENGTH_LONG).show());
                        return; // 等下一輪 snapshot 回來
                    }

                    blockAdapter.submitSorted(list);
                    refreshHighlightPanel();
                    updateToggleText();
                });
    }

    private final Runnable lockClockTick = new Runnable() {
        @Override public void run() {
            if (blockAdapter != null) blockAdapter.updateLockClock();
            if (lockUiTicker != null) lockUiTicker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onStop() {
        super.onStop();
        if (blocksReg != null) {
            blocksReg.remove();
            blocksReg = null;
        }
        if (lockUiTicker != null) {
            lockUiTicker.removeCallbacksAndMessages(null);
            lockUiTicker = null;
        }
        // 最佳努力釋放我持有的鎖
        if (blockAdapter != null) {
            List<NoteBlock> cur = blockAdapter.current();
            for (NoteBlock b : cur) {
                if (myUid.equals(b.getLockHolder()) && !lockExpired(b)) {
                    blockRepo.releaseLock(ownerId, noteId, b.getId());
                }
            }
        }
    }

    private boolean lockExpired(NoteBlock b) {
        Date until = b.getLockUntil();
        return until == null || System.currentTimeMillis() > until.getTime();
    }

    // ======================= 儲存：基本欄位 =======================

    private boolean saveNoteBasics(String noteOwnerId) {
        String title = s(etTitle), stack = s(etStack);
        int chapter = parseIntSafe(etChapter);
        int section = parseIntSafe(etSection);

        if (TextUtils.isEmpty(title)) {
            Toast.makeText(this, "請輸入標題", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (TextUtils.isEmpty(stack)) {
            Toast.makeText(this, "請填寫『大類別』", Toast.LENGTH_SHORT).show();
            return false;
        }

        btnSave.setEnabled(false);

        if (TextUtils.isEmpty(noteId)) {
            // 新增 note（僅基本屬性；內容改用 blocks）
            Note n = new Note(title, "");
            n.setStack(stack);
            n.setChapter(chapter);
            n.setSection(section);

            noteRepo.addNote(n, task -> {
                btnSave.setEnabled(true);
                if (task != null && task.isSuccessful() && task.getResult() != null) {
                    String newId = task.getResult().getId();
                    noteId = newId;
                    ownerId = myUid; // 新筆記一定是自己
                    Toast.makeText(this, "筆記已新增，已切換到區塊編輯", Toast.LENGTH_SHORT).show();

                    startBlocksListener();
                    btnDelete.setEnabled(true);
                    btnShare.setEnabled(true);

                } else {
                    String msg = (task != null && task.getException() != null)
                            ? task.getException().getMessage() : "未知錯誤";
                    Toast.makeText(this, "新增失敗：" + msg, Toast.LENGTH_LONG).show();
                }
            });

        } else {
            // 更新（支援共筆）
            Note n = (current != null) ? current : new Note();
            n.setId(noteId);
            n.setTitle(title);
            n.setStack(stack);
            n.setChapter(chapter);
            n.setSection(section);

            noteRepo.updateNote(n, noteOwnerId).addOnCompleteListener(t -> {
                btnSave.setEnabled(true);
                if (!t.isSuccessful()) {
                    String msg = (t.getException() != null) ? t.getException().getMessage() : "未知錯誤";
                    Toast.makeText(this, "更新失敗：" + msg, Toast.LENGTH_LONG).show();
                }
            });
        }
        return true;
    }

    // ======================= 儲存：一次送出所有 block 草稿 + 釋放鎖 =======================

    private void saveAllBlockDraftsThenReleaseLocks() {
        if (TextUtils.isEmpty(ownerId) || TextUtils.isEmpty(noteId)) {
            Toast.makeText(this, "請先建立或載入筆記", Toast.LENGTH_SHORT).show();
            return;
        }

        // 取出所有變更的 drafts
        List<BlockAdapter.PendingEdit> edits = blockAdapter.collectPendingEdits();

        if (edits.isEmpty()) {
            // 沒有內容變更也要釋放我持有的鎖
            releaseAllMyLocks();
            Toast.makeText(this, "已儲存（無內容變更）並釋放鎖", Toast.LENGTH_SHORT).show();
            return;
        }

        AtomicInteger done = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        int total = edits.size();

        for (BlockAdapter.PendingEdit pe : edits) {
            NoteBlock b = pe.block;
            String newText = pe.newText;
            // 使用目前的 index/type 與版本送出
            blockRepo.updateBlock(ownerId, noteId, b.getId(), newText, b.getType(), b.getIndex(), (int) b.getVersion())
                    .addOnSuccessListener(v -> {
                        blockAdapter.clearDraft(b.getId());
                        if (done.incrementAndGet() + fail.get() == total) {
                            // 全部完成後釋放我持有的鎖
                            releaseAllMyLocks();
                            Toast.makeText(this,
                                    fail.get() == 0 ? "已儲存所有變更並釋放鎖" : ("部分儲存失敗（" + fail.get() + "/" + total + "）並已釋放鎖"),
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .addOnFailureListener(e -> {
                        fail.incrementAndGet();
                        if (done.get() + fail.get() == total) {
                            releaseAllMyLocks();
                            Toast.makeText(this,
                                    "儲存失敗部分（" + fail.get() + "/" + total + "）並已釋放鎖：" + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        }
    }

    private void releaseAllMyLocks() {
        List<NoteBlock> cur = blockAdapter.current();
        for (NoteBlock b : cur) {
            if (myUid.equals(b.getLockHolder()) && !lockExpired(b)) {
                blockRepo.releaseLock(ownerId, noteId, b.getId());
            }
        }
    }

    // ======================= 共編設定（僅擁有者） =======================

    private void openShareDialog(boolean iAmOwner) {
        if (TextUtils.isEmpty(noteId)) {
            Toast.makeText(this, "請先儲存筆記再設定共編", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!iAmOwner) {
            Toast.makeText(this, "僅擁有者可設定共編", Toast.LENGTH_SHORT).show();
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
                names[i] = formatFriendLabel(f);
                uids.add(f.getUid());
                checked[i] = currentCollaborators.contains(f.getUid());
            }

            runOnUiThread(() -> {
                final AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle("選取共編者")
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton("套用", null)
                        .setNegativeButton("取消", null)
                        .create();
                dlg.show();

                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    v.setEnabled(false);
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(uids.get(i));

                    noteRepo.setCollaborators(ownerId, noteId, picked, task -> {
                        v.setEnabled(true);
                        boolean ok = task != null && task.isSuccessful();
                        Toast.makeText(this, ok ? "已更新共編者" : "更新失敗", Toast.LENGTH_SHORT).show();
                        if (ok) {
                            currentCollaborators.clear();
                            currentCollaborators.addAll(picked);
                            dlg.dismiss();
                        }
                    });
                });
            });
        });
    }

    private String formatFriendLabel(Friend f) {
        if (!TextUtils.isEmpty(f.getDisplayName())) return f.getDisplayName();
        if (!TextUtils.isEmpty(f.getEmail())) return f.getEmail();
        return f.getUid();
    }

    // ======================= 重點清單（從 blocks 萃取） =======================

    private void refreshHighlightPanel() {
        if (btnToggleHighlights == null || highlightList == null || blockAdapter == null) return;

        StringBuilder sb = new StringBuilder();
        for (NoteBlock b : blockAdapter.current()) {
            String type = b.getType() == null ? "" : b.getType().toLowerCase();
            if ("text".equals(type) || "paragraph".equals(type) || TextUtils.isEmpty(type)) {
                if (!TextUtils.isEmpty(b.getText())) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(b.getText());
                }
            }
        }
        List<String> hs = HighlightUtils.extractHighlights(sb.toString());
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
        if (btnToggleHighlights == null || blockAdapter == null) return;

        StringBuilder sb = new StringBuilder();
        for (NoteBlock b : blockAdapter.current()) {
            String type = b.getType() == null ? "" : b.getType().toLowerCase();
            if ("text".equals(type) || "paragraph".equals(type) || TextUtils.isEmpty(type)) {
                if (!TextUtils.isEmpty(b.getText())) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(b.getText());
                }
            }
        }
        int count = HighlightUtils.extractHighlights(sb.toString()).size();
        String base = "顯示重點 (" + count + ")";
        btnToggleHighlights.setText(highlightExpanded ? base + " ▲" : base + " ▼");
    }

    // ======================= 小工具 =======================

    private String s(TextInputEditText et) {
        return et == null || et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int parseIntSafe(TextInputEditText et) {
        String v = s(et);
        try { return TextUtils.isEmpty(v) ? 0 : Integer.parseInt(v); }
        catch (Exception ex) { return 0; }
    }
}
