package com.example.uvccamerapreview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class SplashActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 2001;  // different from LoginActivity's old code if you keep it

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // No layout is strictly necessary; you can show a splash screen if you want
        // setContentView(R.layout.activity_splash);

        boolean loggedIn = getSharedPreferences("auth_prefs", MODE_PRIVATE)
                .getBoolean("is_logged_in", false);

        if (!loggedIn) {
            // Not logged in → go to LoginActivity
            Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
        } else {
            // Logged in → make sure we have camera + audio permissions before WelcomeActivity
            if (checkAndRequestPermissions()) {
                goToWelcomeScreen();
            }
        }
    }

    private boolean checkAndRequestPermissions() {
        List<String> neededPermissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    neededPermissions.toArray(new String[0]),
                    REQUEST_PERMISSIONS
            );
            return false;
        }

        // Already granted
        return true;
    }

    private void goToWelcomeScreen() {
        Intent intent = new Intent(SplashActivity.this, WelcomeActivity.class);
        startActivity(intent);
        finish();  // Don't return to splash when back is pressed
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;

            if (grantResults.length > 0) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            } else {
                allGranted = false;
            }

            if (allGranted) {
                goToWelcomeScreen();
            } else {
                Toast.makeText(
                        this,
                        "Camera and Audio permissions are required to use the camera.",
                        Toast.LENGTH_LONG
                ).show();
                // You could still move to WelcomeActivity if you want the app usable without camera
                // or stay here / close app etc.
            }
        }
    }
}
