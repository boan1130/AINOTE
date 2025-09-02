package com.ld.ainote.data;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.ld.ainote.models.Note;

import java.util.*;

public class NoteRepository {
    private static final String TAG = "NoteRepository";

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

    public ListenerRegistration listenNotes(final NotesListener listener) {
        if (myNotesRef == null) {
            listener.onError(new IllegalStateException("尚未登入"));
            return () -> {};
        }
        return myNotesRef.orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) {
                        listener.onError(e);
                        return;
                    }
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

                    final Map<String, Note> map = new LinkedHashMap<>();
                    for (DocumentSnapshot doc : taskMy.getResult().getDocuments()) {
                        Note n = doc.toObject(Note.class);
                        if (n == null) continue;
                        n.setId(doc.getId());
                        if (n.getOwnerId() == null) n.setOwnerId(myUid);
                        map.put(n.getOwnerId() + "/" + n.getId(), n);
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
                                            map.put(n.getOwnerId() + "/" + n.getId(), n);
                                        }
                                    }
                                } else {
                                    Exception e = taskShared.getException();
                                    Log.w(TAG, "載入共編筆記失敗(可能缺索引)：", e);
                                }

                                List<Note> out = new ArrayList<>(map.values());
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

    public void addNote(Note note, @Nullable OnCompleteListener<DocumentReference> callback) {
        if (myNotesRef == null) {
            if (callback != null)
                callback.onComplete(Tasks.forException(new IllegalStateException("尚未登入")));
            return;
        }
        if (note.getOwnerId() == null) note.setOwnerId(myUid);
        myNotesRef.add(note)
                .addOnCompleteListener(callback != null ? callback : t -> {});
    }

    public Task<Void> updateNote(Note note) {
        if (myNotesRef == null || note.getId() == null) {
            return Tasks.forException(new IllegalStateException("缺少參數或尚未登入"));
        }
        if (note.getOwnerId() == null) note.setOwnerId(myUid);
        return myNotesRef.document(note.getId()).set(note, SetOptions.merge());
    }

    public Task<Void> updateNote(Note note, String ownerId) {
        if (note.getId() == null || ownerId == null) {
            return Tasks.forException(new IllegalStateException("缺少參數"));
        }
        DocumentReference doc = db.collection("users").document(ownerId)
                .collection("notes").document(note.getId());
        if (note.getOwnerId() == null) note.setOwnerId(ownerId);
        return doc.set(note, SetOptions.merge());
    }

    public Task<Void> deleteNote(String id) {
        if (myNotesRef == null) {
            return Tasks.forException(new IllegalStateException("尚未登入"));
        }
        return myNotesRef.document(id).delete();
    }

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

    public void setCollaborators(String ownerId, String noteId, List<String> uids,
                                 @Nullable OnCompleteListener<Void> cb) {
        Map<String, Object> data = new HashMap<>();
        data.put("collaborators", uids != null ? uids : new ArrayList<>());
        db.collection("users").document(ownerId)
                .collection("notes").document(noteId)
                .set(data, SetOptions.merge())
                .addOnCompleteListener(cb != null ? cb : t -> {});
    }

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

}
