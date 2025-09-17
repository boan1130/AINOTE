/*package com.ld.ainote.adapters;

import android.text.TextUtils;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.ld.ainote.R;
import com.ld.ainote.models.Note;
import java.text.SimpleDateFormat;
import java.util.*;

public class NoteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_NOTE = 0;
    private static final int VIEW_HEADER = 1;

    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    // 原始資料（來自 Firestore）
    private final List<Note> all = new ArrayList<>();
    // 展示列（含 header/notes）
    private final List<Row> rows = new ArrayList<>();

    private boolean grouped = false;
    private String keyword = null;
    private OnItemClickListener listener;

    public interface OnItemClickListener { void onItemClick(Note n); }
    public void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

    public static class Row {
        boolean header;
        String headerTitle;
        Note note;
        Row(String headerTitle){ this.header = true; this.headerTitle = headerTitle; }
        Row(Note n){ this.header = false; this.note = n; }
    }

    public void submitAll(List<Note> notes) {
        all.clear();
        if (notes != null) all.addAll(notes);
        rebuild();
    }

    public void setGrouped(boolean on) {
        this.grouped = on;
        rebuild();
    }

    public void setKeyword(String kw) {
        this.keyword = TextUtils.isEmpty(kw) ? null : kw.toLowerCase(Locale.ROOT);
        rebuild();
    }

    // 供外部操作滑動刪除後的即時移除（可選）
    public void removeLocalAt(int position) {
        if (position < 0 || position >= rows.size()) return;
        if (rows.get(position).header) return; // 不移除 header
        rows.remove(position);
        notifyItemRemoved(position);
    }

    public boolean isHeader(int position) {
        if (position < 0 || position >= rows.size()) return false;
        return rows.get(position).header;
    }

    public Note getItem(int position) {
        if (position < 0 || position >= rows.size()) return null;
        Row r = rows.get(position);
        return r.header ? null : r.note;
    }

    public List<Note> getAll() { return new ArrayList<>(all); }

    private void rebuild() {
        rows.clear();

        // 1) 過濾
        List<Note> filtered = new ArrayList<>();
        for (Note n : all) {
            if (keyword == null) {
                filtered.add(n);
            } else {
                String t = n.getTitle() == null ? "" : n.getTitle().toLowerCase(Locale.ROOT);
                String c = n.getContent() == null ? "" : n.getContent().toLowerCase(Locale.ROOT);
                if (t.contains(keyword) || c.contains(keyword)) filtered.add(n);
            }
        }

        // 2) 群組 or 扁平
        if (!grouped) {
            for (Note n : filtered) rows.add(new Row(n));
        } else {
            // 以 stack 名稱分組；null/空字串歸 "(未分組)"
            LinkedHashMap<String, List<Note>> map = new LinkedHashMap<>();
            for (Note n : filtered) {
                String key = (n.getStack() == null || n.getStack().trim().isEmpty()) ? "(未分組)" : n.getStack().trim();
                if (!map.containsKey(key)) map.put(key, new ArrayList<>());
                map.get(key).add(n);
            }
            for (Map.Entry<String, List<Note>> e : map.entrySet()) {
                rows.add(new Row(e.getKey()));
                for (Note n : e.getValue()) rows.add(new Row(n));
            }
        }
        notifyDataSetChanged();
    }

    @Override public int getItemViewType(int position) {
        return rows.get(position).header ? VIEW_HEADER : VIEW_NOTE;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int viewType) {
        LayoutInflater inf = LayoutInflater.from(p.getContext());
        if (viewType == VIEW_HEADER) {
            View v = inf.inflate(R.layout.item_section_header, p, false);
            return new HeaderVH(v);
        } else {
            View v = inf.inflate(R.layout.item_note, p, false);
            return new NoteVH(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        Row r = rows.get(pos);
        if (r.header) {
            HeaderVH vh = (HeaderVH) h;
            vh.tvHeader.setText(r.headerTitle);
        } else {
            Note n = r.note;
            NoteVH vh = (NoteVH) h;
            vh.tvTitle.setText(n.getTitle());
            vh.tvContent.setText(n.getContent());
            if (n.getTimestamp() != null) {
                vh.tvTimestamp.setText(fmt.format(n.getTimestamp()));
            } else {
                vh.tvTimestamp.setText("");
            }
            vh.itemView.setOnClickListener(v -> { if (listener != null) listener.onItemClick(n); });
        }
    }

    @Override public int getItemCount() { return rows.size(); }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderVH(@NonNull View v){ super(v); tvHeader = v.findViewById(R.id.tvHeader); }
    }

    static class NoteVH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvContent, tvTimestamp;
        NoteVH(@NonNull View v){
            super(v);
            tvTitle = v.findViewById(R.id.tvTitle);
            tvContent = v.findViewById(R.id.tvContent);
            tvTimestamp = v.findViewById(R.id.tvTimestamp);
        }
    }

}
*/

package com.ld.ainote.adapters;

import android.text.TextUtils;
import android.view.*;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.ld.ainote.R;
import com.ld.ainote.models.Note;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 支援：
 * - 扁平清單 / 依 stack(大類別) 群組顯示
 * - 群組模式下的展開/收合（由外部傳入 expandedCategories 控制）
 * - Header 點擊辨識：getHeaderForPosition(position)
 * - 依需求在標題前顯示 chapter-section（例如 1-2）
 */
public class NoteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_NOTE   = 0;
    private static final int VIEW_HEADER = 1;

    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    // 原始資料（來自 Firestore → submitAll）
    private final List<Note> all = new ArrayList<>();
    // 產生後用於顯示的列（Header + Notes）
    private final List<Row> rows = new ArrayList<>();

    // 狀態
    private boolean grouped = false;
    private String keyword = null;

    // 展開中的類別（只在 grouped=true 時生效）
    private final Set<String> expandedCategories = new HashSet<>();

    // 點擊 callback
    public interface OnItemClickListener { void onItemClick(Note n); }
    private OnItemClickListener listener;
    public void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

    // 內部行定義
    public static class Row {
        final boolean header;
        final String headerTitle; // 僅 header 用
        final int headerChildCount; // header 下子項數量（用於顯示）
        final Note note;          // 僅 note 用

        // Header 列
        Row(String headerTitle, int childCount) {
            this.header = true;
            this.headerTitle = headerTitle;
            this.headerChildCount = childCount;
            this.note = null;
        }

        // Note 列
        Row(Note n) {
            this.header = false;
            this.headerTitle = null;
            this.headerChildCount = 0;
            this.note = n;
        }
    }

    // ============= 對外 API =============

    public void submitAll(List<Note> notes) {
        all.clear();
        if (notes != null) all.addAll(notes);
        rebuild();
    }

    public void setGrouped(boolean on) {
        this.grouped = on;
        rebuild();
    }

    public void setKeyword(String kw) {
        this.keyword = TextUtils.isEmpty(kw) ? null : kw.toLowerCase(Locale.ROOT);
        rebuild();
    }

    /** 群組模式下：設定哪些類別是展開狀態 */
    public void setExpandedCategories(Set<String> expanded) {
        expandedCategories.clear();
        if (expanded != null) expandedCategories.addAll(expanded);
        if (grouped) rebuild();  // 只有群組模式需要重建 rows
    }

    /** 回傳該 position 的 Header 所代表的類別名稱；若非 Header 或越界，回傳 null */
    @Nullable
    public String getHeaderForPosition(int position) {
        if (position < 0 || position >= rows.size()) return null;
        Row r = rows.get(position);
        return r.header ? r.headerTitle : null;
    }

    /** 供外部在滑動刪除後即時拿掉一列（注意：不會更動 all） */
    public void removeLocalAt(int position) {
        if (position < 0 || position >= rows.size()) return;
        if (rows.get(position).header) return; // 不移除 header
        rows.remove(position);
        notifyItemRemoved(position);
    }

    public boolean isHeader(int position) {
        if (position < 0 || position >= rows.size()) return false;
        return rows.get(position).header;
    }

    @Nullable
    public Note getItem(int position) {
        if (position < 0 || position >= rows.size()) return null;
        Row r = rows.get(position);
        return r.header ? null : r.note;
    }

    /** 需要時可取出目前的原始資料（例如外部統計或導出） */
    public List<Note> getAll() { return new ArrayList<>(all); }

    // ============= 內部重建展示列 =============

    private void rebuild() {
        rows.clear();

        // 1) 過濾（關鍵字比對標題/內容；可視需求再加 stack 比對）
        List<Note> filtered = new ArrayList<>();
        for (Note n : all) {
            if (keyword == null) {
                filtered.add(n);
            } else {
                String t = safeLower(n.getTitle());
                String c = safeLower(n.getContent());
                if (t.contains(keyword) || c.contains(keyword)) filtered.add(n);
            }
        }

        if (!grouped) {
            // 2A) 扁平模式：照順序直接塞 rows
            for (Note n : filtered) rows.add(new Row(n));
        } else {
            // 2B) 群組模式：依 stack 分組；空值視為 "(未分組)"
            // 保持插入順序：LinkedHashMap
            LinkedHashMap<String, List<Note>> map = new LinkedHashMap<>();
            for (Note n : filtered) {
                String key = normalizeStack(n.getStack());
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
            }

            // 按章節排序（假設外部已經大致排序過；這裡保險再針對每組做一次章/節排序）
            for (Map.Entry<String, List<Note>> e : map.entrySet()) {
                List<Note> group = e.getValue();
                group.sort((a, b) -> {
                    int c = Integer.compare(a.getChapter(), b.getChapter());
                    if (c != 0) return c;
                    return Integer.compare(a.getSection(), b.getSection());
                });
            }

            // 只顯示 Header，若該類別有在 expandedCategories 內才加上子筆記
            for (Map.Entry<String, List<Note>> e : map.entrySet()) {
                String cat = e.getKey();
                List<Note> items = e.getValue();
                boolean expanded = expandedCategories.contains(cat);
                rows.add(new Row(cat, items.size()));
                if (expanded) {
                    for (Note n : items) rows.add(new Row(n));
                }
            }
        }

        notifyDataSetChanged();
    }

    // ============= RecyclerView 標準實作 =============

    @Override public int getItemViewType(int position) {
        return rows.get(position).header ? VIEW_HEADER : VIEW_NOTE;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int viewType) {
        LayoutInflater inf = LayoutInflater.from(p.getContext());
        if (viewType == VIEW_HEADER) {
            View v = inf.inflate(R.layout.item_section_header, p, false);
            return new HeaderVH(v);
        } else {
            View v = inf.inflate(R.layout.item_note, p, false);
            return new NoteVH(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        Row r = rows.get(pos);
        if (r.header) {
            HeaderVH vh = (HeaderVH) h;

            // 顯示「類別名（子項數）▼/▲」
            String cat = r.headerTitle;
            boolean expanded = expandedCategories.contains(cat);
            String arrow = expanded ? " ▲" : " ▼";
            String text = cat + "（" + r.headerChildCount + "）" + arrow;
            vh.tvHeader.setText(text);

            // 可選：讓整個 header row 看起來可點（加粗/大小/顏色等，交給 item_section_header.xml 控制）
        } else {
            Note n = r.note;
            NoteVH vh = (NoteVH) h;

            // 在標題前加上「chapter-section」前綴（若有）
            String prefix = buildIndexPrefix(n.getChapter(), n.getSection());
            String title = n.getTitle();
            if (TextUtils.isEmpty(title)) title = "(無標題)";
            vh.tvTitle.setText(prefix.isEmpty() ? title : (prefix + " " + title));

            vh.tvContent.setText(n.getContent());

            if (n.getTimestamp() != null) {
                vh.tvTimestamp.setText(fmt.format(n.getTimestamp()));
            } else {
                vh.tvTimestamp.setText("");
            }

            vh.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(n);
            });
        }
    }

    @Override public int getItemCount() { return rows.size(); }

    // ============= ViewHolders =============

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderVH(@NonNull View v){ super(v); tvHeader = v.findViewById(R.id.tvHeader); }
    }

    static class NoteVH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvContent, tvTimestamp;
        NoteVH(@NonNull View v){
            super(v);
            tvTitle     = v.findViewById(R.id.tvTitle);
            tvContent   = v.findViewById(R.id.tvContent);
            tvTimestamp = v.findViewById(R.id.tvTimestamp);
        }
    }

    // ============= 小工具 =============

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String normalizeStack(String s) {
        if (s == null) return "(未分組)";
        String t = s.trim();
        return t.isEmpty() ? "(未分組)" : t;
    }

    private static String buildIndexPrefix(int chapter, int section) {
        if (chapter > 0 && section > 0) return chapter + "-" + section;
        if (chapter > 0) return String.valueOf(chapter);
        return "";
    }
}
