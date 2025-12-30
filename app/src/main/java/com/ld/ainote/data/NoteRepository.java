package com.ld.ainote.data;

import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.ld.ainote.models.Note;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NoteRepository {
    private static final String TAG = "NoteRepository";

    // ✅ Cloud Run 完整 URL（不使用 FUNCTION_BASE_URL）
    private static final String FN_CREATE            = "https://createnote-qoe2g6zspa-de.a.run.app";
    private static final String FN_UPDATE            = "https://updatenote-qoe2g6zspa-de.a.run.app";
    private static final String FN_DELETE            = "https://deletenote-qoe2g6zspa-de.a.run.app";
    private static final String FN_DELETE_BY_STACK   = "https://deletenotesbycategory-qoe2g6zspa-de.a.run.app";
    private static final String FN_SET_COLLABORATORS = "https://setcollaborators-qoe2g6zspa-de.a.run.app";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final FirebaseFirestore db;
    private final String myUid;
    private final CollectionReference myNotesRef;

    public interface NotesOnceListener {
        void onLoaded(List<Note> list);
        void onError(Exception e);
    }

    public interface NotesListener {
        void onNotesChanged(List<Note> notes);
        void onError(@Nullable Exception e);
    }

    public NoteRepository() {
        db = FirebaseFirestore.getInstance();
        FirebaseAuth auth = FirebaseAuth.getInstance();
        myUid = (auth.getCurrentUser() != null) ? auth.getCurrentUser().getUid() : null;
        myNotesRef = (myUid != null)
                ? db.collection("users").document(myUid).collection("notes")
                : null;
    }

    // ================== 監聽 ==================
    public ListenerRegistration listenNotes(final NotesListener listener) {
        if (myNotesRef == null) {
            listener.onError(new IllegalStateException("尚未登入"));
            return () -> {};
        }
        return myNotesRef.orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) { listener.onError(e); return; }
                    List<Note> list = new ArrayList<>();
                    if (snap != null) {
                        for (DocumentSnapshot doc : snap.getDocuments()) {
                            Note n = doc.toObject(Note.class);
                            if (n == null) continue;
                            n.setId(doc.getId());
                            if (n.getOwnerId() == null) n.setOwnerId(myUid);
                            list.add(n);
                        }
                    }
                    listener.onNotesChanged(list);
                });
    }

    // ================== 讀取「我的 + 共筆」一次性 ==================
    public void getMyAndSharedOnce(final NotesOnceListener cb) {
        if (myUid == null || myNotesRef == null) {
            cb.onError(new IllegalStateException("尚未登入"));
            return;
        }

        myNotesRef.orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnCompleteListener(taskMy -> {
                    if (!taskMy.isSuccessful()) {
                        cb.onError(taskMy.getException() != null
                                ? taskMy.getException()
                                : new Exception("讀取我的筆記失敗"));
                        return;
                    }

                    final Map<String, Note> merged = new LinkedHashMap<>();

                    for (DocumentSnapshot doc : taskMy.getResult().getDocuments()) {
                        Note n = doc.toObject(Note.class);
                        if (n == null) continue;
                        n.setId(doc.getId());
                        if (n.getOwnerId() == null) n.setOwnerId(myUid);
                        merged.put(n.getOwnerId() + "/" + n.getId(), n);
                    }

                    db.collectionGroup("notes")
                            .whereArrayContains("collaborators", myUid)
                            .get()
                            .addOnCompleteListener(taskShared -> {
                                if (taskShared.isSuccessful()) {
                                    for (DocumentSnapshot doc : taskShared.getResult().getDocuments()) {
                                        Note n = doc.toObject(Note.class);
                                        if (n == null) continue;

                                        DocumentReference ref = doc.getReference();
                                        DocumentReference userDoc = ref.getParent().getParent();
                                        String ownerId = userDoc != null ? userDoc.getId() : n.getOwnerId();

                                        n.setId(doc.getId());
                                        if (n.getOwnerId() == null) n.setOwnerId(ownerId);

                                        if (!myUid.equals(n.getOwnerId())) {
                                            merged.put(n.getOwnerId() + "/" + n.getId(), n);
                                        }
                                    }
                                } else {
                                    Exception e = taskShared.getException();
                                    Log.w(TAG, "載入共編筆記失敗(可能缺索引或規則)：", e);
                                }

                                List<Note> out = new ArrayList<>(merged.values());
                                out.sort((a, b) -> {
                                    Date ta = a.getTimestamp();
                                    Date tb = b.getTimestamp();
                                    if (ta == null && tb == null) return 0;
                                    if (ta == null) return 1;
                                    if (tb == null) return -1;
                                    return Long.compare(tb.getTime(), ta.getTime());
                                });

                                cb.onLoaded(out);
                            });
                });
    }

    // ================== 單筆讀取 ==================
    public void getNoteById(String ownerId, String noteId,
                            final java.util.function.Consumer<Note> onOk,
                            final java.util.function.Consumer<Exception> onErr) {
        db.collection("users").document(ownerId)
                .collection("notes").document(noteId)
                .get()
                .addOnSuccessListener(doc -> {
                    Note n = doc.toObject(Note.class);
                    if (n != null) {
                        n.setId(doc.getId());
                        if (n.getOwnerId() == null) n.setOwnerId(ownerId);
                    }
                    onOk.accept(n);
                })
                .addOnFailureListener(onErr::accept);
    }

    // ================== 取得協作者 ==================
    public void getCollaborators(String ownerId, String noteId,
                                 final java.util.function.Consumer<List<String>> onOk) {
        getCollaborators(ownerId, noteId, onOk, e -> {});
    }

    public void getCollaborators(String ownerId, String noteId,
                                 final java.util.function.Consumer<List<String>> onOk,
                                 final java.util.function.Consumer<Exception> onErr) {
        db.collection("users").document(ownerId)
                .collection("notes").document(noteId)
                .get()
                .addOnSuccessListener(doc -> {
                    List<String> list = new ArrayList<>();
                    if (doc.exists()) {
                        Object v = doc.get("collaborators");
                        if (v instanceof List) {
                            for (Object o : (List<?>) v) {
                                if (o != null) list.add(o.toString());
                            }
                        }
                    }
                    onOk.accept(list);
                })
                .addOnFailureListener(onErr::accept);
    }

    // ================== 設定共編者 ==================
    public void setCollaborators(String ownerId, String noteId, List<String> uids,
                                 @Nullable OnCompleteListener<Void> callback) {
        if (TextUtils.isEmpty(ownerId) || TextUtils.isEmpty(noteId)) {
            if (callback != null) {
                callback.onComplete(Tasks.forException(
                        new IllegalArgumentException("ownerId 或 noteId 不可為空")));
            }
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("ownerId", ownerId);
            // 同送兩個 key，確保後端不論吃哪個都可用
            body.put("noteId", noteId);
            body.put("id", noteId);
            JSONArray arr = new JSONArray();
            if (uids != null) for (String u : uids) arr.put(u);
            body.put("collaborators", arr);
        } catch (Exception ignore) {}

        Task<Void> apiTask = callFunctionJson(FN_SET_COLLABORATORS, body)
                .onSuccessTask(IO, resp -> {
                    try {
                        JSONObject js = new JSONObject(resp);
                        if (js.optBoolean("success") || js.optBoolean("noChange")) {
                            return Tasks.forResult(null);
                        }
                        return Tasks.forException(new Exception(
                                "API setCollaborators failed: " + resp));
                    } catch (Exception e) {
                        return Tasks.forException(e);
                    }
                });

        apiTask.addOnCompleteListener(t -> {
            if (t.isSuccessful()) {
                if (callback != null) callback.onComplete(Tasks.forResult(null));
            } else {
                // 🔁 Fallback：用 Firestore 直接 merge 寫 collaborators
                Map<String, Object> data = new HashMap<>();
                data.put("collaborators", uids != null ? uids : new ArrayList<>());
                FirebaseFirestore.getInstance()
                        .collection("users").document(ownerId)
                        .collection("notes").document(noteId)
                        .set(data, SetOptions.merge())
                        .addOnCompleteListener(callback != null ? callback : r -> {});
            }
        });
    }

    // ================== 新增 / 更新 / 刪除 ==================
    public void addNote(Note note, @Nullable OnCompleteListener<DocumentReference> callback) {
        if (myUid == null) {
            if (callback != null)
                callback.onComplete(Tasks.forException(new IllegalStateException("尚未登入")));
            return;
        }

        String title = note.getTitle() != null ? note.getTitle().trim() : "";
        String content = note.getContent() != null ? note.getContent().trim() : "";
        if (title.isEmpty() && content.isEmpty()) {
            String stack = note.getStack() != null ? note.getStack().trim() : "";
            int chap = note.getChapter();
            int sec = note.getSection();
            if (!stack.isEmpty() && chap > 0 && sec > 0)
                title = stack + " " + chap + "-" + sec;
            else if (chap > 0 && sec > 0)
                title = chap + "-" + sec;
            else
                title = "未命名筆記";
        }

        JSONObject body = new JSONObject();
        try {
            body.put("title", title);
            body.put("content", content);
            if (note.getStack() != null) body.put("stack", note.getStack().trim());
            if (note.getChapter() > 0) body.put("chapter", note.getChapter());
            if (note.getSection() > 0) body.put("section", note.getSection());
        } catch (Exception ignore) {}

        Task<DocumentReference> task = callFunctionJson(FN_CREATE, body)
                .onSuccessTask(IO, resp -> {
                    try {
                        JSONObject js = new JSONObject(resp);
                        if (!js.optBoolean("success"))
                            return Tasks.forException(new Exception("API createNote failed: " + resp));

                        String id = js.optString("id", null);
                        if (id == null || id.isEmpty())
                            return Tasks.forException(new Exception("API createNote missing id"));

                        DocumentReference ref = db.collection("users")
                                .document(myUid)
                                .collection("notes")
                                .document(id);
                        return Tasks.forResult(ref);
                    } catch (Exception e) {
                        return Tasks.forException(e);
                    }
                });

        if (callback != null) task.addOnCompleteListener(callback);
    }

    public Task<Void> updateNote(Note note, String ownerId) {
        if (note == null || note.getId() == null || ownerId == null)
            return Tasks.forException(new IllegalStateException("缺少參數"));
        return updateNoteInternal(note, ownerId);
    }

    private Task<Void> updateNoteInternal(Note note, @Nullable String ownerId) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", note.getId());
            if (ownerId != null) body.put("ownerId", ownerId);
            if (note.getTitle() != null) body.put("title", note.getTitle().trim());
            if (note.getContent() != null) body.put("content", note.getContent());
            if (note.getStack() != null) body.put("stack", note.getStack().trim());
            if (note.getChapter() > 0) body.put("chapter", note.getChapter());
            if (note.getSection() > 0) body.put("section", note.getSection());
        } catch (Exception ignore) {}

        return callFunctionJson(FN_UPDATE, body).onSuccessTask(IO, resp -> {
            try {
                JSONObject js = new JSONObject(resp);
                if (js.optBoolean("success") || js.optBoolean("noChange"))
                    return Tasks.forResult(null);
                return Tasks.forException(new Exception("API updateNote failed: " + resp));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public Task<Void> deleteNote(String id) {
        if (myUid == null)
            return Tasks.forException(new IllegalStateException("尚未登入"));
        if (id == null || id.isEmpty())
            return Tasks.forException(new IllegalArgumentException("id is required"));

        JSONObject body = new JSONObject();
        try { body.put("id", id); } catch (Exception ignore) {}

        return callFunctionJson(FN_DELETE, body).onSuccessTask(IO, resp -> {
            try {
                JSONObject js = new JSONObject(resp);
                if (js.optBoolean("success")) return Tasks.forResult(null);
                return Tasks.forException(new Exception("API deleteNote failed: " + resp));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    public Task<Integer> deleteNotesByCategory(String stack) {
        if (myUid == null)
            return Tasks.forException(new IllegalStateException("尚未登入"));
        if (stack == null || stack.trim().isEmpty())
            return Tasks.forException(new IllegalArgumentException("stack is required"));

        JSONObject body = new JSONObject();
        try { body.put("stack", stack.trim()); } catch (Exception ignore) {}

        return callFunctionJson(FN_DELETE_BY_STACK, body).onSuccessTask(IO, resp -> {
            try {
                JSONObject js = new JSONObject(resp);
                if (js.optBoolean("success"))
                    return Tasks.forResult(js.optInt("deleted", 0));
                return Tasks.forException(new Exception("API deleteNotesByCategory failed: " + resp));
            } catch (Exception e) {
                return Tasks.forException(e);
            }
        });
    }

    // ================== HTTP helpers ==================
    private Task<String> callFunctionJson(String fullUrl, JSONObject body) {
        TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        IO.execute(() -> {
            try {
                FirebaseAuth auth = FirebaseAuth.getInstance();
                if (auth.getCurrentUser() == null) {
                    tcs.setException(new IllegalStateException("尚未登入"));
                    return;
                }
                String idToken = Tasks.await(auth.getCurrentUser().getIdToken(true)).getToken();
                if (idToken == null || idToken.isEmpty()) {
                    tcs.setException(new IllegalStateException("取得 ID Token 失敗"));
                    return;
                }

                if (!fullUrl.startsWith("http")) {
                    tcs.setException(new IllegalArgumentException("無效的 URL：" + fullUrl));
                    return;
                }

                String resp = postJson(fullUrl, idToken, body != null ? body.toString() : "{}");
                tcs.setResult(resp);
            } catch (Exception e) {
                Log.e(TAG, "callFunctionJson error", e);
                tcs.setException(e);
            }
        });
        return tcs.getTask();
    }

    private String postJson(String urlStr, String idToken, String json) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + idToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = new BufferedOutputStream(conn.getOutputStream())) {
                os.write(data);
                os.flush();
            }

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String resp = sb.toString();
            if (code < 200 || code >= 300)
                throw new Exception("HTTP " + code + ": " + resp);
            return resp;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
