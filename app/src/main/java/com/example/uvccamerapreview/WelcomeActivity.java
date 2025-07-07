package com.example.uvccamerapreview;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.example.uvccamerapreview.databinding.ActivityWelcomeBinding;
import java.io.File;

public class WelcomeActivity extends AppCompatActivity {
    private ActivityWelcomeBinding binding;
    private static final int REQUEST_CODE_PICK_DIR = 2001;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWelcomeBinding.inflate(getLayoutInflater());
        Log.d("DEBUG", "WelcomeActivity onCreate triggered");
        Toast.makeText(this, "WelcomeActivity loaded", Toast.LENGTH_SHORT).show();
        setContentView(binding.getRoot());
        prefs = getSharedPreferences("uvc_prefs", MODE_PRIVATE);

        // --- MODIFIED LOGIC HERE ---
        // New logic to handle user-selected vs default folders separately
        String userUri = prefs.getString("user_folder_uri", null);
        String effectiveUri = prefs.getString("effective_folder_uri", null);

        if (userUri == null && effectiveUri == null) {
            File defaultDir = new File(getExternalFilesDir(Environment.DIRECTORY_DCIM), "UVCCamera");
            if (!defaultDir.exists()) defaultDir.mkdirs();
            Uri defaultUri = Uri.fromFile(defaultDir);

            prefs.edit()
                    .putString("effective_folder_uri", defaultUri.toString())
                    .apply();

            Log.d("WelcomeActivity", "Using default fallback folder: " + defaultUri);
            Toast.makeText(this, "Default storage folder set", Toast.LENGTH_SHORT).show();
        }

        // ---------------------------

        binding.tutorialButton.setOnClickListener(v -> {
            // TODO: show tutorial
        });

        binding.selectStorageButton.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            String uriString = prefs.getString("user_folder_uri", null);
            if (uriString != null) {
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse(uriString));
            }
            startActivityForResult(i, REQUEST_CODE_PICK_DIR);
        });

        binding.previewButton.setOnClickListener(v -> {
            String effectiveUriString = prefs.getString("effective_folder_uri", null);

            if (effectiveUriString == null) {
                // This case shouldn't happen if onCreate handled everything correctly,
                // but just in case — reset to default.
                File defaultDir = new File(getExternalFilesDir(Environment.DIRECTORY_DCIM), "UVCCamera");
                if (!defaultDir.exists()) {
                    defaultDir.mkdirs();
                }
                Uri defaultUri = Uri.fromFile(defaultDir);

                prefs.edit()
                        .putString("default_folder_uri", defaultUri.toString())
                        .putString("effective_folder_uri", defaultUri.toString())
                        .apply();

                Toast.makeText(this, "Default folder re-set", Toast.LENGTH_SHORT).show();
                Log.w("WelcomeActivity", "Fallback default folder URI applied again: " + defaultUri.toString());
            } else {
                Log.d("WelcomeActivity", "Effective URI found and will be used: " + effectiveUriString);
            }

            startActivity(new Intent(this, MainActivity.class));
        });

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_DIR && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            prefs.edit()
                    .putString("user_folder_uri", treeUri.toString())
                    .putString("effective_folder_uri", treeUri.toString())
                    .apply();

            Toast.makeText(this, "User-selected storage folder set", Toast.LENGTH_SHORT).show();
            Log.d("WelcomeActivity", "User-selected URI saved: " + treeUri.toString());
        }

    }
}