package com.ld.ainote.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.ld.ainote.R;
import com.ld.ainote.models.Friend;
import java.text.SimpleDateFormat;
import java.util.*;

public class FriendAdapter extends RecyclerView.Adapter<FriendAdapter.VH> {

    private final List<Friend> data = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    public void setData(List<Friend> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void upsert(Friend f) {
        int idx = -1;
        for (int i = 0; i < data.size(); i++) {
            if (Objects.equals(data.get(i).getUid(), f.getUid())) { idx = i; break; }
        }
        if (idx >= 0) {
            data.set(idx, f);
            notifyItemChanged(idx);
        } else {
            data.add(f);
            notifyItemInserted(data.size() - 1);
        }
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Friend f = data.get(pos);
        h.tvName.setText(f.getDisplayName() != null && !f.getDisplayName().isEmpty() ? f.getDisplayName() : "(未命名)");
        String last = (f.getLastOnline() > 0) ? fmt.format(new Date(f.getLastOnline())) : "未知";
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
}
