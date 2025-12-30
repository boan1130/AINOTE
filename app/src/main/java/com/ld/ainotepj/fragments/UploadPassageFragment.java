package com.ld.ainotepj.fragments;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.firebase.firestore.FirebaseFirestore;
import com.ld.ainotepj.R;
import com.ld.ainotepj.models.Annotation;

public class UploadPassageFragment extends Fragment {

    private EditText inputTitle, inputPassage;
    private TextView displayText;
    private FirebaseFirestore db;
    private String uploadedText = "";
    private SpannableString spannable;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_upload_passage, container, false);

        inputTitle = view.findViewById(R.id.inputTitle);
        inputPassage = view.findViewById(R.id.inputPassage);
        displayText = view.findViewById(R.id.displayText);
        db = FirebaseFirestore.getInstance();

        view.findViewById(R.id.uploadButton).setOnClickListener(v -> {
            String title = inputTitle.getText().toString().trim();
            uploadedText = inputPassage.getText().toString().trim();

            if (title.isEmpty() || uploadedText.isEmpty()) {
                Toast.makeText(getContext(), "請輸入標題與內容", Toast.LENGTH_SHORT).show();
                return;
            }

            // 顯示內容
            spannable = new SpannableString(uploadedText);
            displayText.setText(spannable);
            displayText.setTextIsSelectable(true);
            displayText.setCustomSelectionActionModeCallback(getSelectionCallback());

            // 儲存文章（可選）
            db.collection("passages").add(new com.ld.ainotepj.models.Passage(title, uploadedText));
            Toast.makeText(getContext(), "文章已儲存，可開始選取註記", Toast.LENGTH_SHORT).show();
        });

        return view;
    }

    private ActionMode.Callback getSelectionCallback() {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add("加註記").setOnMenuItemClickListener(item -> {
                    int start = displayText.getSelectionStart();
                    int end = displayText.getSelectionEnd();

                    if (start >= end || uploadedText.isEmpty()) {
                        Toast.makeText(getContext(), "請選取文字", Toast.LENGTH_SHORT).show();
                        return true;
                    }

                    String selectedText = uploadedText.substring(start, end);
                    showNoteDialog(selectedText, start, end);
                    mode.finish();
                    return true;
                });
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode mode) {}
        };
    }

    private void showNoteDialog(String selectedText, int start, int end) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("對選取文字加註記");

        final EditText input = new EditText(getContext());
        input.setHint("輸入註記內容");
        builder.setView(input);

        builder.setPositiveButton("儲存", (dialog, which) -> {
            String note = input.getText().toString();
            Annotation annotation = new Annotation(note, start, end, "#FFFF00");

            // 儲存 Firestore
            db.collection("annotations").add(annotation);

            // 套用高亮顯示
            spannable.setSpan(
                    new BackgroundColorSpan(Color.YELLOW),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            displayText.setText(spannable);
            Toast.makeText(getContext(), "已標註並儲存註記", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());
        builder.show();
    }
}
