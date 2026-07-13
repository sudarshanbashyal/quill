package mse.quill.ui.notes.editor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class ImageEmbedder {

    public interface ImageResultListener {
        void onImageReady(Bitmap bitmap, String filePath);
        void onImageFailed();
    }

    private final Fragment fragment;
    private final ImageResultListener listener;

    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<String> galleryLauncher;
    private String pendingImagePath;

    public ImageEmbedder(Fragment fragment, ImageResultListener listener) {
        this.fragment = fragment;
        this.listener = listener;
        registerLaunchers();
    }

    private void registerLaunchers() {
        // Camera launcher
        cameraLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && pendingImagePath != null) {
                        Bitmap bitmap = decodeSampledBitmap(pendingImagePath, 800);
                        if (bitmap != null) {
                            listener.onImageReady(bitmap, pendingImagePath);
                        } else {
                            listener.onImageFailed();
                        }
                    } else {
                        listener.onImageFailed();
                    }
                }
        );

        // Gallery launcher
        galleryLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        String savedPath = saveUriToPrivateStorage(uri);
                        if (savedPath != null) {
                            Bitmap bitmap = decodeSampledBitmap(savedPath, 800);
                            if (bitmap != null) {
                                listener.onImageReady(bitmap, savedPath);
                            } else {
                                listener.onImageFailed();
                            }
                        } else {
                            listener.onImageFailed();
                        }
                    }
                }
        );
    }

    public void openCamera() {
        try {
            File imageFile = createPrivateImageFile();
            pendingImagePath = imageFile.getAbsolutePath();
            Uri photoUri = FileProvider.getUriForFile(
                    fragment.requireContext(),
                    fragment.requireContext().getPackageName() + ".fileprovider",
                    imageFile
            );
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            listener.onImageFailed();
        }
    }

    public void openGallery() {
        galleryLauncher.launch("image/*");
    }

    private File createPrivateImageFile() throws IOException {
        String fileName = "img_" + UUID.randomUUID().toString();
        File storageDir = new File(
                fragment.requireContext().getFilesDir(), "images"
        );
        if (!storageDir.exists()) storageDir.mkdirs();
        return new File(storageDir, fileName + ".jpg");
    }

    private String saveUriToPrivateStorage(Uri uri) {
        try {
            String fileName = "img_" + UUID.randomUUID().toString() + ".jpg";
            File storageDir = new File(
                    fragment.requireContext().getFilesDir(), "images"
            );
            if (!storageDir.exists()) storageDir.mkdirs();
            File destFile = new File(storageDir, fileName);

            try (java.io.InputStream in = fragment.requireContext()
                    .getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            return destFile.getAbsolutePath();
        } catch (IOException e) {
            return null;
        }
    }

    // Decode without loading full resolution into memory
    private Bitmap decodeSampledBitmap(String path, int maxWidth) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        int sampleSize = 1;
        while (options.outWidth / sampleSize > maxWidth) {
            sampleSize *= 2;
        }

        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(path, options);
    }
}