/**
 * Cloud Functions for AINote
 * APIs:
 *  - Notes CRUD: createNote / updateNote / deleteNote / deleteNotesByCategory
 *  - NoteBlocks: listBlocks / createBlock / updateBlock / deleteBlock
 *                acquireBlockLock / renewBlockLock / releaseBlockLock
 *
 * Auth: Firebase ID Token (Authorization: Bearer <token>)
 */
const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

// ====== Config ======
const REGION = "asia-east1";
const ALLOW_ORIGIN = "*"; // TODO: 上線請改成你的前端網域
const LOCK_TTL_SEC = 30;  // 區塊鎖預設 30 秒
const MAX_TAGS = 20;

// ====== CORS / Helpers ======
function setCors(res) {
  res.set("Access-Control-Allow-Origin", ALLOW_ORIGIN);
  res.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
}
function maybeHandlePreflight(req, res) {
  if (req.method === "OPTIONS") {
    setCors(res);
    res.status(204).send("");
    return true;
  }
  return false;
}
function ensurePost(req, res) {
  if (req.method !== "POST") {
    setCors(res);
    res.status(405).json({ error: "Method not allowed" });
    return false;
  }
  return true;
}
async function requireAuth(req, res) {
  const auth = req.headers.authorization || "";
  const m = auth.match(/^Bearer\s+(.+)$/);
  if (!m) {
    setCors(res);
    res.status(401).json({ error: "Missing Bearer token" });
    return null;
  }
  try {
    return await admin.auth().verifyIdToken(m[1]);
  } catch {
    setCors(res);
    res.status(401).json({ error: "Invalid token" });
    return null;
  }
}

// ====== Common: note permission helpers ======
function noteRef(ownerId, noteId) {
  return db.collection("users").doc(ownerId).collection("notes").doc(noteId);
}
function blocksCol(ownerId, noteId) {
  return noteRef(ownerId, noteId).collection("blocks");
}
async function assertCanReadNote({ ownerId, noteId, uid }) {
  const ref = noteRef(ownerId, noteId);
  const snap = await ref.get();
  if (!snap.exists) {
    const err = new Error("Note not found");
    err.code = 404;
    throw err;
  }
  const data = snap.data() || {};
  const ownerField = data.ownerId || data.owner || ownerId;
  const collabs = Array.isArray(data.collaborators) ? data.collaborators : [];
  const permitted = ownerField === uid || collabs.includes(uid);
  if (!permitted) {
    const err = new Error("Permission denied");
    err.code = 403;
    throw err;
  }
  return { ref, data };
}
async function assertCanWriteNote({ targetOwnerId, noteId, callerUid }) {
  // 可寫者必然可讀：沿用上方檢查
  const { ref, data } = await assertCanReadNote({
    ownerId: targetOwnerId,
    noteId,
    uid: callerUid,
  });
  return { ref, data };
}

/** 只把傳入的欄位打 patch（避免 null 覆寫） */
function buildPatchFromBody(body) {
  const { title, content, tags, stack, chapter, section } = body || {};
  const patch = {};
  if (typeof title === "string") patch.title = title.trim();
  if (typeof content === "string") patch.content = content;
  if (typeof stack === "string") patch.stack = stack.trim();
  if (Number.isInteger(chapter)) patch.chapter = chapter;
  if (Number.isInteger(section)) patch.section = section;
  if (Array.isArray(tags)) patch.tags = tags.slice(0, MAX_TAGS);
  patch.updatedAt = admin.firestore.FieldValue.serverTimestamp();
  return patch;
}

function nowTs() { return admin.firestore.Timestamp.now(); }
function tsAfterSeconds(sec) {
  return admin.firestore.Timestamp.fromDate(new Date(Date.now() + sec * 1000));
}
function isLockActive(lockUntil) {
  return !!lockUntil && typeof lockUntil.toMillis === "function" && lockUntil.toMillis() > Date.now();
}

// ===================================================================
// =                           Notes CRUD                             =
// ===================================================================

/**
 * Create
 * body: { title?, content?, tags?, stack?, chapter?, section? }
 * path: /users/{uid}/notes
 */
exports.createNote = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;
  const uid = decoded.uid;

  try {
    const { title, content, tags, stack, chapter, section } = req.body || {};
    if (!title && !content) {
      setCors(res);
      return res.status(400).json({ error: "title or content required" });
    }

    const now = admin.firestore.FieldValue.serverTimestamp();
    const note = {
      title: typeof title === "string" ? title.trim() : "",
      content: typeof content === "string" ? content : "",
      stack: typeof stack === "string" ? stack.trim() : "",
      chapter: Number.isInteger(chapter) ? chapter : null,
      section: Number.isInteger(section) ? section : null,
      tags: Array.isArray(tags) ? tags.slice(0, MAX_TAGS) : [],
      // 兩個欄位都寫入，對齊 App 與 collectionGroup 規則
      ownerId: uid,
      owner: uid,
      collaborators: [],
      timestamp: now, // App 用來排序
      createdAt: now,
      updatedAt: now,
    };

    const ref = await db.collection("users").doc(uid).collection("notes").add(note);
    setCors(res);
    return res.status(200).json({ success: true, id: ref.id });
  } catch (err) {
    console.error("createNote error:", err);
    setCors(res);
    return res.status(500).json({ error: "Internal error" });
  }
});

/**
 * Update
 * body: { id, ownerId? (共筆時指定擁有者), title?, content?, tags?, stack?, chapter?, section? }
 */
exports.updateNote = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { id, ownerId } = req.body || {};
    if (!id) {
      setCors(res);
      return res.status(400).json({ error: "id required" });
    }
    const targetOwnerId = ownerId || decoded.uid;
    const { ref } = await assertCanWriteNote({
      targetOwnerId,
      noteId: id,
      callerUid: decoded.uid,
    });

    const patch = buildPatchFromBody(req.body);
    if (Object.keys(patch).length === 1 && patch.updatedAt) {
      setCors(res);
      return res.json({ success: true, noChange: true });
    }
    await ref.set(patch, { merge: true });

    setCors(res);
    return res.json({ success: true });
  } catch (err) {
    console.error("updateNote error:", err);
    setCors(res);
    if (err.code === 403) return res.status(403).json({ error: "Permission denied" });
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/**
 * Delete single
 * body: { id, ownerId? }
 */
exports.deleteNote = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { id, ownerId } = req.body || {};
    if (!id) {
      setCors(res);
      return res.status(400).json({ error: "id required" });
    }
    const targetOwnerId = ownerId || decoded.uid;
    const { ref } = await assertCanWriteNote({
      targetOwnerId,
      noteId: id,
      callerUid: decoded.uid,
    });
    await ref.delete();

    setCors(res);
    return res.json({ success: true });
  } catch (err) {
    console.error("deleteNote error:", err);
    setCors(res);
    if (err.code === 403) return res.status(403).json({ error: "Permission denied" });
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/**
 * Delete by Category (stack)
 * body: { stack }
 * 只刪呼叫者自己的指定類別
 */
exports.deleteNotesByCategory = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { stack } = req.body || {};
    if (!stack) {
      setCors(res);
      return res.status(400).json({ error: "stack required" });
    }

    const col = db.collection("users").doc(decoded.uid).collection("notes");
    const snap = await col.where("stack", "==", String(stack).trim()).get();

    const batch = db.batch();
    snap.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();

    setCors(res);
    return res.json({ success: true, deleted: snap.size });
  } catch (err) {
    console.error("deleteNotesByCategory error:", err);
    setCors(res);
    return res.status(500).json({ error: "Internal error" });
  }
});

// ===================================================================
// =                        Note Blocks APIs                          =
// =   路徑：/users/{ownerId}/notes/{noteId}/blocks/{blockId}         =
// =   欄位：id,index,type,text,version,updatedBy,updatedAt,          =
// =        lockHolder,lockUntil                                      =
// ===================================================================

/** List blocks
 * body: { ownerId, noteId }
 */
exports.listBlocks = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { ownerId, noteId } = req.body || {};
    if (!ownerId || !noteId) {
      setCors(res);
      return res.status(400).json({ error: "ownerId and noteId required" });
    }
    await assertCanReadNote({ ownerId, noteId, uid: decoded.uid });

    const snap = await blocksCol(ownerId, noteId).orderBy("index").get();
    const blocks = [];
    snap.forEach((d) => {
      const b = d.data();
      blocks.push({ id: d.id, ...b });
    });

    setCors(res);
    return res.json({ success: true, blocks });
  } catch (err) {
    console.error("listBlocks error:", err);
    setCors(res);
    if (err.code === 403) return res.status(403).json({ error: "Permission denied" });
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/** Create block
 * body: { ownerId, noteId, index, type?, text? }
 */
exports.createBlock = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    let { ownerId, noteId, index, type, text } = req.body || {};
    if (!ownerId || !noteId || (typeof index !== "number")) {
      setCors(res);
      return res.status(400).json({ error: "ownerId, noteId, index required" });
    }
    if (index < 0) index = 0;

    await assertCanWriteNote({
      targetOwnerId: ownerId,
      noteId,
      callerUid: decoded.uid,
    });

    // 🆕 取使用者顯示資訊
    const user = await admin.auth().getUser(decoded.uid);
    const displayName = user.displayName || "";
    const email = user.email || "";

    const now = nowTs();
    const doc = {
      index,
      type: typeof type === "string" ? type : "paragraph",
      text: typeof text === "string" ? text : "",
      version: 1,
      updatedBy: decoded.uid,
      updatedByDisplayName: displayName,   // 🆕
      updatedByEmail: email,               // 🆕
      updatedAt: now,
      lockHolder: null,
      lockUntil: null,
    };
    const ref = await blocksCol(ownerId, noteId).add(doc);

    setCors(res);
    return res.json({ success: true, id: ref.id, block: { id: ref.id, ...doc } });
  } catch (err) {
    console.error("createBlock error:", err);
    setCors(res);
    if (err.code === 403) return res.status(403).json({ error: "Permission denied" });
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/** Delete block
 * body: { ownerId, noteId, blockId }
 */
exports.deleteBlock = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { ownerId, noteId, blockId } = req.body || {};
    if (!ownerId || !noteId || !blockId) {
      setCors(res);
      return res.status(400).json({ error: "ownerId, noteId, blockId required" });
    }
    await assertCanWriteNote({
      targetOwnerId: ownerId,
      noteId,
      callerUid: decoded.uid,
    });

    const bRef = blocksCol(ownerId, noteId).doc(blockId);
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(bRef);
      if (!snap.exists) return;
      const b = snap.data() || {};
      const lockedBy = b.lockHolder || null;
      const lockUntil = b.lockUntil || null;
      if (lockedBy && lockedBy !== decoded.uid && isLockActive(lockUntil)) {
        const err = new Error("Block locked by another user");
        err.code = 423;
        throw err;
      }
      tx.delete(bRef);
    });

    setCors(res);
    return res.json({ success: true });
  } catch (err) {
    console.error("deleteBlock error:", err);
    setCors(res);
    if (err.code === 423) return res.status(423).json({ error: "Locked" });
    if (err.code === 403) return res.status(403).json({ error: "Permission denied" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/** Acquire lock
 * body: { ownerId, noteId, blockId }
 * 若未持鎖或已逾期，則設為 caller；若已是 caller，直接續約。
 */
exports.acquireBlockLock = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { ownerId, noteId, blockId } = req.body || {};
    if (!ownerId || !noteId || !blockId) {
      setCors(res);
      return res.status(400).json({ error: "ownerId, noteId, blockId required" });
    }
    await assertCanReadNote({ ownerId, noteId, uid: decoded.uid });

    const bRef = blocksCol(ownerId, noteId).doc(blockId);
    const until = tsAfterSeconds(LOCK_TTL_SEC);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(bRef);
      if (!snap.exists) {
        const err = new Error("Block not found");
        err.code = 404;
        throw err;
      }
      const b = snap.data() || {};
      const lockedBy = b.lockHolder || null;
      const lockUntil = b.lockUntil || null;

      if (!lockedBy || !isLockActive(lockUntil) || lockedBy === decoded.uid) {
        tx.update(bRef, { lockHolder: decoded.uid, lockUntil: until });
      } else {
        const err = new Error("Block locked by another user");
        err.code = 423;
        throw err;
      }
    });

    setCors(res);
    return res.json({
      success: true,
      lockHolder: decoded.uid,
      lockUntil: until.toDate().toISOString(),
    });
  } catch (err) {
    console.error("acquireBlockLock error:", err);
    setCors(res);
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    if (err.code === 423) return res.status(423).json({ error: "Locked" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/** Renew lock
 * body: { ownerId, noteId, blockId }
 * 只有持鎖者能續約
 */
exports.renewBlockLock = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { ownerId, noteId, blockId } = req.body || {};
    if (!ownerId || !noteId || !blockId) {
      setCors(res);
      return res.status(400).json({ error: "ownerId, noteId, blockId required" });
    }
    await assertCanReadNote({ ownerId, noteId, uid: decoded.uid });

    const bRef = blocksCol(ownerId, noteId).doc(blockId);
    const until = tsAfterSeconds(LOCK_TTL_SEC);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(bRef);
      if (!snap.exists) {
        const err = new Error("Block not found");
        err.code = 404;
        throw err;
      }
      const b = snap.data() || {};
      if (b.lockHolder !== decoded.uid) {
        const err = new Error("Not lock holder");
        err.code = 423;
        throw err;
      }
      tx.update(bRef, { lockUntil: until });
    });

    setCors(res);
    return res.json({
      success: true,
      lockHolder: decoded.uid,
      lockUntil: until.toDate().toISOString(),
    });
  } catch (err) {
    console.error("renewBlockLock error:", err);
    setCors(res);
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    if (err.code === 423) return res.status(423).json({ error: "Locked" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/** Release lock
 * body: { ownerId, noteId, blockId }
 * 只有持鎖者能釋放
 */
exports.releaseBlockLock = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { ownerId, noteId, blockId } = req.body || {};
    if (!ownerId || !noteId || !blockId) {
      setCors(res);
      return res.status(400).json({ error: "ownerId, noteId, blockId required" });
    }
    await assertCanReadNote({ ownerId, noteId, uid: decoded.uid });

    const bRef = blocksCol(ownerId, noteId).doc(blockId);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(bRef);
      if (!snap.exists) {
        const err = new Error("Block not found");
        err.code = 404;
        throw err;
      }
      const b = snap.data() || {};
      if (b.lockHolder !== decoded.uid) {
        const err = new Error("Not lock holder");
        err.code = 423;
        throw err;
      }
      tx.update(bRef, { lockHolder: null, lockUntil: null });
    });

    setCors(res);
    return res.json({ success: true });
  } catch (err) {
    console.error("releaseBlockLock error:", err);
    setCors(res);
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    if (err.code === 423) return res.status(423).json({ error: "Locked" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/** Update block (with version & lock checking)
 * body: { ownerId, noteId, blockId, text?, type?, index?, expectedVersion? }
 * 規則：
 *  - 若 block 有鎖且鎖主≠caller 且未過期 → 423
 *  - 若傳入 expectedVersion，且現版號≠它 → 409
 *  - 成功後 version + 1，updatedBy/At 更新
 */
exports.updateBlock = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { ownerId, noteId, blockId, text, type, index, expectedVersion } = req.body || {};
    if (!ownerId || !noteId || !blockId) {
      setCors(res);
      return res.status(400).json({ error: "ownerId, noteId, blockId required" });
    }
    await assertCanWriteNote({
      targetOwnerId: ownerId,
      noteId,
      callerUid: decoded.uid,
    });

    // 🆕 取使用者顯示資訊
    const user = await admin.auth().getUser(decoded.uid);
    const displayName = user.displayName || "";
    const email = user.email || "";

    const bRef = blocksCol(ownerId, noteId).doc(blockId);
    let newVersion = null;
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(bRef);
      if (!snap.exists) {
        const err = new Error("Block not found");
        err.code = 404;
        throw err;
      }
      const b = snap.data() || {};
      const lockedBy = b.lockHolder || null;
      const lockUntil = b.lockUntil || null;
      if (lockedBy && lockedBy !== decoded.uid && isLockActive(lockUntil)) {
        const err = new Error("Block locked by another user");
        err.code = 423;
        throw err;
      }
      if (Number.isInteger(expectedVersion) && b.version !== expectedVersion) {
        const err = new Error("Version conflict");
        err.code = 409;
        throw err;
      }

      const patch = {};
      if (typeof text === "string") patch.text = text;
      if (typeof type === "string") patch.type = type;
      if (Number.isInteger(index)) patch.index = index;
      newVersion = (b.version || 1) + 1;

      patch.version = newVersion;
      patch.updatedBy = decoded.uid;
      patch.updatedByDisplayName = displayName;  // 🆕
      patch.updatedByEmail = email;              // 🆕
      patch.updatedAt = nowTs();

      tx.update(bRef, patch);
    });

    setCors(res);
    return res.json({ success: true, version: newVersion });
  } catch (err) {
    console.error("updateBlock error:", err);
    setCors(res);
    if (err.code === 404) return res.status(404).json({ error: "Not found" });
    if (err.code === 423) return res.status(423).json({ error: "Locked" });
    if (err.code === 409) return res.status(409).json({ error: "Version conflict" });
    return res.status(500).json({ error: "Internal error" });
  }
});

/**
 * Set Collaborators
 * body: { ownerId, noteId (或 id), collaborators: [uid1, uid2, ...] }
 * 僅擁有者可修改
 */
exports.setCollaborators = onRequest({ region: REGION }, async (req, res) => {
  if (maybeHandlePreflight(req, res)) return;
  if (!ensurePost(req, res)) return;
  const decoded = await requireAuth(req, res);
  if (!decoded) return;

  try {
    const { ownerId } = req.body || {};
    const noteId = req.body.noteId || req.body.id;  // ✅ 兼容 Android 傳 noteId 或 id
    const collaborators = Array.isArray(req.body.collaborators)
      ? req.body.collaborators.filter((u) => typeof u === "string")
      : [];

    // 驗證參數
    if (!ownerId || !noteId) {
      setCors(res);
      return res.status(400).json({ error: "ownerId and noteId required" });
    }

    // 僅擁有者可修改共編名單
    if (decoded.uid !== ownerId) {
      setCors(res);
      return res.status(403).json({ error: "Only owner can set collaborators" });
    }

    const noteDoc = noteRef(ownerId, noteId);
    const snap = await noteDoc.get();
    if (!snap.exists) {
      setCors(res);
      return res.status(404).json({ error: "Note not found" });
    }

    const current = snap.data() || {};
    const prevList = Array.isArray(current.collaborators)
      ? current.collaborators
      : [];
    const same = JSON.stringify([...prevList].sort()) === JSON.stringify([...collaborators].sort());
    if (same) {
      setCors(res);
      return res.json({ success: true, noChange: true });
    }

    await noteDoc.set(
      {
        collaborators,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    setCors(res);
    return res.json({ success: true, count: collaborators.length });
  } catch (err) {
    console.error("setCollaborators error:", err);
    setCors(res);
    return res.status(500).json({ error: "Internal error" });
  }
});
