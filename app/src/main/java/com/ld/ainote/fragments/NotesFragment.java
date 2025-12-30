package com.ld.ainote.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.ld.ainote.NoteEditActivity;
import com.ld.ainote.R;
import com.ld.ainote.adapters.NoteAdapter;
import com.ld.ainote.data.BlockRepository;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NotesFragment extends Fragment {

    // Firestore
    private FirebaseFirestore db;
    private ListenerRegistration regMine;
    private ListenerRegistration regShared;

    // 既有 Repo（用於新增/刪除）
    private NoteRepository repo;

    // UI
    private TextInputEditText etSearch;
    private MaterialSwitch swGroup;
    private RecyclerView rv;
    private TextView tvEmpty;
    private ProgressBar progress;
    private MaterialButton btnAdd;
    private TextView tvScope; // 可為 null（舊 layout 沒有時不會崩潰）

    // Adapter 與本地快取
    private NoteAdapter adapter;
    private final List<Note> notesCache = new ArrayList<>();
    private final Set<String> categoriesLocal = new HashSet<>();

    // 群組模式下：展開中的類別
    private final Set<String> expandedCategories = new HashSet<>();

    // 合併兩條監聽資料的暫存（key = ownerId#noteId）
    private final Map<String, Note> mergeMap = new HashMap<>();

    // 來源分離的快取：我的 / 共筆
    private final Map<String, Note> mapMine   = new HashMap<>();
    private final Map<String, Note> mapShared = new HashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        // Init
        db = FirebaseFirestore.getInstance();
        repo = new NoteRepository();

        etSearch = v.findViewById(R.id.etSearch);
        swGroup  = v.findViewById(R.id.swGroup);
        rv       = v.findViewById(R.id.rvNotes);
        tvEmpty  = v.findViewById(R.id.tvEmpty);
        progress = v.findViewById(R.id.progress);
        btnAdd   = v.findViewById(R.id.btnAdd);
        tvScope  = v.findViewById(R.id.tvScope);

        if (tvScope != null) {
            tvScope.setText("顯示：我的筆記 + 共筆");
        }

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new NoteAdapter();
        rv.setAdapter(adapter);

        adapter.setOnItemClickListener(note -> {
            // 進編輯頁時要帶真正擁有者的 ownerId（共筆時不能用自己的 UID）
            Intent it = new Intent(getContext(), NoteEditActivity.class);
            it.putExtra("note_id", note.getId());
            it.putExtra("owner_id", note.getOwnerId());
            startActivity(it);
        });

        // 左滑刪除（跳過 header；非擁有者不可刪）
        ItemTouchHelper.SimpleCallback swipe = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getAdapterPosition();
                String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                        ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

                // 1) Header：整類刪除（維持你原本的確認流程）
                if (adapter.isHeader(pos)) {
                    String category = adapter.getHeaderForPosition(pos);
                    if (TextUtils.isEmpty(category)) { adapter.notifyItemChanged(pos); return; }
                    confirmAndCascadeDeleteCategory(category, pos, myUid);
                    return;
                }

                // 2) 單筆 Note
                Note n = adapter.getItem(pos);
                if (n == null) { adapter.notifyItemChanged(pos); return; }

                if (!TextUtils.equals(myUid, n.getOwnerId())) {
                    // 非擁有者不可刪
                    adapter.notifyItemChanged(pos);
                    Toast.makeText(requireContext(), "僅擁有者可刪除此筆記", Toast.LENGTH_SHORT).show();
                    return;
                }

                // ✅ 先把滑動外觀復位，再跳出確認彈窗
                adapter.notifyItemChanged(pos);
                confirmDeleteSingleNote(n, pos);
            }
        };
        new ItemTouchHelper(swipe).attachToRecyclerView(rv);

        // Header 單擊展開/收合；只攔截 Header 點擊，不攔截 Note
        rv.addOnItemTouchListener(new RecyclerItemClickListener(rv, adapter, (view1, position) -> {
            if (!swGroup.isChecked()) return;          // 群組模式才處理
            if (!adapter.isHeader(position)) return;   // 只處理 Header
            String category = adapter.getHeaderForPosition(position);
            if (TextUtils.isEmpty(category)) return;

            if (expandedCategories.contains(category)) expandedCategories.remove(category);
            else expandedCategories.add(category);

            adapter.setExpandedCategories(expandedCategories);
        }));

        // 新增（選單）
        btnAdd.setOnClickListener(view -> {
            final String[] options = {"新增類別", "新增筆記"};
            new AlertDialog.Builder(requireContext())
                    .setTitle("選擇動作")
                    .setItems(options, (d, which) -> {
                        if (which == 0) showCreateCategoryDialog();
                        else showCreateNoteDialogWithDropdown();
                    })
                    .show();
        });

        // 搜尋
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setKeyword(s == null ? null : s.toString());
                if (swGroup.isChecked()) {
                    // 搜尋時預設收起，避免殘留展開狀態
                    expandedCategories.clear();
                    adapter.setExpandedCategories(expandedCategories);
                }
                updateEmpty();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 群組切換：預設全部收起
        swGroup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            adapter.setGrouped(isChecked);
            expandedCategories.clear(); // 預設收起
            adapter.setExpandedCategories(expandedCategories);
            updateEmpty();
        });

        // 一進頁面就啟用群組模式 + 全部收起
        swGroup.setChecked(true);

        // 啟動 Firestore 監聽：我的 + 共筆
        startListening();
    }

    // ===================== Firestore 監聽合併 =====================

    private void startListening() {
        stopListening(); // 先移除舊監聽（保險）

        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        if (TextUtils.isEmpty(myUid)) {
            Toast.makeText(requireContext(), "尚未登入", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);

        // 我的筆記：/users/{uid}/notes
        CollectionReference myNotesCol = db.collection("users").document(myUid).collection("notes");
        regMine = myNotesCol.addSnapshotListener((snap, err) -> {
            if (!isAdded()) return;
            if (err != null) {
                setLoading(false);
                Toast.makeText(requireContext(), "同步失敗（我的筆記）：" + err.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            mergeFromSnapshot(snap, /*fromMine=*/true, myUid);
        });

        // 被分享給我的筆記：
        // 假設每個 Note 有欄位 collaborators:Array<String>，放共編者 uid
        // 用 collectionGroup("notes") + array-contains 查到所有擁有者的 notes 中「包含我」的
        Query sharedQuery = db.collectionGroup("notes").whereArrayContains("collaborators", myUid);
        regShared = sharedQuery.addSnapshotListener((snap, err) -> {
            if (!isAdded()) return;
            if (err != null) {
                setLoading(false);
                Log.d("ERROR", "startListening: err ="+ err.getMessage());
                Toast.makeText(requireContext(), "同步失敗（共筆）：" + err.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            mergeFromSnapshot(snap, /*fromMine=*/false, myUid);
        });
    }

    private void stopListening() {
        if (regMine != null) { regMine.remove(); regMine = null; }
        if (regShared != null) { regShared.remove(); regShared = null; }
    }

    private void mergeFromSnapshot(@Nullable QuerySnapshot snap, boolean fromMine, @NonNull String myUid) {
        if (snap == null) return;

        // 針對「此次回來的來源」先清空，再重建（這一步修正了刪除後殘留的問題）
        Map<String, Note> target = fromMine ? mapMine : mapShared;
        target.clear();

        for (DocumentSnapshot d : snap.getDocuments()) {
            Note n = new Note();
            n.setId(d.getId());
            n.setTitle(emptyIfNull(d.getString("title")));
            n.setContent(emptyIfNull(d.getString("content")));
            n.setStack(emptyIfNull(d.getString("stack")));
            n.setChapter(intFrom(d.get("chapter")));
            n.setSection(intFrom(d.get("section")));

            // 讀 collaborators（若有）
            List<String> collabs = (List<String>) d.get("collaborators");
            if (collabs != null) n.setCollaborators(collabs);

            // 讀 timestamp（若有）
            Date ts = d.getDate("timestamp");
            if (ts != null) n.setTimestamp(ts);

            // 取得 ownerId：我的→myUid；共筆→由文件路徑取上層 users/{ownerId}
            String ownerId;
            if (fromMine) {
                ownerId = myUid;
            } else {
                DocumentReference ref = d.getReference();
                // ref: .../users/{ownerId}/notes/{noteId}
                ownerId = "";
                if (ref != null && ref.getParent() != null && ref.getParent().getParent() != null) {
                    ownerId = ref.getParent().getParent().getId();
                }
            }
            n.setOwnerId(ownerId);

            // ✅ 設定是否共筆：owner 不是自己即視為共筆
            n.setShared(!TextUtils.equals(ownerId, myUid));

            String key = ownerId + "#" + n.getId();
            target.put(key, n);
        }

        // 重新組合兩個來源的快取 → 給 Adapter
        mergeMap.clear();
        mergeMap.putAll(mapMine);
        mergeMap.putAll(mapShared);

        // 轉 List、排序、更新本地快取與 UI
        List<Note> merged = new ArrayList<>(mergeMap.values());

        notesCache.clear();
        notesCache.addAll(merged);
        rebuildCategoriesFromNotes();

        Collections.sort(merged, (a, b) -> {
            String sa = nullSafe(a.getStack());
            String sb = nullSafe(b.getStack());
            int c = sa.compareToIgnoreCase(sb);
            if (c != 0) return c;
            c = Integer.compare(a.getChapter(), b.getChapter());
            if (c != 0) return c;
            c = Integer.compare(a.getSection(), b.getSection());
            if (c != 0) return c;
            return nullSafe(a.getTitle()).compareToIgnoreCase(nullSafe(b.getTitle()));
        });

        adapter.submitAll(merged);

        if (swGroup != null && swGroup.isChecked()) {
            expandedCategories.clear(); // 群組模式下維持預設收起
            adapter.setExpandedCategories(expandedCategories);
        }

        setLoading(false);
        updateEmpty();
    }
    // ===================== 新增類別/筆記 =====================

    private void showCreateCategoryDialog() {
        final TextInputEditText input = new TextInputEditText(requireContext());
        input.setHint("輸入大類別（例如：國文）");
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(p, p, p, p);

        new AlertDialog.Builder(requireContext())
                .setTitle("新增類別")
                .setView(input)
                .setPositiveButton("新增", (d, w) -> {
                    String name = s(input);
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(requireContext(),"請輸入名稱",Toast.LENGTH_SHORT).show();
                        return;
                    }
                    categoriesLocal.add(name);
                    Toast.makeText(requireContext(), "已新增類別「" + name + "」", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCreateNoteDialogWithDropdown() {
        List<String> categories = buildCategoryListForDropdown();
        boolean hasCategories = !categories.isEmpty();
        String defaultCat = hasCategories ? categories.get(0) : "";

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        TextInputLayout tilCat = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);

        final TextInputEditText[] etCatInput = new TextInputEditText[1];
        final MaterialAutoCompleteTextView[] actCatDropdown = new MaterialAutoCompleteTextView[1];

        if (hasCategories) {
            tilCat.setHint("選擇大類別");
            tilCat.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

            actCatDropdown[0] = new MaterialAutoCompleteTextView(tilCat.getContext());
            actCatDropdown[0].setId(View.generateViewId());
            ArrayAdapter<String> dropAdapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_list_item_1, categories);
            actCatDropdown[0].setAdapter(dropAdapter);
            actCatDropdown[0].setText(defaultCat, false);

            actCatDropdown[0].setKeyListener(null);
            actCatDropdown[0].setCursorVisible(false);
            actCatDropdown[0].setFocusable(false);
            actCatDropdown[0].setOnClickListener(v -> actCatDropdown[0].showDropDown());

            tilCat.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);

            tilCat.addView(actCatDropdown[0], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            tilCat.setHint("輸入大類別（例如：國文）");
            tilCat.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

            etCatInput[0] = new TextInputEditText(tilCat.getContext());
            tilCat.addView(etCatInput[0], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        form.addView(tilCat);

        // 章
        TextInputLayout tilChap = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        tilChap.setHint("章（例如：1）");
        tilChap.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText etChap = new TextInputEditText(tilChap.getContext());
        etChap.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etChap.setText("1");
        tilChap.addView(etChap);
        form.addView(tilChap);

        // 節
        TextInputLayout tilSec = new TextInputLayout(requireContext(), null,
                com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        tilSec.setHint("節（例如：2；顯示為 1-2）");
        tilSec.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText etSec = new TextInputEditText(tilSec.getContext());
        etSec.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        tilSec.addView(etSec);
        form.addView(tilSec);

        // 初始自動帶節號（依現在的類別 + 章）
        String initCat = hasCategories
                ? (actCatDropdown[0].getText()==null?"":actCatDropdown[0].getText().toString().trim())
                : (etCatInput[0]==null?"":etCatInput[0].getText()==null?"":etCatInput[0].getText().toString().trim());
        computeNextSection(initCat, safeParse(etChap.getText()), next -> etSec.setText(String.valueOf(next)));

        // 章或類別變更時，重算下一節
        TextWatcher recalc = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String cat = hasCategories
                        ? (actCatDropdown[0].getText()==null?"":actCatDropdown[0].getText().toString().trim())
                        : (etCatInput[0]==null?"":etCatInput[0].getText()==null?"":etCatInput[0].getText().toString().trim());
                int chap = safeParse(etChap.getText());
                computeNextSection(cat, chap, next -> etSec.setText(String.valueOf(next)));
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        etChap.addTextChangedListener(recalc);
        if (hasCategories) actCatDropdown[0].addTextChangedListener(recalc);
        else if (etCatInput[0] != null) etCatInput[0].addTextChangedListener(recalc);

        new AlertDialog.Builder(requireContext())
                .setTitle("新增筆記")
                .setView(form)
                .setPositiveButton("建立", (d, w) -> {
                    String cat = hasCategories
                            ? (actCatDropdown[0].getText()==null?"":actCatDropdown[0].getText().toString().trim())
                            : (etCatInput[0]==null?"":etCatInput[0].getText()==null?"":etCatInput[0].getText().toString().trim());
                    int chap = safeParse(etChap.getText());
                    int sec  = safeParse(etSec.getText());
                    if (TextUtils.isEmpty(cat)) {
                        Toast.makeText(requireContext(), hasCategories ? "請選擇大類別" : "請輸入大類別", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String defaultTitle = TextUtils.isEmpty(cat)
                            ? (chap + "-" + sec)
                            : (cat + " " + chap + "-" + sec);
                    Note n = new Note(defaultTitle, ""); // ✅ 至少有 title
                    n.setStack(cat);
                    n.setChapter(chap);
                    n.setSection(sec);
                    repo.addNote(n, task -> {
                        if (task != null && task.isSuccessful() && task.getResult() != null) {
                            String newId = task.getResult().getId();

                            // 建立第一個空白區塊（index=0, type="text"）
                            BlockRepository blockRepo = new BlockRepository();
                            String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
                            blockRepo.createBlock(myUid, newId, /*index=*/0, "text", "")
                                    .addOnSuccessListener(v2 -> {
                                        categoriesLocal.add(cat);
                                        Toast.makeText(requireContext(), "已建立 " + chap + "-" + sec, Toast.LENGTH_SHORT).show();
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.d("NotesFrag", "showCreateNoteDialogWithDropdown: e = "+e.getMessage());
                                        Toast.makeText(requireContext(), "筆記建立成功，但新增區塊失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                                    });

                        } else {
                            Toast.makeText(requireContext(), "建立失敗", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<String> buildCategoryListForDropdown() {
        List<String> categories = new ArrayList<>(categoriesLocal);
        for (Note n : notesCache) {
            if (!TextUtils.isEmpty(n.getStack())) categories.add(n.getStack());
        }
        // 去重 + 排序
        categories = new ArrayList<>(new HashSet<>(categories));
        Collections.sort(categories, String::compareToIgnoreCase);
        return categories;
    }

    private void rebuildCategoriesFromNotes() {
        categoriesLocal.clear();
        for (Note n : notesCache) {
            if (!TextUtils.isEmpty(n.getStack())) categoriesLocal.add(n.getStack());
        }
    }

    // 依「類別 + 章」回傳目前最大節號 + 1
    private void computeNextSection(String category, int chapter, IntCallback cb) {
        int max = 0;
        if (!TextUtils.isEmpty(category) && chapter > 0) {
            for (Note n : notesCache) {
                if (category.equals(n.getStack()) && n.getChapter() == chapter) {
                    if (n.getSection() > max) max = n.getSection();
                }
            }
        }
        cb.accept(max + 1);
    }

    // ===================== UI 狀態 =====================

    private void updateEmpty() {
        boolean empty = (adapter.getItemCount() == 0);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void setLoading(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        rv.setAlpha(on ? 0.3f : 1f);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopListening();
    }

    // ===================== 小工具 =====================

    private interface IntCallback { void accept(int v); }
    private static String nullSafe(String s) { return s == null ? "" : s; }
    private static String s(TextInputEditText et) { return et.getText() == null ? "" : et.getText().toString().trim(); }

    private static int safeParse(CharSequence cs) {
        try {
            String v = (cs == null) ? "" : cs.toString().trim();
            return TextUtils.isEmpty(v) ? 0 : Integer.parseInt(v);
        } catch (Exception e) { return 0; }
    }

    private static String emptyIfNull(String s) { return s == null ? "" : s; }

    private static int intFrom(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (Exception e) { return 0; }
    }

    /**
     * 只攔截「Header 單擊」；點 Note 不攔截（單擊直接進編輯）
     */
    private static class RecyclerItemClickListener extends RecyclerView.SimpleOnItemTouchListener {
        interface OnItemClickListener { void onItemClick(View view, int position); }

        private final GestureDetector detector;
        private final NoteAdapter adapter;
        private final OnItemClickListener listener;

        RecyclerItemClickListener(RecyclerView rv, NoteAdapter adapter, OnItemClickListener l) {
            this.adapter = adapter;
            this.listener = l;
            this.detector = new GestureDetector(rv.getContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onSingleTapUp(MotionEvent e) { return true; }
                    });
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            View child = rv.findChildViewUnder(e.getX(), e.getY());
            if (child != null && detector.onTouchEvent(e)) {
                int pos = rv.getChildAdapterPosition(child);
                if (pos != RecyclerView.NO_POSITION) {
                    if (adapter != null && adapter.isHeader(pos)) {
                        listener.onItemClick(child, pos);
                        return true;   // consume header click
                    } else {
                        return false;  // do NOT consume note click
                    }
                }
            }
            return false;
        }
    }

    /** 大類別整類刪除：僅刪除「我擁有」且 stack == category 的筆記；共筆不會刪除（改走 Functions）。 */
    private void confirmAndCascadeDeleteCategory(@NonNull String category, int headerPos, @NonNull String myUid) {
        // 找出你擁有且屬於此大類別的所有筆記（僅用來顯示數量與權限檢查）
        List<Note> owned = new ArrayList<>();
        for (Note n : notesCache) {
            if (n == null) continue;
            if (TextUtils.equals(category, n.getStack()) && TextUtils.equals(myUid, n.getOwnerId())) {
                owned.add(n);
            }
        }

        if (owned.isEmpty()) {
            // 沒有你擁有的資料可刪 → 還原 Header 外觀，提示
            adapter.notifyItemChanged(headerPos);
            Toast.makeText(requireContext(), "此類別底下沒有你擁有的筆記可刪除（共筆資料不會被刪）", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("刪除大類別")
                .setMessage("將刪除「" + category + "」底下你擁有的 " + owned.size() + " 筆章節/筆記，且無法復原。確定要刪除嗎？")
                .setPositiveButton("刪除", (d, w) -> {
                    setLoading(true);

                    // 改呼叫後端 API（Cloud Functions）
                    repo.deleteNotesByCategory(category)
                            .addOnSuccessListener(deletedCount -> {
                                setLoading(false);
                                adapter.notifyItemChanged(headerPos); // 還原 Header 外觀
                                Snackbar.make(requireView(),
                                        "已刪除「" + category + "」底下的 " + deletedCount + " 筆（僅刪除你擁有的）",
                                        Snackbar.LENGTH_LONG).show();
                            })
                            .addOnFailureListener(e -> {
                                setLoading(false);
                                adapter.notifyItemChanged(headerPos);
                                Toast.makeText(requireContext(), "刪除失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                })
                .setNegativeButton("取消", (d, w) -> {
                    // 使用者取消 → 還原 Header 外觀
                    adapter.notifyItemChanged(headerPos);
                })
                .show();
    }

    private void confirmDeleteSingleNote(@NonNull Note n, int pos) {
        String title = TextUtils.isEmpty(n.getTitle()) ? "(無標題)" : n.getTitle();

        new AlertDialog.Builder(requireContext())
                .setTitle("刪除筆記")
                .setMessage("確定要刪除「" + title + "」嗎？")
                .setPositiveButton("刪除", (d, w) -> {
                    setLoading(true);
                    repo.deleteNote(n.getId()).addOnCompleteListener(t -> {
                        setLoading(false);
                        if (!isAdded() || getView() == null) return;

                        if (t.isSuccessful()) {
                            // 讓 UI 立即有感（等 Firestore 回補也可以，但這樣更即時）
                            adapter.removeLocalAt(pos);

                            Snackbar.make(getView(),
                                            "已刪除「" + title + "」",
                                            Snackbar.LENGTH_LONG)
                                    .setAction("復原", v1 -> {
                                        // 將欄位補齊再新建一筆（ID 會是新的）
                                        Note restore = new Note(n.getTitle(), n.getContent());
                                        restore.setStack(n.getStack());
                                        restore.setChapter(n.getChapter());
                                        restore.setSection(n.getSection());
                                        restore.setTags(n.getTags());
                                        repo.addNote(restore, null);
                                    })
                                    .show();
                        } else {
                            Toast.makeText(requireContext(), "刪除失敗", Toast.LENGTH_SHORT).show();
                            // 保險：還原外觀（理論上前面已 notifyItemChanged）
                            if (pos >= 0 && pos < adapter.getItemCount()) {
                                adapter.notifyItemChanged(pos);
                            }
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
