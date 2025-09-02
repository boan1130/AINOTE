package com.ld.ainote.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.ListenerRegistration;
import com.ld.ainote.NoteEditActivity;
import com.ld.ainote.R;
import com.ld.ainote.adapters.NoteAdapter;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Note;

import java.util.List;

public class NotesFragment extends Fragment {

    private NoteRepository repo;
    private ListenerRegistration registration;
    private NoteAdapter adapter;

    private TextInputEditText etSearch;
    private MaterialSwitch swGroup;
    private RecyclerView rv;
    private TextView tvEmpty;
    private ProgressBar progress;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        etSearch = v.findViewById(R.id.etSearch);
        swGroup = v.findViewById(R.id.swGroup);
        rv = v.findViewById(R.id.rvNotes);
        tvEmpty = v.findViewById(R.id.tvEmpty);
        progress = v.findViewById(R.id.progress);
        MaterialButton btnAdd = v.findViewById(R.id.btnAdd);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new NoteAdapter();
        rv.setAdapter(adapter);

        adapter.setOnItemClickListener(note -> {
            Intent it = new Intent(getContext(), NoteEditActivity.class);
            it.putExtra("note_id", note.getId());
            it.putExtra("owner_id", FirebaseAuth.getInstance().getCurrentUser().getUid());
            startActivity(it);
        });

        // 左滑刪除（跳過 header）
        ItemTouchHelper.SimpleCallback swipe = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) { return false; }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getAdapterPosition(); // 用 getAdapterPosition，避免舊版錯誤
                if (adapter.isHeader(pos)) { adapter.notifyItemChanged(pos); return; }

                Note n = adapter.getItem(pos);
                if (n == null) { adapter.notifyItemChanged(pos); return; }

                // 先從 UI 拿掉
                adapter.removeLocalAt(pos);

                repo.deleteNote(n.getId()).addOnCompleteListener(t -> {
                    if (getView() == null) return;
                    if (t.isSuccessful()) {
                        Snackbar.make(getView(), "已刪除「" + (n.getTitle()==null?"(無標題)":n.getTitle()) + "」", Snackbar.LENGTH_LONG)
                                .setAction("復原", v1 -> repo.addNote(new Note(n.getTitle(), n.getContent()), null))
                                .show();
                    } else {
                        Toast.makeText(requireContext(), "刪除失敗", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };
        new ItemTouchHelper(swipe).attachToRecyclerView(rv);

        // 新增筆記
        btnAdd.setOnClickListener(view -> startActivity(new Intent(getContext(), NoteEditActivity.class)));

        // 搜尋
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setKeyword(s == null ? null : s.toString());
                updateEmpty();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 群組切換
        swGroup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            adapter.setGrouped(isChecked);
            updateEmpty();
        });

        // Firestore 監聽
        repo = new NoteRepository();
        setLoading(true);
        registration = repo.listenNotes(new NoteRepository.NotesListener() {
            @Override
            public void onNotesChanged(List<Note> notes) {
                if (getActivity()==null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    adapter.submitAll(notes);
                    updateEmpty();
                });
            }
            @Override
            public void onError(@Nullable Exception e) {
                if (getActivity()==null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(requireContext(), "同步失敗：" + (e!=null?e.getMessage():""), Toast.LENGTH_LONG).show();
                    updateEmpty();
                });
            }
        });
    }

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
        if (registration != null) {
            registration.remove();
            registration = null;
        }
    }
}
