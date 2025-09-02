package com.ld.ainote.adapters;

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
