package com.example.uvccamerapreview;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.example.uvccamerapreview.databinding.ActivityWelcomeBinding;

public class WelcomeActivity extends AppCompatActivity {
    private ActivityWelcomeBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWelcomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.tutorialButton.setOnClickListener(v -> {
            // Open tutorial (TODO: Add real logic or activity)
        });

        binding.selectStorageButton.setOnClickListener(v -> {
            // Trigger storage path selection (TODO: Add logic)
        });

        binding.previewButton.setOnClickListener(v -> {
            Intent intent = new Intent(WelcomeActivity.this, MainActivity.class);
            startActivity(intent);
        });
    }
}
