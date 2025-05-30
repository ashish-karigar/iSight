package com.example.uvccamerapreview;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.example.uvccamerapreview.databinding.ActivityWelcomeBinding;

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

        binding.tutorialButton.setOnClickListener(v -> {
            // TODO: show tutorial
        });

        binding.selectStorageButton.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            String uriString = prefs.getString("video_folder_uri", null);
            if (uriString != null) {
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse(uriString));
            }
            startActivityForResult(i, REQUEST_CODE_PICK_DIR);
        });

        binding.previewButton.setOnClickListener(v -> {
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
            prefs.edit().putString("video_folder_uri", treeUri.toString()).apply();
            Toast.makeText(this, "Storage folder set", Toast.LENGTH_SHORT).show();
        }
    }
}