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

import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUser;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserAttributes;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.SignUpHandler;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.GenericHandler;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.UpdateAttributesHandler;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cognitoidentityprovider.model.SignUpResult;



public class LoginActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1001;

    EditText usernameEditText, passwordEditText, confirmPasswordEditText, verificationCodeEditText;
    CheckBox signupCheckbox;
    Button loginButton;

    private boolean isVerificationStage = false;
    private String pendingSignupUsername = "";

    // AWS Cognito user pool instance
    private CognitoUserPool userPool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        usernameEditText = findViewById(R.id.username_edittext);
        passwordEditText = findViewById(R.id.password_edittext);
        confirmPasswordEditText = findViewById(R.id.confirm_password_edittext);
        verificationCodeEditText = findViewById(R.id.verification_code_edittext);
        signupCheckbox = findViewById(R.id.signup_checkbox);
        loginButton = findViewById(R.id.login_button);


        signupCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            confirmPasswordEditText.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            verificationCodeEditText.setVisibility(View.GONE); // Hide code input if switching back
            loginButton.setText(isChecked ? "Sign Up" : "Sign In");
            isVerificationStage = false; // Reset verification state
        });

        // Initialize Cognito user pool
        userPool = new CognitoUserPool(
                this,
                AwsCognitoConstants.USER_POOL_ID,
                AwsCognitoConstants.APP_CLIENT_ID,
                null, // client secret: null for mobile client
                AwsCognitoConstants.AWS_REGION
        );

//        signupCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
//            if (isChecked) {
//                confirmPasswordEditText.setVisibility(View.VISIBLE);
//                loginButton.setText("Sign Up");
//            } else {
//                confirmPasswordEditText.setVisibility(View.GONE);
//                loginButton.setText("Sign In");
//            }
//        });

        // In onCreate() method
        loginButton.setOnClickListener(v -> {
            String username = usernameEditText.getText().toString().trim();
            String password = passwordEditText.getText().toString();
            boolean isSignup = signupCheckbox.isChecked();

            if (isVerificationStage) {
                // Verify user with code
                String code = verificationCodeEditText.getText().toString().trim();
                if (code.isEmpty()) {
                    Toast.makeText(this, "Please enter verification code", Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmUser(pendingSignupUsername, code);
                return;
            }

            if (isSignup) {
                String confirmPassword = confirmPasswordEditText.getText().toString();
                if (username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                    Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!password.equals(confirmPassword)) {
                    Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
                    return;
                }
                signUpUser(username, password);
            } else {
                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                    return;
                }
                signInUser(username, password);
            }

            // After successful auth, check for permissions before navigating.
            // If permissions are already granted, navigate immediately.
//            if (checkAndRequestPermissions()) {
//                navigateToWelcomeScreen();
//            }
            // If not, the request is sent, and onRequestPermissionsResult will handle navigation.
        });;
    }

    private void signUpUser(String username, String password) {
        CognitoUserAttributes userAttributes = new CognitoUserAttributes();
        userAttributes.addAttribute("email", username);

        userPool.signUpInBackground(username, password, userAttributes, null, new SignUpHandler() {
            @Override
            public void onSuccess(CognitoUser user, SignUpResult signUpResult) {
                runOnUiThread(() -> {
                    if (signUpResult.getUserConfirmed()) {
                        Toast.makeText(LoginActivity.this, "Signup successful. You can now sign in.", Toast.LENGTH_LONG).show();
                        signupCheckbox.setChecked(false); // Switch back to sign-in mode
                    } else {
                        // User needs to verify
                        pendingSignupUsername = username;
                        verificationCodeEditText.setVisibility(View.VISIBLE); // Show code input
                        loginButton.setText("Verify");
                        isVerificationStage = true;

                        String destination = (signUpResult.getCodeDeliveryDetails() != null)
                                ? signUpResult.getCodeDeliveryDetails().getDestination()
                                : "your contact";
                        Toast.makeText(LoginActivity.this, "Signed up. Confirm code sent to: " + destination, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onFailure(Exception exception) {
                runOnUiThread(() -> {
                    String msg = exception.getMessage() != null ? exception.getMessage() : "Signup failed";
                    Toast.makeText(LoginActivity.this, "Signup failed: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmUser(String username, String code) {
        CognitoUser user = userPool.getUser(username);
        user.confirmSignUpInBackground(code, false, new GenericHandler() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(LoginActivity.this, "Account confirmed. Please log in.", Toast.LENGTH_SHORT).show();
                    verificationCodeEditText.setVisibility(View.GONE);
                    isVerificationStage = false;
                    loginButton.setText("Sign In");
                    signupCheckbox.setChecked(false);
                });
            }

            @Override
            public void onFailure(Exception exception) {
                runOnUiThread(() -> Toast.makeText(LoginActivity.this, "Verification failed: " + exception.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void signInUser(String username, String password) {
        CognitoUser user = userPool.getUser(username);
        user.signOut();

        AuthenticationHandler authHandler = new AuthenticationHandler() {
            @Override
            public void onSuccess(com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession userSession, com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice newDevice) {
                // Authentication successful
                runOnUiThread(() -> {
                    Toast.makeText(LoginActivity.this, "Login successful", Toast.LENGTH_SHORT).show();
                    // After successful auth, proceed to permission check / next screen
                    if (checkAndRequestPermissions()) {
                        navigateToWelcomeScreen();
                    }
                });
            }

            @Override
            public void getAuthenticationDetails(
                    com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation authenticationContinuation,
                    String userId) {

                // Build AuthenticationDetails with the provided username/password
                com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails authDetails =
                        new com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails(
                                username,
                                password,
                                null // No validation data
                        );

                // Pass the details and continue
                authenticationContinuation.setAuthenticationDetails(authDetails);
                authenticationContinuation.continueTask();
            }


            @Override
            public void getMFACode(com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation continuation) {
                // If you have MFA enabled, prompt user and call continuation.setMfaCode(...)
                runOnUiThread(() -> Toast.makeText(LoginActivity.this, "MFA required but not handled in this sample", Toast.LENGTH_LONG).show());
            }

            @Override
            public void authenticationChallenge(com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation continuation) {
                // Respond to other challenges (e.g., new password required)
                runOnUiThread(() -> Toast.makeText(LoginActivity.this, "Challenge required: " + continuation.getChallengeName(), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onFailure(Exception exception) {
                runOnUiThread(() -> {
                    String msg = exception.getMessage() != null ? exception.getMessage() : "Login failed";
                    Toast.makeText(LoginActivity.this, "Login failed: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        };

        // Start the sign-in flow (this will call getAuthenticationDetails)
        user.getSessionInBackground(authHandler);
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
