package mse.quill.ui.notes.editor;

import android.app.Activity;
import android.content.ActivityNotFoundException;
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

import mse.quill.util.BitmapUtils;

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
                        deliver(pendingImagePath);
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
                            deliver(savedPath);
                        } else {
                            listener.onImageFailed();
                        }
                    }
                }
        );
    }

    /** Both sources land here so orientation is normalised once, on the way in — a gallery pick
     *  carries the same EXIF rotation a capture does, since importing copies the file verbatim. */
    private void deliver(String path) {
        BitmapUtils.normaliseStoredImage(path);
        Bitmap bitmap = BitmapUtils.decodeSampled(path, 800);
        if (bitmap != null) {
            listener.onImageReady(bitmap, path);
        } else {
            listener.onImageFailed();
        }
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
            // The camera app is a separate process, so it needs explicit permission on our
            // content:// URI — without the write grant it can't save and returns
            // RESULT_CANCELED. The read grant is granted implicitly today, but Android logs
            // that implicit grants for ACTION_IMAGE_CAPTURE end in Android 18, so set both.
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            listener.onImageFailed();
        } catch (ActivityNotFoundException e) {
            // No camera app installed (common on bare emulator images).
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

}