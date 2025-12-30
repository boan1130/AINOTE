package com.ld.ainote.data;

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.ld.ainote.models.NoteBlock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cloud Run 版 BlockRepository（送 blockId / expectedVersion） */
public class BlockRepository {

    private static final String TAG = "BlockRepository";

    // ✅ Cloud Run 完整 URL
    private static final String FN_CREATE_BLOCK = "https://createblock-qoe2g6zspa-de.a.run.app";
    private static final String FN_UPDATE_BLOCK = "https://updateblock-qoe2g6zspa-de.a.run.app";
    private static final String FN_DELETE_BLOCK = "https://deleteblock-qoe2g6zspa-de.a.run.app";
    private static final String FN_LIST_BLOCKS  = "https://listblocks-qoe2g6zspa-de.a.run.app";
    private static final String FN_ACQUIRE_LOCK = "https://acquireblocklock-qoe2g6zspa-de.a.run.app";
    private static final String FN_RENEW_LOCK   = "https://renewblocklock-qoe2g6zspa-de.a.run.app";
    private static final String FN_RELEASE_LOCK = "https://releaseblocklock-qoe2g6zspa-de.a.run.app";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    // ---------- 建立 ----------
    public Task<Void> createBlock(String ownerId, String noteId, int index, String type, String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("ownerId", ownerId);
            body.put("noteId", noteId);
            body.put("index", index);
            body.put("type", type == null ? "text" : type); // 與後端預設一致
            body.put("text", text == null ? "" : text);
            return callFunctionJson(FN_CREATE_BLOCK, body)
                    .onSuccessTask(IO, resp -> Tasks.forResult(null));
        } catch (Exception e) {
            return Tasks.forException(e);
        }
    }

    // ---------- 更新（使用 expectedVersion 做衝突檢查） ----------
    public Task<Void> updateBlock(String ownerId, String noteId, String blockId,
                                  String text, String type, int index, int version) {
        try {
            JSONObject body = new JSONObject();
            body.put("ownerId", ownerId);
            body.put("noteId", noteId);
            // 後端主用 blockId；同送 id 做相容
            body.put("blockId", blockId);
            body.put("id", blockId);

            if (text != null)  body.put("text", text);
            if (type != null)  body.put("type", type);
            if (index >= 0)    body.put("index", index);
            // ✅ 重點：改送 expectedVersion（不要送 version）
            if (version >= 0)  body.put("expectedVersion", version);

            return callFunctionJson(FN_UPDATE_BLOCK, body)
                    .onSuccessTask(IO, resp -> Tasks.forResult(null));
        } catch (Exception e) {
            return Tasks.forException(e);
        }
    }

    // ---------- 刪除 ----------
    public Task<Void> deleteBlock(String ownerId, String noteId, String blockId) {
        try {
            JSONObject body = new JSONObject();
            body.put("ownerId", ownerId);
            body.put("noteId", noteId);
            body.put("blockId", blockId);
            body.put("id", blockId); // 相容
            return callFunctionJson(FN_DELETE_BLOCK, body)
                    .onSuccessTask(IO, resp -> Tasks.forResult(null));
        } catch (Exception e) {
            return Tasks.forException(e);
        }
    }

    // ---------- 取得清單 ----------
    public Task<List<NoteBlock>> listBlocks(String ownerId, String noteId) {
        TaskCompletionSource<List<NoteBlock>> tcs = new TaskCompletionSource<>();
        IO.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("ownerId", ownerId);
                body.put("noteId", noteId);

                String resp = Tasks.await(callFunctionJson(FN_LIST_BLOCKS, body));
                JSONObject js = new JSONObject(resp);
                List<NoteBlock> out = new ArrayList<>();
                JSONArray arr = js.optJSONArray("blocks");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.getJSONObject(i);
                        NoteBlock nb = new NoteBlock();
                        nb.setId(b.optString("id", b.optString("blockId", "")));
                        nb.setIndex(b.optInt("index", i));
                        nb.setType(b.optString("type", "text"));
                        nb.setText(b.optString("text", ""));
                        nb.setVersion(b.optInt("version", 0));

                        // 若你的 NoteBlock 有以下欄位/Setter，可一併帶入（沒有就刪掉這幾行）
                        try {
                            if (b.has("updatedBy")) nb.setUpdatedBy(b.optString("updatedBy", null));
                            if (b.has("lockHolder")) nb.setLockHolder(b.optString("lockHolder", null));
                            long updatedAtMs = parseTimestampMillis(b.opt("updatedAt"));
                            if (updatedAtMs > 0) nb.setUpdatedAt(new Date(updatedAtMs));
                            long lockUntilMs = parseTimestampMillis(b.opt("lockUntil"));
                            if (lockUntilMs > 0) nb.setLockUntil(new Date(lockUntilMs));
                        } catch (Throwable ignore) {}

                        out.add(nb);
                    }
                }
                tcs.setResult(out);
            } catch (Exception e) {
                tcs.setException(e);
            }
        });
        return tcs.getTask();
    }

    // ---------- 鎖定 ----------
    public Task<Void> acquireLock(String ownerId, String noteId, String blockId) {
        try {
            JSONObject body = new JSONObject();
            body.put("ownerId", ownerId);
            body.put("noteId", noteId);
            body.put("blockId", blockId);
            body.put("id", blockId); // 相容
            return callFunctionJson(FN_ACQUIRE_LOCK, body)
                    .onSuccessTask(IO, resp -> Tasks.forResult(null));
        } catch (Exception e) {
            return Tasks.forException(e);
        }
    }

    public Task<Void> renewLock(String ownerId, String noteId, String blockId) {
        try {
            JSONObject body = new JSONObject();
            body.put("ownerId", ownerId);
            body.put("noteId", noteId);
            body.put("blockId", blockId);
            body.put("id", blockId); // 相容
            return callFunctionJson(FN_RENEW_LOCK, body)
                    .onSuccessTask(IO, resp -> Tasks.forResult(null));
        } catch (Exception e) {
            return Tasks.forException(e);
        }
    }

    public Task<Void> releaseLock(String ownerId, String noteId, String blockId) {
        try {
            JSONObject body = new JSONObject();
            body.put("ownerId", ownerId);
            body.put("noteId", noteId);
            body.put("blockId", blockId);
            body.put("id", blockId); // 相容
            return callFunctionJson(FN_RELEASE_LOCK, body)
                    .onSuccessTask(IO, resp -> Tasks.forResult(null));
        } catch (Exception e) {
            return Tasks.forException(e);
        }
    }

    // ================== utilities ==================

    /** 支援 Firestore Timestamp JSON（{_seconds,_nanoseconds}）或 ISO8601 字串；失敗回 0 */
    private static long parseTimestampMillis(Object v) {
        try {
            if (v == null) return 0L;
            if (v instanceof JSONObject) {
                JSONObject o = (JSONObject) v;
                if (o.has("_seconds")) {
                    long sec = o.optLong("_seconds", 0L);
                    long nanos = o.optLong("_nanoseconds", 0L);
                    return sec * 1000L + (nanos / 1_000_000L);
                }
                // 也有可能長這樣 {seconds:123, nanoseconds:456000000}
                if (o.has("seconds")) {
                    long sec = o.optLong("seconds", 0L);
                    long nanos = o.optLong("nanoseconds", 0L);
                    return sec * 1000L + (nanos / 1_000_000L);
                }
            } else if (v instanceof String) {
                // 嘗試解析 ISO 字串（簡易版）
                String s = (String) v;
                // java.util.Date 不直接吃 ISO；用 javax.xml.bind 在舊 Android 會缺
                // 這裡只處理毫秒/秒級 epoch 字串
                try {
                    // 若是數字字串
                    long epoch = Long.parseLong(s.trim());
                    // 假設 >= 1e12 視為毫秒，否則秒
                    return (epoch >= 1_000_000_000_000L) ? epoch : epoch * 1000L;
                } catch (NumberFormatException ignore) {
                    // 放棄 ISO 解析，回 0
                }
            }
        } catch (Throwable ignore) {}
        return 0L;
    }

    // ================== HTTP helper ==================
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

                String json = body != null ? body.toString() : "{}";
                String resp = postJson(fullUrl, idToken, json);
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

            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + ": " + resp);
            }
            return resp;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
