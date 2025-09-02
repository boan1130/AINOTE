package com.ld.ainote.data;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.ld.ainote.models.Friend;
import java.util.*;
import java.util.function.Consumer;

public class FriendRepository {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

    public void getFriends(Consumer<List<Friend>> callback) {
        db.collection("users").document(uid).collection("friends")
                .get().addOnSuccessListener(snap -> {
                    List<Friend> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Friend f = d.toObject(Friend.class);
                        if (f != null) list.add(f);
                    }
                    callback.accept(list);
                }).addOnFailureListener(e -> callback.accept(Collections.emptyList()));
    }
}
