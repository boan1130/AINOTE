package com.ld.ainote.data;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.ld.ainote.models.Friend;

import java.util.*;
import java.util.function.Consumer;

public class FriendRepository {

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Nullable
    private String myUid() {
        FirebaseAuth a = FirebaseAuth.getInstance();
        return (a.getCurrentUser() == null) ? null : a.getCurrentUser().getUid();
    }

    /** 一次性取回好友清單（依 addedAt DESC 排序，若沒這欄就用 documentId 排序退而求其次） */
    public void getFriends(Consumer<List<Friend>> callback) {
        String uid = myUid();
        if (uid == null) { callback.accept(Collections.emptyList()); return; }

        // 先嘗試用 addedAt 排序，沒有的話 Firestore 也能回結果，只是排序可能不完美
        db.collection("users").document(uid).collection("friends")
                .orderBy("addedAt", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(snap -> callback.accept(mapFriends(snap)))
                .addOnFailureListener(e -> callback.accept(Collections.emptyList()));
    }

    /** 即時監聽版（可選）：呼叫端請保存 return 的 ListenerRegistration 並在 onDestroyView() 移除 */
    public ListenerRegistration listenFriends(Consumer<List<Friend>> onChange,
                                              @Nullable Consumer<Exception> onError) {
        String uid = myUid();
        if (uid == null) {
            if (onError != null) onError.accept(new IllegalStateException("尚未登入"));
            // 回傳一個 no-op 的註冊物件，避免呼叫端 NPE
            return () -> {};
        }
        return db.collection("users").document(uid).collection("friends")
                .orderBy("addedAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) {
                        if (onError != null) onError.accept(e);
                        return;
                    }
                    if (snap != null) onChange.accept(mapFriends(snap));
                });
    }

    /** 將 Firestore 文件轉成 Friend，並做好欄位回填與顯示名稱 fallback */
    private List<Friend> mapFriends(QuerySnapshot snap) {
        List<Friend> list = new ArrayList<>();
        for (DocumentSnapshot d : snap.getDocuments()) {
            Friend f = d.toObject(Friend.class);
            if (f == null) f = new Friend();

            // 回填 uid（若文件沒存 uid，就用文件 id）
            if (f.getUid() == null || f.getUid().isEmpty()) {
                f.setUid(d.getId());
            }

            // 顯示名稱：displayName -> email（不要用 UID 當顯示名）
            String dn = f.getDisplayName();
            String em = f.getEmail();
            if (dn == null || dn.trim().isEmpty()) {
                f.setDisplayName(em != null ? em : ""); // 讓 UI 再決定最後的 placeholder
            }

            list.add(f);
        }

        // 如果資料沒有 addedAt，保底用 displayName 排序一次，避免 UI 抖動
        list.sort((a, b) -> {
            String sa = a.getDisplayName() == null ? "" : a.getDisplayName();
            String sb = b.getDisplayName() == null ? "" : b.getDisplayName();
            return sa.compareToIgnoreCase(sb);
        });

        return list;
    }
}
