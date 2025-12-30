package com.ld.ainotepj.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.firebase.firestore.FirebaseFirestore;
import com.ld.ainotepj.R;
import com.ld.ainotepj.models.Note;

public class AddNoteFragment extends Fragment {

    private EditText titleEditText, contentEditText;
    private Button saveButton;
    private FirebaseFirestore db;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_add_note, container, false);

        titleEditText = view.findViewById(R.id.titleEditText);
        contentEditText = view.findViewById(R.id.contentEditText);
        saveButton = view.findViewById(R.id.saveButton);
        db = FirebaseFirestore.getInstance();

        saveButton.setOnClickListener(v -> {
            String title = titleEditText.getText().toString().trim();
            String content = contentEditText.getText().toString().trim();

            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(content)) {
                Toast.makeText(getContext(), "標題或內容不能為空", Toast.LENGTH_SHORT).show();
                return;
            }

            Note note = new Note(title, content);
            db.collection("notes")
                    .add(note)
                    .addOnSuccessListener(documentReference -> {
                        Toast.makeText(getContext(), "筆記已儲存", Toast.LENGTH_SHORT).show();
                        // 回到筆記列表
                        requireActivity().getSupportFragmentManager()
                                .beginTransaction()
                                .replace(R.id.fragment_container, new NoteListFragment())
                                .commit();
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(getContext(), "儲存失敗：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        });

        return view;
    }
}
