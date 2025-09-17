package com.ld.ainote.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.ld.ainote.R;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Note;
import com.ld.ainote.net.AiService;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.firestore.DocumentSnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AiFragment
 * - 勾選章節 → AI 出題 / 整理 / 融會貫通
 * - 交卷後：把錯題（含未作答）寫入「雲端 Firestore + 本機 SharedPreferences」
 * - 錯誤複習：依目前勾選的類別/章節 tags 篩選錯題，隨機抽題作答
 * - 本版：章節「一律全部顯示（我的＋共筆）」；預設展開，可點類別標題收合
 */
public class AiFragment extends Fragment {

    private static final String TAG = "AiQuizParser";
    private static final int QUIZ_COUNT_MAX = 20;

    // ===== SharedPreferences（本機備援） =====
    private static final String SP_NAME = "aiquiz_prefs";
    private static final String KEY_WRONG_BANK = "wrong_bank_v1";

    // ===== 分類/章節選取 =====
    private LinearLayout containerCategories;
    private final List<Note> notes = new ArrayList<>();
    private final Map<String, List<Note>> byCategory = new LinkedHashMap<>();
    private final List<String> categories = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, List<ItemRef>> itemsByCategory = new LinkedHashMap<>();

    // ===== 任務 & 控制 =====
    private ChipGroup chipsTask;
    private Chip chSummary, chQuiz, chIntegrate, chReviewWrong;
    private LinearLayout rowQuiz;
    private SeekBar seekCount;
    private TextView tvCount;
    private TextInputEditText etResult, etAge;
    private ProgressBar progress;
    private View btnRun, btnSaveAsNote;

    // ===== 測驗 UI =====
    private LinearLayout quizHost;
    private LinearLayout quizList;
    private View btnSubmitQuiz;

    // ===== 題庫（當前畫面顯示） =====
    private final List<Question> currentQuiz = new ArrayList<>();

    // 本次 AI 出題時的章節 tags（供交卷寫入錯題庫）
    private List<String> currentContextTags = new ArrayList<>();

    // ================== 小型資料結構 ==================
    private static class ItemRef {
        CheckBox cb; Note note;
        ItemRef(CheckBox cb, Note note){ this.cb = cb; this.note = note; }
    }

    private static class Question {
        String stem;
        String[] options = new String[4]; // A..D
        int correct = -1;                 // 0..3
        int chosen  = -1;                 // 使用者選擇
        RadioGroup group;                 // UI ref
        TextView   tvStem;                // for marking
        int number = -1;                  // 題號（1-based）
    }

    /** 存到雲端/本機用的精簡版結構（含 tags） */
    private static class StoredQuestion {
        String stem;
        String[] opts = new String[4];
        int correct;
        List<String> tags = new ArrayList<>(); // 例如 ["國文", "國文|1-1"]

        String signature() {
            return (stem==null?"":stem) + "\u0001"
                    + (opts[0]==null?"":opts[0]) + "\u0001"
                    + (opts[1]==null?"":opts[1]) + "\u0001"
                    + (opts[2]==null?"":opts[2]) + "\u0001"
                    + (opts[3]==null?"":opts[3]);
        }

        String docId() { return Integer.toHexString(signature().hashCode()); }

        static StoredQuestion from(Question q){
            StoredQuestion s = new StoredQuestion();
            s.stem = q.stem == null ? "" : q.stem;
            for (int i=0;i<4;i++) s.opts[i] = q.options[i] == null ? "" : q.options[i];
            s.correct = q.correct;
            return s;
        }

        Question toQuestion(int number){
            Question q = new Question();
            q.number = number;
            q.stem = stem;
            for (int i=0;i<4;i++) q.options[i] = opts[i];
            q.correct = correct;
            return q;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("stem", stem);
            JSONArray arr = new JSONArray();
            for (int i=0;i<4;i++) arr.put(opts[i]);
            o.put("opts", arr);
            o.put("correct", correct);
            JSONArray t = new JSONArray();
            for (String tag : tags) t.put(tag);
            o.put("tags", t);
            return o;
        }

        static StoredQuestion fromJson(JSONObject o){
            StoredQuestion s = new StoredQuestion();
            try {
                s.stem = o.optString("stem", "");
                JSONArray arr = o.optJSONArray("opts");
                for (int i=0;i<4;i++){
                    String v = "";
                    if (arr != null && i < arr.length()) {
                        Object val = arr.opt(i);
                        v = (val == null) ? "" : String.valueOf(val);
                    }
                    s.opts[i] = v;
                }
                s.correct = o.has("correct") ? o.optInt("correct", -1) : -1;

                s.tags = new ArrayList<>();
                JSONArray t = o.optJSONArray("tags");
                if (t != null) {
                    for (int i=0;i<t.length();i++) {
                        String tag = t.optString(i, "");
                        if (tag != null && !tag.isEmpty()) s.tags.add(tag);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "StoredQuestion.fromJson: malformed object, defaulting", e);
                if (s.stem == null) s.stem = "";
                for (int i=0;i<4;i++) if (s.opts[i] == null) s.opts[i] = "";
                if (s.tags == null) s.tags = new ArrayList<>();
                if (s.correct < 0 || s.correct > 3) s.correct = -1;
            }
            return s;
        }
    }

    // ================== 生命週期 ==================
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_ai, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        // 綁定 View
        containerCategories = v.findViewById(R.id.containerCategories);
        chipsTask           = v.findViewById(R.id.chipsTask);
        chSummary           = v.findViewById(R.id.chSummary);
        chQuiz              = v.findViewById(R.id.chQuiz);
        chIntegrate         = v.findViewById(R.id.chIntegrate);
        chReviewWrong       = v.findViewById(R.id.chReviewWrong);
        rowQuiz             = v.findViewById(R.id.rowQuiz);
        seekCount           = v.findViewById(R.id.seekCount);
        tvCount             = v.findViewById(R.id.tvCount);
        etResult            = v.findViewById(R.id.etResult);
        etAge               = v.findViewById(R.id.etAge);
        progress            = v.findViewById(R.id.progress);
        btnRun              = v.findViewById(R.id.btnRun);
        btnSaveAsNote       = v.findViewById(R.id.btnSaveAsNote);

        quizHost      = v.findViewById(R.id.quizHost);
        quizList      = v.findViewById(R.id.quizList);
        btnSubmitQuiz = v.findViewById(R.id.btnSubmitQuiz);

        // 預設
        chSummary.setChecked(true);

        // 題數 SeekBar（1~20，預設 20 題）
        seekCount.setMax(QUIZ_COUNT_MAX);
        seekCount.setProgress(QUIZ_COUNT_MAX);
        tvCount.setText(QUIZ_COUNT_MAX + " 題");
        seekCount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fu) {
                int n = Math.max(1, p);
                tvCount.setText(n + " 題");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 任務切換：出題/錯誤複習 → 顯示測驗 UI；其他 → 純文字結果
        chipsTask.setOnCheckedStateChangeListener((group, ids) -> {
            boolean quizMode = chQuiz.isChecked() || chReviewWrong.isChecked();
            rowQuiz.setVisibility(quizMode ? View.VISIBLE : View.GONE);
            quizHost.setVisibility(quizMode ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.tilResult).setVisibility(quizMode ? View.GONE : View.VISIBLE);
            if (quizMode) {
                seekCount.setProgress(QUIZ_COUNT_MAX);
                tvCount.setText(QUIZ_COUNT_MAX + " 題");
                clearQuizUI();
            }
        });

        // 載入（我的 + 共編）筆記 → 一律顯示（不做範圍切換）
        NoteRepository repo = new NoteRepository();
        repo.getMyAndSharedOnce(new NoteRepository.NotesOnceListener() {
            @Override public void onLoaded(List<Note> list) {
                notes.clear();
                if (list != null) notes.addAll(list);
                buildCategoryMap();
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> renderAllCategories());
            }
            @Override public void onError(Exception e) { toast("載入筆記失敗：" + e.getMessage()); }
        });

        btnRun.setOnClickListener(c -> runTask());
        btnSaveAsNote.setOnClickListener(c -> saveAsNewNote());
        btnSubmitQuiz.setOnClickListener(c -> submitQuiz());
    }

    // ================== 任務執行 ==================

    private void runTask() {
        List<Note> picked = getAllCheckedNotes();
        if (picked.isEmpty()) { toast("請至少勾選一個章節"); return; }

        int n = Math.min(QUIZ_COUNT_MAX, Math.max(1, seekCount.getProgress()));
        Integer age = parseAge();

        // 錯誤複習：不呼叫 AI，從錯題庫抽題
        if (chReviewWrong.isChecked()) {
            List<String> wanted = buildSelectedTags(picked);
            Set<String> wantedSet = new LinkedHashSet<>(wanted);

            fetchWrongFromCloud(new FetchCb() {
                @Override public void onOk(List<StoredQuestion> cloudList) {
                    List<StoredQuestion> base = cloudList;
                    if (base.isEmpty()) base = loadWrongBank(); // 雲端沒取到 → 本機備援
                    if (base.isEmpty()) { toast("目前沒有可複習的錯題"); return; }

                    List<StoredQuestion> filtered = filterByTags(base, wantedSet);
                    if (filtered.isEmpty()) {
                        toast("此範圍尚無錯題，已改從全部錯題抽題");
                        filtered = base;
                    }
                    List<StoredQuestion> pickedSq = pickFromBank(filtered, n);
                    showStoredQuestions(pickedSq);
                }
                @Override public void onErr(Exception e) {
                    Log.e(TAG, "fetchWrongFromCloud error", e);
                    List<StoredQuestion> base = loadWrongBank();
                    if (base.isEmpty()) { toast("目前沒有可複習的錯題"); return; }
                    List<StoredQuestion> filtered = filterByTags(base, wantedSet);
                    if (filtered.isEmpty()) {
                        toast("此範圍尚無錯題，已改從全部錯題抽題");
                        filtered = base;
                    }
                    List<StoredQuestion> pickedSq = pickFromBank(filtered, n);
                    showStoredQuestions(pickedSq);
                }
            });
            return;
        }

        // 其他任務（summary / quiz / integrate）
        String task = chSummary.isChecked() ? "summary" : (chQuiz.isChecked() ? "quiz" : "integrate");

        // 記住這次 AI 出題所對應的章節 tags（供交卷時把錯題標記進去）
        currentContextTags = chQuiz.isChecked() ? buildSelectedTags(picked) : new ArrayList<>();

        String text = buildCombinedNoteText(picked);
        if (chQuiz.isChecked()) {
            text = buildQuizRules(n) + "\n\n" + text;
        }

        setLoading(true);
        AiService.ask(task, text, n, age, new AiService.Callback() {
            @Override public void onSuccess(String out) {
                if (getActivity()==null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);

                    Log.d(TAG, "AI output length=" + (out==null?0:out.length())
                            + ", preview=\n" + truncateForLog(out, 1200));

                    if (chQuiz.isChecked()) {
                        if (parseQuiz(out, currentQuiz)) {
                            renderQuizUI(currentQuiz);
                            quizHost.setVisibility(View.VISIBLE);
                            requireView().findViewById(R.id.tilResult).setVisibility(View.GONE);
                        } else {
                            etResult.setText(out);
                            quizHost.setVisibility(View.GONE);
                            requireView().findViewById(R.id.tilResult).setVisibility(View.VISIBLE);
                            toast("格式略有偏差，已以文字方式顯示");
                            Log.e(TAG, "parseQuiz=false; fallback to text. Raw (truncated):\n"
                                    + truncateForLog(out, 4000));
                        }
                    } else {
                        etResult.setText(out);
                        quizHost.setVisibility(View.GONE);
                        requireView().findViewById(R.id.tilResult).setVisibility(View.VISIBLE);
                    }
                });
            }
            @Override public void onError(Exception e) {
                if (getActivity()==null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    toast("AI 呼叫失敗：" + e.getMessage());
                    Log.e(TAG, "AiService.ask error", e);
                });
            }
        });
    }

    // ================== Quiz 規則（請 AI 用 Q1:） ==================

    private String buildQuizRules(int count) {
        return "【出題規則】\n"
                + "1) 依照使用者勾選的內容，出 " + count + " 題『單選選擇題』。\n"
                + "2) 每題 4 個選項（A、B、C、D），且只有 1 個正確答案。\n"
                + "3) 題目請使用題號格式：Q1:、Q2:、Q3: ...（題號後加冒號），接著換行列出 A.~D. 選項。\n"
                + "4) 盡量考理解/應用，不要只考死背；難度依年齡自動調整；題目涵蓋不同章節重點、避免重複。\n"
                + "【輸出格式（範例）】\n"
                + "Q1: 題目敘述\n"
                + "A. 選項A\n"
                + "B. 選項B\n"
                + "C. 選項C\n"
                + "D. 選項D\n"
                + "...\n"
                + "【答案】\n"
                + "1. C\n"
                + "2. A\n"
                + "3. D\n"
                + "...\n";
    }

    // ================== Quiz 解析 ==================

    /** 同時支援「Q1:」與「1. / 1)」題號；答案區支援「1. C / 1) C / Q1: C」。 */
    private boolean parseQuiz(String raw, List<Question> out) {
        try {
            Log.d(TAG, "parseQuiz: start, rawLen=" + (raw == null ? 0 : raw.length()));
            out.clear();
            if (raw == null) return false;

            // 標準化空白
            String norm = raw.replace('\u00A0',' ').replace('\u3000',' ');

            // 分成 題目區 / 答案區（答案區開頭獨立行「【答案】」）
            String[] parts = norm.split("(?m)^\\s*【答案】\\s*$");
            String qPart = parts[0];
            String aPart = (parts.length > 1) ? parts[1] : "";

            Log.d(TAG, "qPartLen=" + qPart.length() + ", aPartLen=" + aPart.length());
            Log.d(TAG, "qPart preview:\n" + truncateForLog(qPart, 800));
            Log.d(TAG, "aPart preview:\n" + truncateForLog(aPart, 400));

            // 解析答案：允許「1. C」「1) C」「Q1: C」「Q1 C」
            Map<Integer, Integer> answerMap = new HashMap<>();
            Pattern ansPat = Pattern.compile("^\\s*(?:Q\\s*)?(\\d+)\\s*[\\.|\\):：]??\\s*([A-Da-d])\\s*$", Pattern.MULTILINE);
            Matcher ma = ansPat.matcher(aPart);
            while (ma.find()) {
                int idx = safeInt(ma.group(1));
                char letter = Character.toUpperCase(ma.group(2).charAt(0));
                if (idx > 0 && letter >= 'A' && letter <= 'D') {
                    answerMap.put(idx, letter - 'A');
                    Log.d(TAG, "ans: #" + idx + " -> " + letter);
                }
            }
            if (answerMap.isEmpty()) Log.w(TAG, "answerMap is empty");

            // 以題號切題：支援 Qn: 或 n. / n)（保留題首）
            Pattern qStart = Pattern.compile("(?m)^(?=\\s*(?:Q\\s*)?\\d+\\s*[\\.:：\\)])");
            String[] blocks = qStart.split(qPart);
            int parsed = 0;

            // 標頭（題號 + 題幹）正則
            Pattern headPat = Pattern.compile("^\\s*(?:Q\\s*)?(\\d+)\\s*[\\.:：\\)]\\s*(.*)$", Pattern.MULTILINE);

            for (String blk : blocks) {
                String b = blk.trim();
                if (b.isEmpty()) continue;

                Matcher mh = headPat.matcher(b);
                if (!mh.find()) {
                    Log.w(TAG, "skip block (no header): " + truncateForLog(b, 200));
                    continue;
                }
                int number = safeInt(mh.group(1));
                int headerEnd = mh.end();
                String afterHeader = b.substring(headerEnd).trim();

                // 題幹
                Matcher mApos = Pattern.compile("(?m)^\\s*A[\\.|\\)．、]\\s").matcher(afterHeader);
                String stem;
                if (mApos.find()) {
                    stem = (mh.group(2) + "\n" + afterHeader.substring(0, mApos.start())).trim();
                } else {
                    stem = (mh.group(2) + "\n" + afterHeader).trim();
                }
                stem = stem.replaceAll("^[\\r\\n\\s]+|[\\r\\n\\s]+$", "");
                Log.d(TAG, "#" + number + " stem len=" + stem.length());

                // 取 A..D
                List<String> opts = new ArrayList<>(4);
                for (char c='A'; c<='D'; c++) {
                    Matcher mo = Pattern.compile("(?m)^\\s*" + c + "[\\.|\\)．、]\\s*(.*)$").matcher(b);
                    if (mo.find()) {
                        String opt = mo.group(1).trim();
                        opts.add(opt);
                        Log.d(TAG, "#" + number + " opt " + c + ": " + truncateForLog(opt, 120));
                    } else {
                        Log.w(TAG, "#" + number + " missing option " + c);
                    }
                }
                if (opts.size() != 4) {
                    Log.w(TAG, "#" + number + " options!=4, got=" + opts.size() + " block=\n" + truncateForLog(b, 400));
                    continue;
                }

                Question q = new Question();
                q.number = number;
                q.stem = stem.isEmpty() ? ("第 " + number + " 題") : stem;
                for (int i=0;i<4;i++) q.options[i] = opts.get(i);
                if (answerMap.containsKey(number)) q.correct = answerMap.get(number);
                else Log.w(TAG, "#" + number + " has no answer");

                out.add(q);
                parsed++;
            }

            if (parsed == 0) {
                Log.e(TAG, "no question parsed");
                return false;
            }

            out.sort(Comparator.comparingInt(q -> q.number));
            Log.d(TAG, "parseQuiz success, parsed=" + parsed);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "parseQuiz exception", e);
            return false;
        }
    }

    // ================== Quiz 呈現 / 批改 ==================

    private void clearQuizUI() {
        currentQuiz.clear();
        quizList.removeAllViews();
    }

    private void renderQuizUI(List<Question> quiz) {
        quizList.removeAllViews();
        int pad = dp(8);
        for (int i = 0; i < quiz.size(); i++) {
            Question q = quiz.get(i);

            TextView tv = new TextView(requireContext());
            tv.setText((i+1) + ". " + q.stem);
            tv.setTextSize(16f);
            tv.setPadding(pad, pad, pad, pad);
            quizList.addView(tv);
            q.tvStem = tv;

            RadioGroup rg = new RadioGroup(requireContext());
            rg.setOrientation(RadioGroup.VERTICAL);
            rg.setPadding(pad, 0, pad, pad);

            for (int j = 0; j < 4; j++) {
                RadioButton rb = new RadioButton(requireContext());
                rb.setText((char)('A'+j) + ". " + q.options[j]);
                int finalJ = j;
                rb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) q.chosen = finalJ;
                });
                rg.addView(rb);
            }
            quizList.addView(rg);
            q.group = rg;
        }
    }

    private void submitQuiz() {
        if (currentQuiz.isEmpty()) { toast("目前沒有題目可提交"); return; }

        int correct = 0;
        int unanswered = 0;

        // 清色
        for (Question q : currentQuiz) {
            if (q.tvStem != null) q.tvStem.setTextColor(0xFF000000);
            if (q.group != null) {
                for (int i = 0; i < q.group.getChildCount(); i++) {
                    View child = q.group.getChildAt(i);
                    if (child instanceof RadioButton) {
                        ((RadioButton) child).setTextColor(0xFF000000);
                    }
                }
            }
        }

        // 收集本次答錯（含未作答）
        List<Question> wrongThisRound = new ArrayList<>();

        for (Question q : currentQuiz) {
            if (q.chosen == -1) {
                unanswered++;
                wrongThisRound.add(q);
            } else if (q.chosen == q.correct) {
                correct++;
            } else {
                wrongThisRound.add(q);
            }
            if (q.group != null) {
                for (int i = 0; i < q.group.getChildCount(); i++) {
                    View child = q.group.getChildAt(i);
                    if (!(child instanceof RadioButton)) continue;
                    RadioButton rb = (RadioButton) child;
                    if (i == q.correct) {
                        rb.setTextColor(0xFF2E7D32); // 綠
                    } else if (i == q.chosen && q.chosen != q.correct) {
                        rb.setTextColor(0xFFC62828); // 紅
                    }
                }
            }
        }

        // 寫入錯題庫（雲端 + 本機）
        if (!wrongThisRound.isEmpty()) {
            writeWrongToCloudWithTags(wrongThisRound, currentContextTags);
            appendToWrongBankWithTags(wrongThisRound, currentContextTags);
        }

        int total = currentQuiz.size();
        String msg = "得分：" + correct + " / " + total;
        if (unanswered > 0) msg += "（未作答 " + unanswered + " 題）";

        new AlertDialog.Builder(requireContext())
                .setTitle("測驗結果")
                .setMessage(msg)
                .setPositiveButton("確定", null)
                .show();

        Log.d(TAG, "submitQuiz: score=" + correct + "/" + total + ", unanswered=" + unanswered
                + ", wrongAdded=" + wrongThisRound.size());
    }

    // ================== 類別/章節 UI ==================

    private void buildCategoryMap() {
        byCategory.clear();
        categories.clear();
        itemsByCategory.clear();
        expanded.clear();

        for (Note n : notes) {
            String key = normalizeStack(n.getStack());
            byCategory.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
        }
        categories.addAll(byCategory.keySet());
        Collections.sort(categories, String::compareToIgnoreCase);

        for (List<Note> group : byCategory.values()) {
            group.sort((a,b) -> {
                int c = Integer.compare(a.getChapter(), b.getChapter());
                if (c != 0) return c;
                return Integer.compare(a.getSection(), b.getSection());
            });
        }
    }

    /** 一律全部顯示：每個類別預設展開（expanded 加入所有類別） */
    private void renderAllCategories() {
        containerCategories.removeAllViews();
        itemsByCategory.clear();

        int pad12 = dp(8);
        int pad8  = dp(8);

        for (String cat : categories) {
            List<Note> group = byCategory.get(cat);
            if (group == null) continue;

            // ❌ 原本這行會預設展開，改為不要加入
            // expanded.add(cat);

            // 類別標題（可點擊收合）
            TextView header = new TextView(requireContext());
            header.setText(buildHeaderTitle(cat, group.size(), false)); // ← 預設收合
            header.setTextSize(14f);
            header.setTextColor(0xFF000000);
            header.setBackgroundColor(0xFFFFFFFF);
            header.setPadding(pad12, pad12, pad12, pad12);

            LinearLayout.LayoutParams lpHeader = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpHeader.topMargin = dp(6);
            header.setLayoutParams(lpHeader);

            LinearLayout secContainer = new LinearLayout(requireContext());
            secContainer.setOrientation(LinearLayout.VERTICAL);
            secContainer.setPadding(pad8, pad8, pad8, pad8);
            secContainer.setVisibility(View.GONE); // ← 預設收合

            List<ItemRef> itemRefs = new ArrayList<>();
            for (Note n : group) {
                CheckBox cb = new CheckBox(requireContext());
                String prefix = buildIndexPrefix(n.getChapter(), n.getSection());
                String title  = n.getTitle() == null ? "(無標題)" : n.getTitle();
                cb.setText(prefix.isEmpty() ? title : (prefix + " " + title));
                cb.setChecked(true); // 預設勾選
                cb.setPadding(pad8, dp(6), pad8, dp(6));
                secContainer.addView(cb);
                itemRefs.add(new ItemRef(cb, n));
            }
            itemsByCategory.put(cat, itemRefs);

            header.setOnClickListener(v -> {
                boolean nowExpanded = secContainer.getVisibility() != View.VISIBLE;
                secContainer.setVisibility(nowExpanded ? View.VISIBLE : View.GONE);
                if (nowExpanded) expanded.add(cat); else expanded.remove(cat);
                header.setText(buildHeaderTitle(cat, group.size(), nowExpanded));
            });

            containerCategories.addView(header);
            containerCategories.addView(secContainer);
        }

        if (categories.isEmpty()) {
            TextView tv = new TextView(requireContext());
            tv.setText("尚未有任何筆記可選擇");
            tv.setPadding(pad12, pad12, pad12, pad12);
            containerCategories.addView(tv);
        }
    }

    private String buildHeaderTitle(String cat, int count, boolean expanded) {
        return cat + "（" + count + "）" + (expanded ? " ▲" : " ▼");
    }

    private List<Note> getAllCheckedNotes() {
        List<Note> picked = new ArrayList<>();
        for (Map.Entry<String, List<ItemRef>> e : itemsByCategory.entrySet()) {
            for (ItemRef ref : e.getValue()) {
                if (ref.cb.isChecked()) picked.add(ref.note);
            }
        }
        picked.sort((a,b) -> {
            int s = safeCmp(normalizeStack(a.getStack()), normalizeStack(b.getStack()));
            if (s != 0) return s;
            int c = Integer.compare(a.getChapter(), b.getChapter());
            if (c != 0) return c;
            return Integer.compare(a.getSection(), b.getSection());
        });
        return picked;
    }

    // ================== 其他工具 ==================

    private Integer parseAge() {
        String s = (etAge.getText()==null) ? "" : etAge.getText().toString().trim();
        try {
            if (s.isEmpty()) return null;
            int v = Integer.parseInt(s);
            return (v >= 3 && v <= 120) ? v : null;
        } catch (Exception e) { return null; }
    }

    private String buildCombinedNoteText(List<Note> list) {
        StringBuilder sb = new StringBuilder();
        String lastCat = null;
        for (Note n : list) {
            String cat = normalizeStack(n.getStack());
            if (!Objects.equals(lastCat, cat)) {
                sb.append("【大類別】").append(cat).append('\n');
                lastCat = cat;
            }
            String prefix = buildIndexPrefix(n.getChapter(), n.getSection());
            String t = n.getTitle() == null ? "" : n.getTitle();
            String c = n.getContent() == null ? "" : n.getContent();
            sb.append("《章節》").append(prefix.isEmpty()?t:(prefix + " " + t)).append('\n');
            sb.append("《內容》\n").append(c).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 把目前勾選的 notes 轉成一組 tags（類別級、章節級） */
    private List<String> buildSelectedTags(List<Note> picked) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (Note n : picked) {
            String stack = normalizeStack(n.getStack()); // 例如 國文
            int ch = n.getChapter();
            int sec = n.getSection();
            if (stack != null && !stack.isEmpty()) set.add(stack); // 類別級
            if (ch > 0 && sec > 0) set.add(stack + "|" + ch + "-" + sec); // 章節級
            else if (ch > 0) set.add(stack + "|" + ch); // 只有章
        }
        return new ArrayList<>(set);
    }

    private static String buildIndexPrefix(int chapter, int section) {
        if (chapter > 0 && section > 0) return chapter + "-" + section;
        if (chapter > 0) return String.valueOf(chapter);
        return "";
    }

    private static String normalizeStack(String s) {
        if (s == null) return "(未分組)";
        String t = s.trim();
        return t.isEmpty() ? "(未分組)" : t;
    }

    private static int safeCmp(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareToIgnoreCase(b);
    }

    private int dp(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }

    private void setLoading(boolean on){
        progress.setVisibility(on ? View.VISIBLE : View.GONE);

        if (btnRun != null)        btnRun.setEnabled(!on);
        if (btnSaveAsNote != null) btnSaveAsNote.setEnabled(!on);
        if (btnSubmitQuiz != null) btnSubmitQuiz.setEnabled(!on);
    }

    private void toast(String s){
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }

    private String truncateForLog(String s, int maxChars) {
        if (s == null) return "null";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n...[truncated " + (s.length() - maxChars) + " chars]";
    }

    /** 儲存 AI 純文字結果為新筆記（非測驗作答結果） */
    private void saveAsNewNote() {
        CharSequence cs = etResult.getText();
        String aiText = cs == null ? "" : cs.toString().trim();
        if (aiText.isEmpty()) { toast("沒有可儲存的內容，請先產生結果"); return; }

        Note newNote = new Note("（AI 助手）", aiText);
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

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    // ================== Firestore helpers ==================

    private FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }

    @Nullable
    private String uid() {
        FirebaseAuth a = FirebaseAuth.getInstance();
        return (a.getCurrentUser()==null) ? null : a.getCurrentUser().getUid();
    }

    private DocumentReference wrongDoc(String signatureDocId) {
        String u = uid();
        if (u == null) throw new IllegalStateException("No logged in user");
        return db().collection("users").document(u)
                .collection("wrong_bank").document(signatureDocId);
    }

    private CollectionReference wrongCol() {
        String u = uid();
        if (u == null) throw new IllegalStateException("No logged in user");
        return db().collection("users").document(u)
                .collection("wrong_bank");
    }

    // ================== 錯題庫：雲端寫入/讀取 + 本機備援 ==================

    /** 雲端：寫入錯題並附帶 tags；同題 merge、times_wrong 自增、tags 使用 arrayUnion 合併 */
    private void writeWrongToCloudWithTags(List<Question> wrong, @Nullable List<String> tags) {
        String u = uid();
        if (u == null || wrong == null || wrong.isEmpty()) return;

        WriteBatch batch = db().batch();
        Timestamp now = Timestamp.now();

        for (Question q : wrong) {
            StoredQuestion sq = StoredQuestion.from(q);
            if (sq.correct < 0 || sq.correct > 3) continue;

            Map<String, Object> data = new HashMap<>();
            data.put("stem", sq.stem);
            data.put("opts", Arrays.asList(sq.opts[0], sq.opts[1], sq.opts[2], sq.opts[3]));
            data.put("correct", sq.correct);
            data.put("times_wrong", FieldValue.increment(1));
            data.put("lastWrongAt", now);
            data.put("addedAt", FieldValue.serverTimestamp());
            if (tags != null && !tags.isEmpty()) {
                data.put("tags", FieldValue.arrayUnion(tags.toArray(new String[0])));
            }

            batch.set(wrongDoc(sq.docId()), data, SetOptions.merge());
        }

        batch.commit()
                .addOnSuccessListener(v -> Log.d(TAG, "writeWrongToCloud: committed with tags=" + tags))
                .addOnFailureListener(e -> Log.e(TAG, "writeWrongToCloud: error", e));
    }

    /** 從雲端載入錯題（按 lastWrongAt DESC 取最多 500 題；之後在 client 端用 tags 篩選） */
    private void fetchWrongFromCloud(FetchCb cb) {
        String u = uid();
        if (u == null) { cb.onOk(Collections.emptyList()); return; }

        wrongCol().orderBy("lastWrongAt", Query.Direction.DESCENDING)
                .limit(500)
                .get()
                .addOnSuccessListener(snap -> {
                    List<StoredQuestion> out = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        try {
                            StoredQuestion s = new StoredQuestion();
                            s.stem = d.getString("stem");
                            List<?> arr = (List<?>) d.get("opts");
                            for (int i=0;i<4;i++) s.opts[i] = (arr!=null && i<arr.size()) ? String.valueOf(arr.get(i)) : "";
                            Number corr = (Number) d.get("correct");
                            s.correct = corr==null ? -1 : corr.intValue();
                            List<?> tg = (List<?>) d.get("tags");
                            if (tg != null) for (Object t : tg) s.tags.add(String.valueOf(t));
                            if (s.correct >= 0 && s.correct <= 3) out.add(s);
                        } catch (Exception ignore) {}
                    }
                    cb.onOk(out);
                })
                .addOnFailureListener(cb::onErr);
    }

    private interface FetchCb {
        void onOk(List<StoredQuestion> list);
        void onErr(Exception e);
    }

    // ================== 本機錯題庫：讀寫/合併/篩選 ==================

    private JSONArray readJsonArraySafely(String raw) {
        try {
            if (raw == null) return new JSONArray();
            String s = raw.trim();
            if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
            if (!s.startsWith("[")) return new JSONArray();
            return new JSONArray(s);
        } catch (Exception e) {
            Log.e(TAG, "readJsonArraySafely: invalid JSON, fallback to []", e);
            return new JSONArray();
        }
    }

    private List<StoredQuestion> loadWrongBank(){
        String raw;
        try { raw = sp().getString(KEY_WRONG_BANK, "[]"); }
        catch (Exception e) { Log.e(TAG, "SharedPreferences getString error", e); raw = "[]"; }

        List<StoredQuestion> out = new ArrayList<>();
        JSONArray arr = readJsonArraySafely(raw);

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                StoredQuestion sq = StoredQuestion.fromJson(o);
                if (sq.correct >= 0 && sq.correct <= 3) out.add(sq);
            } catch (Exception e) {
                Log.w(TAG, "loadWrongBank: skip bad item @"+i, e);
            }
        }
        return out;
    }

    private void saveWrongBank(List<StoredQuestion> list){
        try {
            JSONArray arr = new JSONArray();
            if (list != null) {
                for (StoredQuestion s : list) {
                    try { arr.put(s.toJson()); }
                    catch (Exception inner) { Log.w(TAG, "saveWrongBank: skip bad item", inner); }
                }
            }
            sp().edit().putString(KEY_WRONG_BANK, arr.toString()).apply();
        } catch (Exception e){
            Log.e(TAG, "saveWrongBank error", e);
            sp().edit().putString(KEY_WRONG_BANK, "[]").apply();
        }
    }

    private void appendToWrongBankWithTags(List<Question> wrong, @Nullable List<String> tags){
        if (wrong == null || wrong.isEmpty()) return;
        List<StoredQuestion> bank = loadWrongBank();

        HashMap<String, Integer> idx = new HashMap<>();
        for (int i=0;i<bank.size();i++) idx.put(bank.get(i).signature(), i);

        int added = 0, merged = 0;
        for (Question q : wrong) {
            StoredQuestion s = StoredQuestion.from(q);
            if (s.correct < 0 || s.correct > 3) continue;
            if (tags != null && !tags.isEmpty()) s.tags.addAll(tags);

            String sig = s.signature();
            if (idx.containsKey(sig)) {
                StoredQuestion old = bank.get(idx.get(sig));
                LinkedHashSet<String> set = new LinkedHashSet<>(old.tags);
                set.addAll(s.tags);
                old.tags = new ArrayList<>(set);
                merged++;
            } else {
                bank.add(s);
                idx.put(sig, bank.size()-1);
                added++;
            }
        }
        saveWrongBank(bank);
        Log.d(TAG, "appendToWrongBankWithTags added=" + added + ", merged=" + merged + ", total=" + bank.size());
    }

    private List<StoredQuestion> pickFromBank(List<StoredQuestion> bank, int n){
        if (bank.isEmpty()) return Collections.emptyList();
        ArrayList<StoredQuestion> tmp = new ArrayList<>(bank);
        Collections.shuffle(tmp, new Random());
        if (n >= tmp.size()) return tmp;
        return new ArrayList<>(tmp.subList(0, n));
    }

    private List<StoredQuestion> filterByTags(List<StoredQuestion> list, Set<String> wantedTags){
        if (wantedTags == null || wantedTags.isEmpty()) return list;
        List<StoredQuestion> out = new ArrayList<>();
        for (StoredQuestion s : list) {
            if (s.tags == null || s.tags.isEmpty()) continue;
            for (String t : s.tags) {
                if (wantedTags.contains(t)) { out.add(s); break; }
            }
        }
        return out;
    }

    private void showStoredQuestions(List<StoredQuestion> pickedSq) {
        currentQuiz.clear();
        int num = 1;
        for (StoredQuestion sq : pickedSq) currentQuiz.add(sq.toQuestion(num++));
        renderQuizUI(currentQuiz);
        quizHost.setVisibility(View.VISIBLE);
        requireView().findViewById(R.id.tilResult).setVisibility(View.GONE);
    }

    private SharedPreferences sp(){
        return requireContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }
}
