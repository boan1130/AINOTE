package com.ld.ainote.fragments;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.ld.ainote.R;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Note;
import com.ld.ainote.net.AiService;
import java.util.*;

public class AiFragment extends Fragment {

    private AutoCompleteTextView spNote;
    private ChipGroup chipsTask;
    private Chip chSummary, chQuiz, chIntegrate;
    private LinearLayout rowQuiz;
    private SeekBar seekCount;
    private TextView tvCount;
    private TextInputEditText etResult, etAge;
    private ProgressBar progress;
    private View btnRun, btnSaveAsNote;

    private final List<Note> notes = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private Note selected;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_ai, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        spNote   = v.findViewById(R.id.spNote);
        chipsTask= v.findViewById(R.id.chipsTask);
        chSummary= v.findViewById(R.id.chSummary);
        chQuiz   = v.findViewById(R.id.chQuiz);
        chIntegrate = v.findViewById(R.id.chIntegrate);
        rowQuiz  = v.findViewById(R.id.rowQuiz);
        seekCount= v.findViewById(R.id.seekCount);
        tvCount  = v.findViewById(R.id.tvCount);
        etResult = v.findViewById(R.id.etResult);
        etAge    = v.findViewById(R.id.etAge);
        btnRun   = v.findViewById(R.id.btnRun);
        progress = v.findViewById(R.id.progress);
        btnSaveAsNote = v.findViewById(R.id.btnSaveAsNote);

        chSummary.setChecked(true);

        seekCount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                int n = Math.max(1, p);
                tvCount.setText(n + " 題");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        // 預設題數：3
        seekCount.setProgress(3);
        tvCount.setText("3 題");

        chipsTask.setOnCheckedStateChangeListener((group, ids) -> {
            boolean quiz = chQuiz.isChecked();
            rowQuiz.setVisibility(quiz ? View.VISIBLE : View.GONE);
        });

        // 一次性載入「我的 + 共編」筆記
        NoteRepository repo = new NoteRepository();
        repo.getMyAndSharedOnce(new NoteRepository.NotesOnceListener() {
            @Override public void onLoaded(List<Note> list) {
                notes.clear(); titles.clear();
                notes.addAll(list);
                for (Note n : notes) titles.add(n.getTitle() == null ? "(無標題)" : n.getTitle());
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    ArrayAdapter<String> adp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, titles);
                    spNote.setAdapter(adp);
                    spNote.setOnItemClickListener((parent, view, position, id) -> selected = notes.get(position));
                    if (!notes.isEmpty()) {
                        spNote.setText(titles.get(0), false);
                        selected = notes.get(0);
                    }
                });
            }
            @Override public void onError(Exception e) { toast("載入筆記失敗：" + e.getMessage()); }
        });

        btnRun.setOnClickListener(c -> runTask());
        btnSaveAsNote.setOnClickListener(c -> saveAsNewNote());
    }

    private void runTask() {
        if (selected == null) { toast("請先選擇一份筆記"); return; }
        String task = chSummary.isChecked() ? "summary" : (chQuiz.isChecked() ? "quiz" : "integrate");
        int n = Math.max(1, seekCount.getProgress());
        Integer age = parseAge();
        String text = buildNoteText(selected);

        setLoading(true);
        AiService.ask(task, text, n, age, new AiService.Callback() {
            @Override public void onSuccess(String out) {
                if (getActivity()==null) return;
                getActivity().runOnUiThread(() -> {
                    etResult.setText(out);
                    setLoading(false);
                });
            }
            @Override public void onError(Exception e) {
                if (getActivity()==null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    toast("AI 呼叫失敗：" + e.getMessage());
                });
            }
        });
    }

    /** 存成新筆記：沿用原標題 +（AI 助手），同堆疊 */
    private void saveAsNewNote() {
        if (selected == null) { toast("請先選擇一份筆記"); return; }
        CharSequence cs = etResult.getText();
        String aiText = cs == null ? "" : cs.toString().trim();
        if (aiText.isEmpty()) { toast("沒有可儲存的內容，請先產生結果"); return; }

        String baseTitle = selected.getTitle() == null ? "(無標題)" : selected.getTitle();
        String newTitle = baseTitle + "（AI 助手）";

        Note newNote = new Note(newTitle, aiText);
        newNote.setStack(selected.getStack());

        setLoading(true);
        new NoteRepository().addNote(newNote, task -> {
            if (getActivity()==null) return;
            getActivity().runOnUiThread(() -> {
                setLoading(false);
                if (task.isSuccessful()) toast("已存成新筆記");
                else toast("儲存失敗");
            });
        });
    }

    private Integer parseAge() {
        String s = (etAge.getText()==null) ? "" : etAge.getText().toString().trim();
        try {
            if (s.isEmpty()) return null;
            int v = Integer.parseInt(s);
            return (v >= 3 && v <= 120) ? v : null;
        } catch (Exception e) { return null; }
    }

    private String buildNoteText(Note n) {
        String t = n.getTitle() == null ? "" : n.getTitle();
        String c = n.getContent() == null ? "" : n.getContent();
        return "《標題》" + t + "\n《內容》\n" + c;
    }

    private void setLoading(boolean on){
        progress.setVisibility(on? View.VISIBLE: View.GONE);
        btnRun.setEnabled(!on);
        if (btnSaveAsNote != null) btnSaveAsNote.setEnabled(!on);
    }

    private void toast(String s){
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }
}
