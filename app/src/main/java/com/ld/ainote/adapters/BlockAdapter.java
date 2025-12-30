/*package com.ld.ainote.adapters;

import android.text.Editable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.ld.ainote.R;
import com.ld.ainote.models.NoteBlock;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class BlockAdapter extends ListAdapter<NoteBlock, RecyclerView.ViewHolder> {

    // ===== 事件介面 =====
    public interface Listener {
        void onAcquireLock(@NonNull NoteBlock block);
        void onSave(@NonNull NoteBlock block, @NonNull String newText, long currentVersion);
        void onReleaseLock(@NonNull NoteBlock block);
        void onAddAfter(@NonNull NoteBlock block); // 加號新增 block
        void onDelete(@NonNull NoteBlock block);   // 新增刪除功能
    }

    private static final int VT_TEXT = 1;

    private final Listener listener;
    private String myUid = null;

    public BlockAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setMyUid(String uid) {
        this.myUid = uid;
        notifyDataSetChanged();
    }


    public void updateLockClock() {
        notifyItemRangeChanged(0, getItemCount(), "clock");
    }

    // ===== DiffUtil =====
    private static final DiffUtil.ItemCallback<NoteBlock> DIFF = new DiffUtil.ItemCallback<NoteBlock>() {
        @Override public boolean areItemsTheSame(@NonNull NoteBlock a, @NonNull NoteBlock b) {
            return Objects.equals(a.getId(), b.getId());
        }
        @Override public boolean areContentsTheSame(@NonNull NoteBlock a, @NonNull NoteBlock b) {
            return a.getVersion() == b.getVersion()
                    && Objects.equals(a.getText(), b.getText())
                    && Objects.equals(a.getType(), b.getType())
                    && Objects.equals(a.getLockHolder(), b.getLockHolder())
                    && Objects.equals(a.getUpdatedBy(), b.getUpdatedBy())
                    && Objects.equals(a.getUpdatedAt(), b.getUpdatedAt())
                    && Objects.equals(a.getLockUntil(), b.getLockUntil())
                    && a.getIndex() == b.getIndex();
        }
        @Override public Object getChangePayload(@NonNull NoteBlock oldItem, @NonNull NoteBlock newItem) {
            return null;
        }
    };

    @Override public long getItemId(int position) {
        NoteBlock b = getItem(position);
        return b.getId() == null ? position : b.getId().hashCode();
    }

    @Override public int getItemViewType(int position) {
        NoteBlock b = getItem(position);
        if ("text".equalsIgnoreCase(s(b.getType()))) return VT_TEXT;
        return VT_TEXT;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        View v = inf.inflate(R.layout.item_block_text, parent, false);
        v.setTag(listener);
        return new TextVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        onBindViewHolder(h, pos, new ArrayList<>());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos, @NonNull List<Object> payloads) {
        NoteBlock b = getItem(pos);
        boolean iHold = isLockedByMe(b);
        boolean expired = lockExpired(b);

        if (h instanceof TextVH) {
            TextVH tvh = (TextVH) h;
            if (payloads.contains("clock")) {
                tvh.bindClockOnly(b, iHold, expired);
            } else {
                tvh.bind(b, iHold, expired);
            }
        }
    }

    private boolean isLockedByMe(NoteBlock b) {
        return !TextUtils.isEmpty(myUid)
                && myUid.equals(b.getLockHolder())
                && !lockExpired(b);
    }

    private boolean lockExpired(NoteBlock b) {
        Date until = b.getLockUntil();
        if (until == null) return true;
        return System.currentTimeMillis() > until.getTime();
    }

    private static String s(String x){ return x == null ? "" : x; }

    // =====================================================
    // ViewHolder
    // =====================================================

    static class TextVH extends RecyclerView.ViewHolder {
        TextView tvIndex, tvMeta, tvLock, tvViewText;
        EditText etEdit;
        ImageButton btnEdit, btnSave, btnCancel, btnAcquire, btnRelease, btnDelete;

        TextVH(@NonNull View v) {
            super(v);
            tvIndex = v.findViewById(R.id.tvIndex);
            tvMeta = v.findViewById(R.id.tvMeta);
            tvLock = v.findViewById(R.id.tvLock);
            tvViewText = v.findViewById(R.id.tvViewText);
            etEdit = v.findViewById(R.id.etEdit);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnSave = v.findViewById(R.id.btnSave);
            btnCancel = v.findViewById(R.id.btnCancel);
            btnAcquire = v.findViewById(R.id.btnAcquire);   // 改為新增 block
            btnRelease = v.findViewById(R.id.btnRelease);
            btnDelete = v.findViewById(R.id.btnDelete);     // 新增刪除按鈕

            if (tvViewText != null) tvViewText.setMovementMethod(new ScrollingMovementMethod());
        }

        void bind(NoteBlock b, boolean iHoldLock, boolean lockExpired) {
            if (tvIndex != null) tvIndex.setText(String.valueOf(b.getIndex()));

            if (tvMeta != null) {
                // 🟢 顯示「誰編輯」：優先 displayName → email → UID
                String who;
                if (!TextUtils.isEmpty(b.getUpdatedByDisplayName())) {
                    who = b.getUpdatedByDisplayName();
                } else if (!TextUtils.isEmpty(b.getUpdatedByEmail())) {
                    who = b.getUpdatedByEmail();
                } else {
                    who = s(b.getUpdatedBy());
                }

                Date t = b.getUpdatedAt();
                String when = (t == null) ? "-" : DateUtils.getRelativeTimeSpanString(
                        t.getTime(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                ).toString();

                tvMeta.setText("v" + b.getVersion() + " • " + (who.isEmpty() ? "-" : who) + " • " + when);
            }

            String text = s(b.getText());
            if (tvViewText != null) tvViewText.setText(text);
            if (etEdit != null)     etEdit.setText(text);

            bindLockText(b, iHoldLock, lockExpired);
            setEditable(iHoldLock);

            if (btnEdit != null)    btnEdit.setVisibility(iHoldLock ? View.GONE : View.VISIBLE);
            if (btnAcquire != null) btnAcquire.setVisibility(View.VISIBLE);
            if (btnRelease != null) btnRelease.setVisibility(iHoldLock ? View.VISIBLE : View.GONE);
            if (btnSave != null)    btnSave.setVisibility(iHoldLock ? View.VISIBLE : View.GONE);
            if (btnCancel != null)  btnCancel.setVisibility(iHoldLock ? View.VISIBLE : View.GONE);

            setupListeners(b);
        }

        void bindClockOnly(NoteBlock b, boolean iHoldLock, boolean lockExpired) {
            bindLockText(b, iHoldLock, lockExpired);
        }

        private void bindLockText(NoteBlock b, boolean iHoldLock, boolean lockExpired) {
            if (tvLock == null) return;

            if (b.getLockHolder() == null || lockExpired) {
                tvLock.setText("未鎖定");
                tvLock.setTextColor(0xFF616161);
                return;
            }
            long remainMs = Math.max(0L,
                    b.getLockUntil() == null ? 0L : (b.getLockUntil().getTime() - System.currentTimeMillis()));
            String remain = remainMs <= 0 ? "已過期" : (remainMs / 1000) + "s";
            String label = iHoldLock ? "我持鎖" : ("被 " + b.getLockHolder() + " 鎖定");
            tvLock.setText(label + "（" + remain + "）");
            tvLock.setTextColor(iHoldLock ? 0xFF2E7D32 : 0xFFC62828);
        }

        private void setEditable(boolean editable) {
            if (etEdit != null) {
                etEdit.setEnabled(editable);
                etEdit.setVisibility(editable ? View.VISIBLE : View.GONE);
            }
            if (tvViewText != null) {
                tvViewText.setVisibility(editable ? View.GONE : View.VISIBLE);
            }
        }

        private void setupListeners(NoteBlock b) {
            Listener l = (Listener) itemView.getTag();
            if (l == null) return;

            clearClick(btnEdit);
            clearClick(btnAcquire);
            clearClick(btnRelease);
            clearClick(btnSave);
            clearClick(btnCancel);

            if (btnEdit != null)    btnEdit.setOnClickListener(v -> l.onAcquireLock(b));
            if (btnAcquire != null) btnAcquire.setOnClickListener(v -> l.onAddAfter(b));
            if (btnRelease != null) btnRelease.setOnClickListener(v -> l.onReleaseLock(b));
            if (btnSave != null)    btnSave.setOnClickListener(v -> {
                String newText = "";
                if (etEdit != null) {
                    Editable ed = etEdit.getText();
                    newText = ed == null ? "" : ed.toString();
                }
                l.onSave(b, newText, b.getVersion());
            });
            if (btnCancel != null)  btnCancel.setOnClickListener(v -> l.onReleaseLock(b));

            // ✅ 長按整個 item 觸發刪除
            itemView.setOnLongClickListener(v -> {
                l.onDelete(b);
                return true;
            });
        }

        private void clearClick(View v) {
            if (v != null) v.setOnClickListener(null);
        }

        private static String s(String x){ return x == null ? "" : x; }
    }

    // =====================================================
    // 公用工具
    // =====================================================
    public void submitSorted(List<NoteBlock> blocks) {
        if (blocks == null) {
            submitList(null);
            return;
        }
        List<NoteBlock> copy = new ArrayList<>(blocks);
        copy.sort((a,b) -> Integer.compare(a.getIndex(), b.getIndex()));
        submitList(copy);
    }

    public List<NoteBlock> current() {
        List<NoteBlock> out = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) out.add(getItem(i));
        return out;
    }
}
*/
package com.ld.ainote.adapters;

import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.ld.ainote.R;
import com.ld.ainote.models.NoteBlock;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 改動要點：
 * 1) 移除每個 block 的「存檔 / 取消」按鈕（統一由底部存檔）。
 * 2) 在 adapter 內維護 drafts（EditText 未送出的文字）。
 * 3) 提供 collectPendingEdits() 讓外部一次取出所有變更，在底部存檔時送出。
 * 4) 移除所有 View.setTag(R.id.xxx, ...)；不需 ids.xml。
 */
public class BlockAdapter extends ListAdapter<NoteBlock, RecyclerView.ViewHolder> {

    // ===== 事件介面（去掉 onSave；存檔改由底部統一觸發） =====
    public interface Listener {
        void onAcquireLock(@NonNull NoteBlock block);
        void onReleaseLock(@NonNull NoteBlock block);
        void onAddAfter(@NonNull NoteBlock block); // 加號新增 block
        void onDelete(@NonNull NoteBlock block);   // 長按刪除
    }

    private static final int VT_TEXT = 1;

    private final Listener listener;
    private String myUid = null;

    // 以 blockId 為 key，儲存使用者尚未送出的編輯文字（draft）
    private final Map<String, String> drafts = new HashMap<>();

    public BlockAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setMyUid(String uid) {
        this.myUid = uid;
        notifyDataSetChanged();
    }

    /** 外部每秒呼叫一次以刷新鎖定倒數 */
    public void updateLockClock() {
        notifyItemRangeChanged(0, getItemCount(), "clock");
    }

    // ===== DiffUtil =====
    private static final DiffUtil.ItemCallback<NoteBlock> DIFF = new DiffUtil.ItemCallback<NoteBlock>() {
        @Override public boolean areItemsTheSame(@NonNull NoteBlock a, @NonNull NoteBlock b) {
            return Objects.equals(a.getId(), b.getId());
        }
        @Override public boolean areContentsTheSame(@NonNull NoteBlock a, @NonNull NoteBlock b) {
            return a.getVersion() == b.getVersion()
                    && Objects.equals(a.getText(), b.getText())
                    && Objects.equals(a.getType(), b.getType())
                    && Objects.equals(a.getLockHolder(), b.getLockHolder())
                    && Objects.equals(a.getUpdatedBy(), b.getUpdatedBy())
                    && Objects.equals(a.getUpdatedAt(), b.getUpdatedAt())
                    && Objects.equals(a.getLockUntil(), b.getLockUntil())
                    && a.getIndex() == b.getIndex();
        }
        @Override public Object getChangePayload(@NonNull NoteBlock oldItem, @NonNull NoteBlock newItem) {
            return null;
        }
    };

    @Override public long getItemId(int position) {
        NoteBlock b = getItem(position);
        return b.getId() == null ? position : b.getId().hashCode();
    }

    @Override public int getItemViewType(int position) {
        NoteBlock b = getItem(position);
        if ("text".equalsIgnoreCase(s(b.getType()))) return VT_TEXT;
        return VT_TEXT;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        View v = inf.inflate(R.layout.item_block_text, parent, false);
        return new TextVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        onBindViewHolder(h, pos, new ArrayList<>());
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos, @NonNull List<Object> payloads) {
        NoteBlock b = getItem(pos);
        boolean iHold = isLockedByMe(b);
        boolean expired = lockExpired(b);

        if (h instanceof TextVH) {
            TextVH tvh = (TextVH) h;
            if (payloads.contains("clock")) {
                tvh.bindClockOnly(b, iHold, expired);
            } else {
                tvh.bind(b, iHold, expired);
            }
        }
    }

    private boolean isLockedByMe(NoteBlock b) {
        return !TextUtils.isEmpty(myUid)
                && myUid.equals(b.getLockHolder())
                && !lockExpired(b);
    }

    private boolean lockExpired(NoteBlock b) {
        Date until = b.getLockUntil();
        if (until == null) return true;
        return System.currentTimeMillis() > until.getTime();
    }

    private static String s(String x){ return x == null ? "" : x; }

    // =====================================================
    // ViewHolder（非 static，方便取用外層 drafts / listener）
    // =====================================================

    class TextVH extends RecyclerView.ViewHolder {
        TextView tvIndex, tvMeta, tvLock, tvViewText;
        EditText etEdit;
        ImageButton btnEdit, btnAcquire, btnRelease, btnDelete;

        // 追蹤 TextWatcher，避免多次疊加
        TextWatcher watcher;

        TextVH(@NonNull View v) {
            super(v);
            tvIndex = v.findViewById(R.id.tvIndex);
            tvMeta = v.findViewById(R.id.tvMeta);
            tvLock = v.findViewById(R.id.tvLock);
            tvViewText = v.findViewById(R.id.tvViewText);
            etEdit = v.findViewById(R.id.etEdit);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnAcquire = v.findViewById(R.id.btnAcquire);   // 改為「新增區塊」
            btnRelease = v.findViewById(R.id.btnRelease);
            btnDelete = v.findViewById(R.id.btnDelete);     // 新增：刪除按鈕

            if (tvViewText != null) tvViewText.setMovementMethod(new ScrollingMovementMethod());
        }

        void bind(NoteBlock b, boolean iHoldLock, boolean lockExpired) {
            if (tvIndex != null) tvIndex.setText(String.valueOf(b.getIndex()));

            if (tvMeta != null) {
                String who;
                if (!TextUtils.isEmpty(b.getUpdatedByDisplayName())) {
                    who = b.getUpdatedByDisplayName();
                } else if (!TextUtils.isEmpty(b.getUpdatedByEmail())) {
                    who = b.getUpdatedByEmail();
                } else {
                    who = s(b.getUpdatedBy());
                }

                Date t = b.getUpdatedAt();
                String when = (t == null) ? "-" : DateUtils.getRelativeTimeSpanString(
                        t.getTime(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                ).toString();

                tvMeta.setText("v" + b.getVersion() + " • " + (who.isEmpty() ? "-" : who) + " • " + when);
            }

            // 顯示內容：若 drafts 有此 block 的編輯中內容，優先顯示 draft
            String serverText = s(b.getText());
            String draft = drafts.get(b.getId());
            String displayText = (draft != null) ? draft : serverText;

            if (tvViewText != null) tvViewText.setText(displayText);
            if (etEdit != null) {
                if (watcher != null) etEdit.removeTextChangedListener(watcher);
                etEdit.setText(displayText);

                etEdit.setEnabled(iHoldLock);
                etEdit.setVisibility(iHoldLock ? View.VISIBLE : View.GONE);
                if (tvViewText != null) tvViewText.setVisibility(iHoldLock ? View.GONE : View.VISIBLE);

                if (iHoldLock) {
                    watcher = new SimpleWatcher(text -> drafts.put(b.getId(), text));
                    etEdit.addTextChangedListener(watcher);
                } else {
                    watcher = null;
                }
            }

            bindLockText(b, iHoldLock, lockExpired);

            // 只保留三個按鈕：編輯(鎖定) / 新增區塊 / 釋放 / 刪除
            if (btnEdit != null)    btnEdit.setVisibility(iHoldLock ? View.GONE : View.VISIBLE);
            if (btnAcquire != null) btnAcquire.setVisibility(View.VISIBLE);
            if (btnRelease != null) btnRelease.setVisibility(iHoldLock ? View.VISIBLE : View.GONE);
            if (btnDelete != null)  btnDelete.setVisibility(View.VISIBLE);

            setupListeners(b);
        }

        void bindClockOnly(NoteBlock b, boolean iHoldLock, boolean lockExpired) {
            bindLockText(b, iHoldLock, lockExpired);
        }

        private void bindLockText(NoteBlock b, boolean iHoldLock, boolean lockExpired) {
            if (tvLock == null) return;

            if (b.getLockHolder() == null || lockExpired) {
                tvLock.setText("未鎖定");
                tvLock.setTextColor(0xFF616161);
                return;
            }
            long remainMs = Math.max(0L,
                    b.getLockUntil() == null ? 0L : (b.getLockUntil().getTime() - System.currentTimeMillis()));
            String remain = remainMs <= 0 ? "已過期" : (remainMs / 1000) + "s";
            String label = iHoldLock ? "我持鎖" : ("被 " + b.getLockHolder() + " 鎖定");
            tvLock.setText(label + "（" + remain + "）");
            tvLock.setTextColor(iHoldLock ? 0xFF2E7D32 : 0xFFC62828);
        }

        private void setupListeners(NoteBlock b) {
            // 直接使用外部 adapter 的 listener，避免 tag / ids.xml
            Listener l = BlockAdapter.this.listener;
            if (l == null) return;

            clearClick(btnEdit);
            clearClick(btnAcquire);
            clearClick(btnRelease);
            clearClick(btnDelete);

            if (btnEdit != null)    btnEdit.setOnClickListener(v -> l.onAcquireLock(b));
            if (btnAcquire != null) btnAcquire.setOnClickListener(v -> l.onAddAfter(b));
            if (btnRelease != null) btnRelease.setOnClickListener(v -> l.onReleaseLock(b));
            if (btnDelete != null)  btnDelete.setOnClickListener(v -> l.onDelete(b));

            itemView.setOnLongClickListener(v -> {
                l.onDelete(b);
                return true;
            });
        }

        private void clearClick(View v) {
            if (v != null) v.setOnClickListener(null);
        }
    }

    // 小型 TextWatcher，避免樣板
    private static class SimpleWatcher implements TextWatcher {
        interface OnChange { void onChanged(String s); }
        private final OnChange cb;
        SimpleWatcher(OnChange cb){ this.cb = cb; }
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) { if (cb != null) cb.onChanged(s==null?"":s.toString()); }
        @Override public void afterTextChanged(Editable s) {}
    }

    // =====================================================
    // 公用工具
    // =====================================================

    /** 依 index 排序後提交；保留 drafts 不清空 */
    public void submitSorted(List<NoteBlock> blocks) {
        if (blocks == null) {
            submitList(null);
            return;
        }
        List<NoteBlock> copy = new ArrayList<>(blocks);
        copy.sort((a,b) -> Integer.compare(a.getIndex(), b.getIndex()));
        submitList(copy);
    }

    /** 取得目前清單 */
    public List<NoteBlock> current() {
        List<NoteBlock> out = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) out.add(getItem(i));
        return out;
    }

    /** 擷取所有「與伺服器文字不同」的編輯草稿，供底部一次存檔使用 */
    public List<PendingEdit> collectPendingEdits() {
        List<PendingEdit> out = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            NoteBlock b = getItem(i);
            if (b == null || b.getId() == null) continue;
            String server = s(b.getText());
            String draft = drafts.get(b.getId());
            if (draft != null && !draft.equals(server)) {
                out.add(new PendingEdit(b, draft));
            }
        }
        return out;
    }

    /** 清掉指定 block 的草稿（例如存檔成功後） */
    public void clearDraft(String blockId) {
        if (blockId != null) drafts.remove(blockId);
    }

    /** 清掉所有草稿（例如整頁儲存後） */
    public void clearAllDrafts() { drafts.clear(); }

    /** 外部可讀的待送出變更資料結構 */
    public static class PendingEdit {
        public final NoteBlock block;
        public final String newText;
        public PendingEdit(NoteBlock block, String newText) {
            this.block = block;
            this.newText = newText;
        }
    }
}
