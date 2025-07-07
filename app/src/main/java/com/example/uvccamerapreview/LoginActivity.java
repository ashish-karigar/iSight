package com.example.uvccamerapreview;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uvccamerapreview.WelcomeActivity;

public class LoginActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1001;

    EditText usernameEditText, passwordEditText, confirmPasswordEditText;
    CheckBox signupCheckbox;
    Button loginButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        usernameEditText = findViewById(R.id.username_edittext);
        passwordEditText = findViewById(R.id.password_edittext);
        confirmPasswordEditText = findViewById(R.id.confirm_password_edittext);
        signupCheckbox = findViewById(R.id.signup_checkbox);
        loginButton = findViewById(R.id.login_button);

        signupCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                confirmPasswordEditText.setVisibility(View.VISIBLE);
                loginButton.setText("Sign Up");
            } else {
                confirmPasswordEditText.setVisibility(View.GONE);
                loginButton.setText("Sign In");
            }
        });

        // In onCreate() method
        loginButton.setOnClickListener(v -> {
            String username = usernameEditText.getText().toString().trim();
            String password = passwordEditText.getText().toString();
            boolean isSignup = signupCheckbox.isChecked();

            if (username.isEmpty() || password.isEmpty() || (isSignup && confirmPasswordEditText.getText().toString().isEmpty())) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                return;
            }

            if (isSignup) {
                String confirmPassword = confirmPasswordEditText.getText().toString();
                if (!password.equals(confirmPassword)) {
                    Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
                    return;
                }
                // TODO: Handle sign-up logic here
                Toast.makeText(this, "Signed up successfully", Toast.LENGTH_SHORT).show();
            } else {
                // Check hardcoded credentials
                if (!username.equals("test user") || !password.equals("password")) {
                    Toast.makeText(this, "Invalid username or password", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, "Signed in successfully", Toast.LENGTH_SHORT).show();
            }

            // After successful auth, check for permissions before navigating.
            // If permissions are already granted, navigate immediately.
            if (checkAndRequestPermissions()) {
                navigateToWelcomeScreen();
            }
            // If not, the request is sent, and onRequestPermissionsResult will handle navigation.
        });;
    }

    private void navigateToWelcomeScreen() {
        Intent intent = new Intent(LoginActivity.this, WelcomeActivity.class);
        startActivity(intent);
        finish();
    }

    private boolean checkAndRequestPermissions() {
        List<String> neededPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            return false;
        }
        // Permissions were already granted
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            if (grantResults.length > 0) {
                for (int grantResult : grantResults) {
                    if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        break;
                    }
                }
            } else {
                allGranted = false; // No results means no permissions were granted
            }


            if (allGranted) {
                // Permissions granted, proceed to the next screen.
                navigateToWelcomeScreen();
            } else {
                // Permissions denied. Show a message to the user.
                Toast.makeText(this, "Camera and Audio permissions are required to use the camera.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
