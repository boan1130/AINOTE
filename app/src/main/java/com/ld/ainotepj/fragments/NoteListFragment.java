package com.ld.ainotepj.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.ld.ainotepj.R;
import com.ld.ainotepj.adapters.UnifiedNoteAdapter;
import com.ld.ainotepj.models.UnifiedNote;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NoteListFragment extends Fragment {

    private RecyclerView recyclerView;
    private UnifiedNoteAdapter adapter;
    private List<UnifiedNote> unifiedNoteList;
    private FirebaseFirestore db;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_note_list, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        unifiedNoteList = new ArrayList<>();
        adapter = new UnifiedNoteAdapter(unifiedNoteList, note -> {
            // 點擊筆記 → 顯示詳情頁（你可以實作 NoteDetailFragment）
            Toast.makeText(getContext(), "點擊：" + note.getTitle(), Toast.LENGTH_SHORT).show();
        });
        recyclerView.setAdapter(adapter);

        db = FirebaseFirestore.getInstance();
        loadNotes();
        loadHighlights();

        FloatingActionButton fab = view.findViewById(R.id.fab_add_note);
        fab.setOnClickListener(v -> {
            // 彈出選單切換新增類型（如你之前設計的）
            Toast.makeText(getContext(), "新增筆記或段落", Toast.LENGTH_SHORT).show();
        });

        return view;
    }

    private void loadNotes() {
        db.collection("notes")
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null) return;
                    for (QueryDocumentSnapshot doc : value) {
                        String title = doc.getString("title");
                        String content = doc.getString("content");
                        Timestamp ts = doc.getTimestamp("createdAt");
                        String timeStr = formatTimestamp(ts);
                        unifiedNoteList.add(new UnifiedNote(title, content, "note", timeStr));
                    }
                    adapter.notifyDataSetChanged();
                });
    }

    private void loadHighlights() {
        db.collection("passages")
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null) return;
                    for (QueryDocumentSnapshot doc : value) {
                        String title = doc.getString("title");
                        String content = doc.getString("content");
                        Timestamp ts = doc.getTimestamp("createdAt");
                        String timeStr = formatTimestamp(ts);
                        unifiedNoteList.add(new UnifiedNote(title, content, "highlight", timeStr));
                    }
                    adapter.notifyDataSetChanged();
                });
    }

    private String formatTimestamp(Timestamp ts) {
        if (ts == null) return "";
        Date date = ts.toDate();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
        return sdf.format(date);
    }
}
