package com.ld.ainote.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.ld.ainote.R;
import com.ld.ainote.models.Friend;

import java.text.SimpleDateFormat;
import java.util.*;

public class FriendAdapter extends RecyclerView.Adapter<FriendAdapter.VH> {

    private final List<Friend> data = new ArrayList<>();
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    public void setData(List<Friend> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    /** 依 uid 取得現有 Friend（可能回傳 null） */
    @Nullable
    public Friend findByUid(@NonNull String uid) {
        for (Friend f : data) {
            if (uid.equals(f.getUid())) return f;
        }
        return null;
    }

    /**
     * 合併式 upsert：
     * - 以 uid 配對
     * - 新值優先；若新值為空，保留舊值，避免把已顯示的名稱/筆記數蓋掉為空或 0
     */
    public void upsert(@NonNull Friend incoming) {
        int idx = -1;
        for (int i = 0; i < data.size(); i++) {
            if (Objects.equals(data.get(i).getUid(), incoming.getUid())) { idx = i; break; }
        }

        if (idx >= 0) {
            Friend old = data.get(idx);
            Friend merged = new Friend();
            merged.setUid(old.getUid());

            // 顯示名稱：新 displayName 若空，保留舊；仍空則用 email 當備援
            String newName = trimOrNull(incoming.getDisplayName());
            String oldName = trimOrNull(old.getDisplayName());
            String emailForName = trimOrNull(
                    !isEmpty(incoming.getEmail()) ? incoming.getEmail() : old.getEmail()
            );
            String finalName = !isEmpty(newName) ? newName
                    : (!isEmpty(oldName) ? oldName
                    : (emailForName != null ? emailForName : "(未命名)"));
            merged.setDisplayName(finalName);

            // email：新值優先，沒有就舊值
            merged.setEmail(!isEmpty(incoming.getEmail()) ? incoming.getEmail() : old.getEmail());

            // lastOnline：>0 的新值優先
            merged.setLastOnline(incoming.getLastOnline() > 0 ? incoming.getLastOnline() : old.getLastOnline());

            // noteCount：若呼叫方想更新會帶入新值；否則保留舊值
            // 規則：若 incoming 有帶（>=0 視為有效）就用 incoming，否則保留舊的
            merged.setNoteCount(incoming.getNoteCount() >= 0 ? incoming.getNoteCount() : old.getNoteCount());

            data.set(idx, merged);
            notifyItemChanged(idx);
        } else {
            // 新增時也補齊顯示名稱備援
            Friend add = new Friend();
            add.setUid(incoming.getUid());
            String name = trimOrNull(incoming.getDisplayName());
            if (isEmpty(name)) {
                name = trimOrNull(incoming.getEmail());
                if (isEmpty(name)) name = "(未命名)";
            }
            add.setDisplayName(name);
            add.setEmail(incoming.getEmail());
            add.setLastOnline(incoming.getLastOnline());
            add.setNoteCount(Math.max(0, incoming.getNoteCount()));
            data.add(add);
            notifyItemInserted(data.size() - 1);
        }
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_friend, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Friend f = data.get(pos);

        String name = trimOrNull(f.getDisplayName());
        if (isEmpty(name)) {
            String em = trimOrNull(f.getEmail());
            name = !isEmpty(em) ? em : "(未命名)";
        }
        h.tvName.setText(name);

        String last = (f.getLastOnline() > 0)
                ? fmt.format(new Date(f.getLastOnline()))
                : "未知";
        h.tvLastOnline.setText("上線：" + last);

        h.tvNoteCount.setText("筆記數：" + f.getNoteCount());
    }

    @Override public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvLastOnline, tvNoteCount;
        VH(@NonNull View v) {
            super(v);
            tvName = v.findViewById(R.id.tvName);
            tvLastOnline = v.findViewById(R.id.tvLastOnline);
            tvNoteCount = v.findViewById(R.id.tvNoteCount);
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    @Nullable private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
