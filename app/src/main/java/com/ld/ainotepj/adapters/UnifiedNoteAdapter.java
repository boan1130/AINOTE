package com.ld.ainotepj.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.ld.ainotepj.R;
import com.ld.ainotepj.models.UnifiedNote;
import java.util.List;

public class UnifiedNoteAdapter extends RecyclerView.Adapter<UnifiedNoteAdapter.NoteViewHolder> {

    private List<UnifiedNote> noteList;
    private OnNoteClickListener listener;

    public interface OnNoteClickListener {
        void onNoteClick(UnifiedNote note);
    }

    public UnifiedNoteAdapter(List<UnifiedNote> noteList, OnNoteClickListener listener) {
        this.noteList = noteList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public NoteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_unified_note, parent, false);
        return new NoteViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull NoteViewHolder holder, int position) {
        UnifiedNote note = noteList.get(position);
        holder.title.setText(note.getTitle());
        holder.content.setText(note.getContent());
        holder.timestamp.setText(note.getTimestamp());
        holder.type.setText(note.getType().equals("note") ? "一般筆記" : "段落註記");
        holder.itemView.setOnClickListener(v -> listener.onNoteClick(note));
    }

    @Override
    public int getItemCount() {
        return noteList.size();
    }

    static class NoteViewHolder extends RecyclerView.ViewHolder {
        TextView title, content, timestamp;
        Chip type;

        public NoteViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.noteTitle);
            content = itemView.findViewById(R.id.noteContent);
            timestamp = itemView.findViewById(R.id.noteTimestamp);
            type = itemView.findViewById(R.id.noteType);
        }
    }
}