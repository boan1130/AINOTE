package com.ld.ainote.fragments;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.*;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.ld.ainote.R;
import com.ld.ainote.data.BlockRepository;
import com.ld.ainote.data.NoteRepository;
import com.ld.ainote.models.Note;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class OcrFragment extends Fragment {

    private ImageView imgPreview;
    private ProgressBar progress;
    private TextInputEditText etRecognized;

    private Uri currentPhotoUri;

    private ActivityResultLauncher<String[]> requestPermissions;
    private ActivityResultLauncher<Uri> takePictureLauncher;
    private ActivityResultLauncher<String> pickImageLauncher;

    private final SimpleDateFormat titleFmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ocr, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        imgPreview    = v.findViewById(R.id.imgPreview);
        progress      = v.findViewById(R.id.progress);
        etRecognized  = v.findViewById(R.id.etRecognized);
        MaterialButton btnCamera   = v.findViewById(R.id.btnCamera);
        MaterialButton btnGallery  = v.findViewById(R.id.btnGallery);
        MaterialButton btnCopy     = v.findViewById(R.id.btnCopy);
        MaterialButton btnSaveNote = v.findViewById(R.id.btnSaveNote);

        requestPermissions = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {});

        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                (ActivityResultCallback<Boolean>) success -> {
                    if (Boolean.TRUE.equals(success) && currentPhotoUri != null) {
                        loadAndRecognize(currentPhotoUri);
                    }
                });

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        currentPhotoUri = uri;
                        loadAndRecognize(uri);
                    }
                });

        btnCamera.setOnClickListener(v1 -> openCamera());
        btnGallery.setOnClickListener(v12 -> openGallery());

        btnCopy.setOnClickListener(v13 -> {
            String txt = safeText(etRecognized);
            if (TextUtils.isEmpty(txt)) {
                Toast.makeText(getContext(), "目前沒有文字可複製", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ocr", txt));
            Toast.makeText(getContext(), "已複製到剪貼簿", Toast.LENGTH_SHORT).show();
        });

        btnSaveNote.setOnClickListener(v14 -> {
            String content = safeText(etRecognized);
            if (TextUtils.isEmpty(content)) {
                Toast.makeText(getContext(), "沒有辨識到文字，無法儲存", Toast.LENGTH_SHORT).show();
                return;
            }

            String title = "OCR 擷取 " + titleFmt.format(new Date());
            Note note = new Note(title, ""); // 不直接塞 content，改由 blocks 儲存
            note.setStack("OCR");
            note.setChapter(0);
            note.setSection(0);

            NoteRepository repo = new NoteRepository();
            repo.addNote(note, task -> {
                if (task != null && task.isSuccessful() && task.getResult() != null) {
                    String noteId = task.getResult().getId();
                    String ownerId = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();

                    // ✅ 自動分段：以空行分段（你可依需求調整）
                    String[] paragraphs = content.split("\\n{2,}");
                    BlockRepository blockRepo = new BlockRepository();
                    for (int i = 0; i < paragraphs.length; i++) {
                        String para = paragraphs[i].trim();
                        if (para.isEmpty()) continue;
                        blockRepo.createBlock(ownerId, noteId, i, "text", para);
                    }

                    Toast.makeText(getContext(), "已存成筆記（共 " + paragraphs.length + " 段）", Toast.LENGTH_SHORT).show();
                } else {
                    String msg = (task != null && task.getException() != null)
                            ? task.getException().getMessage()
                            : "未知錯誤";
                    Toast.makeText(getContext(), "儲存失敗：" + msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void openCamera() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions.launch(new String[]{ Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES });
        } else {
            requestPermissions.launch(new String[]{ Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE });
        }

        try {
            File outFile = createTempImageFile();
            currentPhotoUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    outFile
            );
            takePictureLauncher.launch(currentPhotoUri);
        } catch (IOException e) {
            Toast.makeText(getContext(), "無法建立檔案：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openGallery() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions.launch(new String[]{ Manifest.permission.READ_MEDIA_IMAGES });
        } else {
            requestPermissions.launch(new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE });
        }
        pickImageLauncher.launch("image/*");
    }

    private void loadAndRecognize(@NonNull Uri uri) {
        progress.setVisibility(View.VISIBLE);
        try {
            Bitmap bmp;
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(requireContext().getContentResolver(), uri);
                bmp = ImageDecoder.decodeBitmap(src);
            } else {
                bmp = MediaStore.Images.Media.getBitmap(requireContext().getContentResolver(), uri);
            }
            imgPreview.setImageBitmap(bmp);
        } catch (IOException e) {
            progress.setVisibility(View.GONE);
            Toast.makeText(getContext(), "載入圖片失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        try {
            InputImage image = InputImage.fromFilePath(requireContext(), uri);

            com.google.mlkit.vision.text.TextRecognizer recognizerZh =
                    TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

            recognizerZh.process(image)
                    .addOnSuccessListener(this::onTextRecognized)
                    .addOnFailureListener(e -> {
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                .process(image)
                                .addOnSuccessListener(this::onTextRecognized)
                                .addOnFailureListener(e2 -> {
                                    progress.setVisibility(View.GONE);
                                    Toast.makeText(getContext(), "辨識失敗：" + e2.getMessage(), Toast.LENGTH_LONG).show();
                                });
                    });

        } catch (IOException e) {
            progress.setVisibility(View.GONE);
            Toast.makeText(getContext(), "讀取圖片失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onTextRecognized(@NonNull Text result) {
        progress.setVisibility(View.GONE);
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(block.getText());
        }
        etRecognized.setText(sb.toString());
        if (TextUtils.isEmpty(sb.toString())) {
            Toast.makeText(getContext(), "沒有辨識到文字，請換張更清晰的圖片", Toast.LENGTH_SHORT).show();
        }
    }

    private File createTempImageFile() throws IOException {
        String fileName = "OCR_" + System.currentTimeMillis();
        File storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(fileName, ".jpg", storageDir);
    }

    private String safeText(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }
}
