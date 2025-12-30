package com.ld.ainote.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.Tasks;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import com.ld.ainote.LoginActivity;
import com.ld.ainote.R;
import com.ld.ainote.adapters.FriendAdapter;
import com.ld.ainote.models.Friend;

import java.security.SecureRandom;
import java.util.*;

/**
 * ProfileFragment
 * 只負責「好友管理」：顯示/產生好友代碼、加好友、好友清單、基本帳號資訊。
 * 不顯示任何筆記（自己的或共筆）。
 */
public class ProfileFragment extends Fragment {

    private FirebaseAuth auth;
    private FirebaseUser user;
    private FirebaseFirestore db;

    private TextView tvEmail, tvVerify, tvFriendCode;
    private TextInputEditText etDisplayName, etFriendCode;
    private MaterialButton btnSaveName, btnSendVerify, btnResetPwd, btnLogout, btnAddFriend;

    private FriendAdapter friendAdapter;
    private ListenerRegistration friendReg;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        auth = FirebaseAuth.getInstance();
        user = auth.getCurrentUser();
        db = FirebaseFirestore.getInstance();

        if (user == null) {
            if (getActivity() != null) {
                startActivity(new Intent(getActivity(), LoginActivity.class));
                getActivity().finish();
            }
            return;
        }

        tvEmail       = v.findViewById(R.id.tvEmail);
        tvVerify      = v.findViewById(R.id.tvVerify);
        tvFriendCode  = v.findViewById(R.id.tvFriendCode);
        etDisplayName = v.findViewById(R.id.etDisplayName);
        etFriendCode  = v.findViewById(R.id.etFriendCode);
        btnSaveName   = v.findViewById(R.id.btnSaveName);
        btnSendVerify = v.findViewById(R.id.btnSendVerify);
        btnResetPwd   = v.findViewById(R.id.btnResetPwd);
        btnLogout     = v.findViewById(R.id.btnLogout);
        btnAddFriend  = v.findViewById(R.id.btnAddFriend);

        RecyclerView rvFriends = v.findViewById(R.id.rvFriends);
        rvFriends.setLayoutManager(new LinearLayoutManager(getContext()));
        friendAdapter = new FriendAdapter();
        rvFriends.setAdapter(friendAdapter);

        tvEmail.setText(user.getEmail() != null ? user.getEmail() : "(無信箱)");
        tvVerify.setText("Email 驗證狀態：" + (user.isEmailVerified() ? "已驗證" : "未驗證"));
        if (user.getDisplayName() != null) etDisplayName.setText(user.getDisplayName());

        ensureFriendCode();

        btnSaveName.setOnClickListener(view -> saveDisplayName());

        btnSendVerify.setOnClickListener(view ->
                user.sendEmailVerification().addOnCompleteListener(task ->
                        Toast.makeText(getContext(),
                                task.isSuccessful() ? "驗證信已寄出" :
                                        "寄送失敗：" + (task.getException() != null ? task.getException().getMessage() : ""),
                                Toast.LENGTH_LONG).show()));

        btnResetPwd.setOnClickListener(view -> {
            String email = user.getEmail();
            if (TextUtils.isEmpty(email)) {
                Toast.makeText(getContext(), "此帳號沒有信箱", Toast.LENGTH_SHORT).show();
                return;
            }
            auth.sendPasswordResetEmail(email).addOnCompleteListener(task ->
                    Toast.makeText(getContext(),
                            task.isSuccessful() ? "重設連結已寄出" :
                                    "寄送失敗：" + (task.getException() != null ? task.getException().getMessage() : ""),
                            Toast.LENGTH_LONG).show());
        });

        btnLogout.setOnClickListener(view -> {
            auth.signOut();
            if (getActivity() != null) {
                Intent it = new Intent(getActivity(), LoginActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(it);
            }
        });

        btnAddFriend.setOnClickListener(view -> addFriendByCode());

        listenMyFriends();
    }

    @Override
    public void onResume() {
        super.onResume();
        setPresence(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        setPresence(false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (friendReg != null) {
            friendReg.remove();
            friendReg = null;
        }
    }

    /** 產生或讀取好友代碼（8 碼），顯示到 UI */
    private void ensureFriendCode() {
        DocumentReference me = db.collection("users").document(user.getUid());
        me.get().addOnSuccessListener(snap -> {
            String code = null;
            if (snap.exists()) code = snap.getString("friendCode");
            if (TextUtils.isEmpty(code)) {
                code = genFriendCode();
                Map<String, Object> upd = new HashMap<>();
                upd.put("email", user.getEmail());
                upd.put("displayName", user.getDisplayName());
                upd.put("friendCode", code);
                upd.put("createdAt", FieldValue.serverTimestamp());
                me.set(upd, SetOptions.merge());
            }
            tvFriendCode.setText("我的好友代碼：" + fmtCode(code));
        }).addOnFailureListener(e ->
                tvFriendCode.setText("我的好友代碼：（讀取失敗）")
        );
    }

    /** 產生 8 碼不含易混淆字的代碼 */
    private String genFriendCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** 4-4 顯示 */
    private String fmtCode(String code) {
        if (code == null) return "--------";
        if (code.length() == 8) return code.substring(0, 4) + " " + code.substring(4);
        return code;
    }

    /** 更新顯示名稱（Auth + Firestore） */
    private void saveDisplayName() {
        String name = etDisplayName.getText() != null ? etDisplayName.getText().toString().trim() : "";
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(getContext(), "請輸入顯示名稱", Toast.LENGTH_SHORT).show();
            return;
        }
        UserProfileChangeRequest req = new UserProfileChangeRequest.Builder()
                .setDisplayName(name).build();
        user.updateProfile(req).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Toast.makeText(getContext(), "更新失敗：" +
                                (task.getException() != null ? task.getException().getMessage() : ""),
                        Toast.LENGTH_LONG).show();
                return;
            }

            db.collection("users").document(user.getUid())
                    .set(new HashMap<String, Object>() {{
                        put("displayName", name);
                        put("updatedAt", FieldValue.serverTimestamp());
                    }}, SetOptions.merge())
                    .addOnSuccessListener(unused ->
                            Toast.makeText(getContext(), "已更新顯示名稱", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e ->
                            Toast.makeText(getContext(), "Firestore 同步失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
        });
    }

    /** 以代碼加好友（雙向建立 /users/{uid}/friends/{otherUid}） */
    private void addFriendByCode() {
        String raw = etFriendCode.getText() != null ? etFriendCode.getText().toString() : "";
        String code = raw.replace(" ", "").trim().toUpperCase(); // 容忍使用者輸入空格
        if (TextUtils.isEmpty(code)) {
            Toast.makeText(getContext(), "請輸入好友代碼", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code.length() != 8) {
            Toast.makeText(getContext(), "代碼需為 8 碼", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").whereEqualTo("friendCode", code).limit(1).get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        Toast.makeText(getContext(), "查無此代碼", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    DocumentSnapshot other = qs.getDocuments().get(0);
                    String otherUid = other.getId();
                    if (otherUid.equals(user.getUid())) {
                        Toast.makeText(getContext(), "不能加入自己為好友", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String otherName = other.getString("displayName");
                    String otherEmail = other.getString("email");

                    DocumentReference myFriendDoc = db.collection("users").document(user.getUid())
                            .collection("friends").document(otherUid);
                    DocumentReference otherFriendDoc = db.collection("users").document(otherUid)
                            .collection("friends").document(user.getUid());

                    Map<String, Object> meToOther = new HashMap<>();
                    meToOther.put("uid", otherUid);
                    meToOther.put("displayName", otherName);
                    meToOther.put("email", otherEmail);
                    meToOther.put("addedAt", FieldValue.serverTimestamp());

                    Map<String, Object> otherToMe = new HashMap<>();
                    otherToMe.put("uid", user.getUid());
                    otherToMe.put("displayName", user.getDisplayName());
                    otherToMe.put("email", user.getEmail());
                    otherToMe.put("addedAt", FieldValue.serverTimestamp());

                    WriteBatch batch = db.batch();
                    batch.set(myFriendDoc, meToOther, SetOptions.merge());
                    batch.set(otherFriendDoc, otherToMe, SetOptions.merge());
                    batch.commit().addOnSuccessListener(unused -> {
                        etFriendCode.setText("");
                        Toast.makeText(getContext(), "已加入好友", Toast.LENGTH_SHORT).show();
                    }).addOnFailureListener(e ->
                            Toast.makeText(getContext(), "加入失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .addOnFailureListener(e ->
                        Toast.makeText(getContext(), "查詢失敗：" + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    /** 監聽我的好友清單（不顯示任何筆記列表） */
    private void listenMyFriends() {
        CollectionReference mine = db.collection("users").document(user.getUid()).collection("friends");
        friendReg = mine.addSnapshotListener((qs, e) -> {
            if (e != null || qs == null) return;
            List<Friend> base = new ArrayList<>();
            for (DocumentSnapshot s : qs.getDocuments()) {
                Friend f = new Friend(
                        s.getString("uid"),
                        s.getString("displayName"),
                        s.getString("email")
                );
                base.add(f);
            }
            friendAdapter.setData(base);
            // 額外抓好友資訊（上線/離線、筆記數）做摘要用；不在這裡顯示筆記清單
            for (Friend f : base) {
                if (f.getUid() == null) continue;
                fetchFriendDetails(f.getUid());
            }
        });
    }

    /** 取得好友的狀態/筆記數（摘要資訊） */
    /** 取得好友的狀態/「分享給我的」筆記數（摘要資訊） */
    private void fetchFriendDetails(String friendUid) {
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // 初始 Friend 物件，避免 UI 閃爍或 NullPointer
        Friend f = new Friend();
        f.setUid(friendUid);
        f.setDisplayName("");
        f.setEmail(null);
        f.setLastOnline(0);
        f.setNoteCount(0);
        friendAdapter.upsert(f);

        DocumentReference otherDoc = db.collection("users").document(friendUid);

        // === A. 讀取朋友基本資料 ===
        otherDoc.get()
                .addOnSuccessListener(userSnap -> {
                    String dn = null, em = null;
                    long last = 0L;
                    boolean online = false;

                    if (userSnap.exists()) {
                        dn = userSnap.getString("displayName");
                        em = userSnap.getString("email");
                        com.google.firebase.Timestamp ts = userSnap.getTimestamp("lastOnline");
                        if (ts != null) last = ts.toDate().getTime();
                        Boolean on = userSnap.getBoolean("online");
                        online = on != null && on;
                    }

                    // ✅ 顯示名稱優先順序：displayName → email → ""
                    String display = !TextUtils.isEmpty(dn)
                            ? dn
                            : (em != null ? em : "");

                    Friend cur = new Friend();
                    cur.setUid(friendUid);
                    cur.setDisplayName(display);
                    cur.setEmail(em);
                    cur.setLastOnline(online ? System.currentTimeMillis() : last);
                    cur.setNoteCount(f.getNoteCount()); // 保留舊的筆記數
                    friendAdapter.upsert(cur);
                })
                .addOnFailureListener(e -> {
                    // 不中斷，保留原顯示
                });

        // === B. 統計「朋友分享給我的筆記數」 ===
        // ⚠️ 這裡改成直接查「users/{friendUid}/notes」而不是 collectionGroup
        //    因為 Firestore 規則允許你讀取有你在 collaborators 裡的文件
        Query sharedWithMeCountQ = db.collection("users")
                .document(friendUid)
                .collection("notes")
                .whereArrayContains("collaborators", myUid);

        sharedWithMeCountQ.get()
                .addOnSuccessListener(sharedNotesSnap -> {
                    int sharedCount = (sharedNotesSnap != null) ? sharedNotesSnap.size() : 0;

                    // 用 adapter 取舊值避免覆蓋名稱/狀態
                    Friend existing = friendAdapter.findByUid(friendUid);
                    Friend cur = new Friend();
                    cur.setUid(friendUid);
                    cur.setDisplayName(existing != null ? existing.getDisplayName() : f.getDisplayName());
                    cur.setEmail(existing != null ? existing.getEmail() : f.getEmail());
                    cur.setLastOnline(existing != null ? existing.getLastOnline() : f.getLastOnline());
                    cur.setNoteCount(sharedCount);

                    friendAdapter.upsert(cur);
                })
                .addOnFailureListener(e -> {
                    // 若缺索引或權限問題，Logcat 可檢查 e
                    // Log.e("ProfileFragment", "count shared notes failed", e);
                });
    }

    /** 線上狀態（簡易版） */
    private void setPresence(boolean online) {
        Map<String, Object> upd = new HashMap<>();
        upd.put("online", online);
        if (!online) {
            upd.put("lastOnline", FieldValue.serverTimestamp());
        }
        db.collection("users").document(user.getUid())
                .set(upd, SetOptions.merge());
    }
}
