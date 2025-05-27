package com.example.uvccamerapreview;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.camera.CameraUVC;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.usb.USBMonitor;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ICameraStateCallBack {
    private static final String TAG = "UVCCameraPreview";
    private static final boolean DEBUG = true;
    private static final int REQUEST_PERMISSIONS = 1001;
    
    // The default width and height for preview
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    
    // UI references
    private FrameLayout mCameraViewContainer;
    private AspectRatioTextureView mCameraView;
    private TextView mStatusTextView;
    private FloatingActionButton mCaptureButton;
    
    // Camera client
    private MultiCameraClient mCameraClient;
    private MultiCameraClient.ICamera mCamera;
    
    // Photo capture state
    private boolean mIsCapturing = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize UI components
        mCameraViewContainer = findViewById(R.id.camera_preview);
        mStatusTextView = findViewById(R.id.status_text);
        mCaptureButton = findViewById(R.id.capture_button);
        
        // Create and add camera texture view
        mCameraView = new AspectRatioTextureView(this);
        mCameraViewContainer.addView(mCameraView);
        
        // Set up capture button
        mCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCamera != null && !mIsCapturing) {
                    capturePhoto();
                }
            }
        });
        
        // Check permissions before initializing camera
        if (checkAndRequestPermissions()) {
            initializeCamera();
        }
    }
    
    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != 
                PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != 
                PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }
        
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, 
                    permissionsNeeded.toArray(new String[0]), 
                    REQUEST_PERMISSIONS);
            return false;
        }
        
        return true;
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                initializeCamera();
            } else {
                Toast.makeText(this, "Permissions are required to use the camera", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    private void initializeCamera() {
        // Initialize camera client with device connect callback
        mCameraClient = new MultiCameraClient(this, new IDeviceConnectCallBack() {
            @Override
            public void onAttachDev(UsbDevice device) {
                if (DEBUG) Log.d(TAG, "USB device attached: " + device.getDeviceName());
                // Request permission when device is attached
                mCameraClient.requestPermission(device);
            }

            @Override
            public void onDetachDec(UsbDevice device) {
                if (DEBUG) Log.d(TAG, "USB device detached: " + device.getDeviceName());
                // If the camera we're using was detached, close it
                if (mCamera != null && device.equals(mCamera.getUsbDevice())) {
                    closeCamera();
                }
                
                // Disable capture button
                runOnUiThread(() -> {
                    mCaptureButton.setEnabled(false);
                });
            }

            @Override
            public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                if (DEBUG) Log.d(TAG, "USB device connected: " + device.getDeviceName());
                // Create camera instance - using CameraUVC instead of MultiCameraClient.UVCCamera
                mCamera = new CameraUVC(MainActivity.this, device);
                mCamera.setUsbControlBlock(ctrlBlock);
                
                // Set camera state callback
                mCamera.setCameraStateCallBack(MainActivity.this);
                
                // Create camera request
                CameraRequest cameraRequest = new CameraRequest.Builder()
                    .setPreviewWidth(DEFAULT_WIDTH)
                    .setPreviewHeight(DEFAULT_HEIGHT)
                    .create();
                
                // Open camera with texture view
                mCamera.openCamera(mCameraView, cameraRequest);
                
                // Enable capture button
                runOnUiThread(() -> {
                    mCaptureButton.setEnabled(true);
                });
            }

            @Override
            public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                if (DEBUG) Log.d(TAG, "USB device disconnected: " + device.getDeviceName());
                closeCamera();
                
                // Disable capture button
                runOnUiThread(() -> {
                    mCaptureButton.setEnabled(false);
                });
            }

            @Override
            public void onCancelDev(UsbDevice device) {
                if (DEBUG) Log.d(TAG, "USB device permission denied: " + device.getDeviceName());
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Permission denied", Toast.LENGTH_SHORT).show();
                    mStatusTextView.setText(R.string.status_no_device);
                    mCaptureButton.setEnabled(false);
                });
            }
        });
    }
    
    /**
     * Creates a file for saving a photo
     * @return File object for the photo or null if creation failed
     */
    private File createPhotoFile() {
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String imageFileName = "UVC_IMG_" + timeStamp;
            
            File storageDir;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // For Android 10 and above, use app-specific directory
                storageDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "UVCCamera");
            } else {
                // For older versions, use public directory
                storageDir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "UVCCamera");
            }
            
            // Create the storage directory if it does not exist
            if (!storageDir.exists()) {
                if (!storageDir.mkdirs()) {
                    Log.e(TAG, "Failed to create directory: " + storageDir.getAbsolutePath());
                    return null;
                }
            }
            
            // Create the file
            File image = new File(storageDir, imageFileName + ".jpg");
            if (DEBUG) Log.d(TAG, "Photo will be saved to: " + image.getAbsolutePath());
            return image;
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating photo file", e);
            return null;
        }
    }
    
    private void capturePhoto() {
        if (mCamera != null) {
            try {
                mIsCapturing = true;
                Log.d(TAG, "Starting photo capture process");
                
                // Update UI to show capturing state
                mCaptureButton.setEnabled(false);
                Toast.makeText(this, "Taking photo...", Toast.LENGTH_SHORT).show();
                
                // Verify storage permissions before capture
                if (android.os.Build.VERSION.SDK_INT <= 28) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != 
                            PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Missing WRITE_EXTERNAL_STORAGE permission");
                        Toast.makeText(this, "Storage permission required to save photos", Toast.LENGTH_LONG).show();
                        mCaptureButton.setEnabled(true);
                        mIsCapturing = false;
                        return;
                    }
                }
                
                // Create a file for the photo
                File photoFile = createPhotoFile();
                final String photoPath = (photoFile != null) ? photoFile.getAbsolutePath() : null;
                
                if (photoPath == null) {
                    Log.e(TAG, "Failed to create photo file path");
                    Toast.makeText(this, "Could not create file to save photo", Toast.LENGTH_SHORT).show();
                    mCaptureButton.setEnabled(true);
                    mIsCapturing = false;
                    return;
                }
                
                Log.d(TAG, "Photo will be saved to: " + photoPath);
                
                // Use a background thread for the capture to avoid ANR
                new Thread(() -> {
                    try {
                        // Add a small delay to make sure camera is ready
                        Thread.sleep(500);
                        
                        // Capture image using the camera
                        mCamera.captureImage(new ICaptureCallBack() {
                            @Override
                            public void onBegin() {
                                Log.d(TAG, "Photo capture started");
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "Photo capture error: " + error);
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, "Failed to capture photo: " + error, Toast.LENGTH_SHORT).show();
                                    mCaptureButton.setEnabled(true);
                                    mIsCapturing = false;
                                });
                            }

                            @Override
                            public void onComplete(String path) {
                                Log.d(TAG, "Photo captured successfully: " + path);
                                // Verify the file exists
                                File savedFile = new File(path);
                                if (savedFile.exists()) {
                                    Log.d(TAG, "Photo file exists: " + savedFile.length() + " bytes");
                                } else {
                                    Log.e(TAG, "Photo file does not exist at path: " + path);
                                }
                                
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, "Photo saved to: " + path, Toast.LENGTH_LONG).show();
                                    mCaptureButton.setEnabled(true);
                                    mIsCapturing = false;
                                });
                            }
                        }, photoPath); // Use our custom photo path
                        
                        Log.d(TAG, "captureImage method called successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in capture thread", e);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Error taking photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            mCaptureButton.setEnabled(true);
                            mIsCapturing = false;
                        });
                    }
                }).start();
                
            } catch (Exception e) {
                Log.e(TAG, "Exception during photo capture", e);
                Toast.makeText(this, "Error taking photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
                mCaptureButton.setEnabled(true);
                mIsCapturing = false;
            }
        } else {
            Log.e(TAG, "Camera is null, cannot take photo");
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Register camera client
        if (mCameraClient != null) {
            mCameraClient.register();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Unregister camera client
        if (mCameraClient != null) {
            mCameraClient.unRegister();
        }
    }

    @Override
    protected void onDestroy() {
        // Release camera resources
        closeCamera();
        if (mCameraClient != null) {
            mCameraClient.destroy();
            mCameraClient = null;
        }
        super.onDestroy();
    }

    private void closeCamera() {
        if (mCamera != null) {
            mCamera.closeCamera();
            mCamera = null;
        }
    }

    // ICameraStateCallBack implementation
    @Override
    public void onCameraState(MultiCameraClient.ICamera self, State code, String msg) {
        if (DEBUG) Log.d(TAG, "Camera state: " + code + ", msg: " + msg);
        runOnUiThread(() -> {
            switch (code) {
                case OPENED:
                    mStatusTextView.setText(R.string.status_device_connected);
                    mCaptureButton.setEnabled(true);
                    break;
                case CLOSED:
                    mStatusTextView.setText(R.string.status_no_device);
                    mCaptureButton.setEnabled(false);
                    break;
                case ERROR:
                    Toast.makeText(MainActivity.this, "Camera error: " + msg, Toast.LENGTH_SHORT).show();
                    mStatusTextView.setText(R.string.status_no_device);
                    mCaptureButton.setEnabled(false);
                    break;
            }
        });
    }
} 