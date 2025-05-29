package com.example.uvccamerapreview;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

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
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ICameraStateCallBack {
    private static final String TAG = "UVCCameraPreview";
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_PICK_FOLDER = 2001;
    private static final int DEFAULT_WIDTH = 320;
    private static final int DEFAULT_HEIGHT = 240;

    // UI
    private FrameLayout mCameraViewContainer;
    private AspectRatioTextureView mCameraView;
    private TextView mStatusTextView;
//    private FloatingActionButton mCaptureButton;
    private FloatingActionButton mRecordButton;

    // Camera
    private MultiCameraClient mCameraClient;
    private CameraUVC mCamera;

    // State
    private boolean mIsCapturing = false;
    private boolean mIsRecording = false;
    private Uri mTreeUri;           // SAF folder URI
    private String mTempSaveDir;    // local temp path
    private String mCurrentVideoPath; // <<<< ADDED: To store current video path

    private final ICaptureCallBack videoCb = new ICaptureCallBack() {
        @Override
        public void onBegin() {
            Log.d(TAG, "videoCb.onBegin called");
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Recording…", Toast.LENGTH_SHORT).show());
        }

        @Override
        public void onError(String error) {
            Log.e(TAG, "videoCb.onError called with error: " + error);
            boolean wasFileCopied = false;
            if (error != null && error.contains("Mutation of _data is not allowed") && mCurrentVideoPath != null) {
                Log.w(TAG, "Mutation error detected. Attempting to salvage video: " + mCurrentVideoPath);
                File tempFile = new File(mCurrentVideoPath);
                if (tempFile.exists() && tempFile.length() > 0) {
                    Log.d(TAG, "Temp file for salvage exists and is not empty. Size: " + tempFile.length());
                    copyToFolder(mCurrentVideoPath); // Attempt to copy
                    wasFileCopied = true; // Assume copyToFolder will show its own success/failure toast
                } else {
                    Log.e(TAG, "Temp file for salvage is missing or empty. Path: " + mCurrentVideoPath);
                }
            }

            final boolean finalWasFileCopied = wasFileCopied;
            runOnUiThread(() -> {
                mIsRecording = false;
                mRecordButton.setImageResource(android.R.drawable.ic_media_play);
                if (!finalWasFileCopied) { // Only show generic error if not handled by copyToFolder
                    Toast.makeText(MainActivity.this,
                            "Record error: " + error, Toast.LENGTH_LONG).show();
                }
                Log.e(TAG, "Recording error processed. mIsRecording set to false.");
            });
            mCurrentVideoPath = null; // Clear path after handling
        }

        @Override
        public void onComplete(String path) {
            Log.d(TAG, "videoCb.onComplete called with path: " + path);
            mCurrentVideoPath = null; // Clear path as it's successfully completed
            if (path == null) {
                Log.e(TAG, "videoCb.onComplete: path is NULL!");
                runOnUiThread(() -> {
                    mIsRecording = false;
                    mRecordButton.setImageResource(android.R.drawable.ic_media_play);
                    Toast.makeText(MainActivity.this, "Record complete but path is null.", Toast.LENGTH_LONG).show();
                });
                return;
            }
            File tempFile = new File(path);
            if (tempFile.exists() && tempFile.length() > 0) {
                Log.d(TAG, "Temporary file exists and is not empty. Size: " + tempFile.length() + " bytes. Path: " + path);
            } else if (tempFile.exists()) {
                Log.w(TAG, "Temporary file exists but is EMPTY. Path: " + path);
            } else {
                Log.e(TAG, "Temporary file DOES NOT EXIST at path: " + path);
            }

            copyToFolder(path);

            runOnUiThread(() -> {
                mIsRecording = false;
                mRecordButton.setImageResource(android.R.drawable.ic_media_play);
                Log.d(TAG, "onComplete: UI updated. mIsRecording set to false.");
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mCameraViewContainer = findViewById(R.id.camera_preview);
        mStatusTextView = findViewById(R.id.status_text);
//        mCaptureButton = findViewById(R.id.capture_button);
        mRecordButton = findViewById(R.id.record_button);

        mCameraView = new AspectRatioTextureView(this);
        mCameraViewContainer.addView(mCameraView);

//        mCaptureButton.setOnClickListener(v -> {
//            if (mCamera != null && !mIsCapturing) capturePhoto();
//        });
        mRecordButton.setOnClickListener(v -> {
            if (mIsRecording) stopVideoCapture();
            else startVideoCapture();
        });

        mTempSaveDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES).getAbsolutePath();
        Log.d(TAG, "Temporary save directory: " + mTempSaveDir);
        SharedPreferences prefs = getSharedPreferences("uvc_prefs", MODE_PRIVATE);
        String treeStr = prefs.getString("video_folder_uri", null);
        if (treeStr == null) {
            Log.i(TAG, "No saved SAF URI found, prompting user to pick folder.");
            pickFolder();
        } else {
            mTreeUri = Uri.parse(treeStr);
            Log.i(TAG, "Loaded saved SAF URI: " + mTreeUri.toString());
        }

        if (checkAndRequestPermissions()) {
            initializeCamera();
        }
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        Toast.makeText(this, "Please select a folder to save videos.", Toast.LENGTH_LONG).show();
        startActivityForResult(intent, REQUEST_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK) {
            Uri tree = data.getData();
            if (tree != null) {
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(tree, takeFlags);

                mTreeUri = tree;
                getSharedPreferences("uvc_prefs", MODE_PRIVATE).edit()
                        .putString("video_folder_uri", tree.toString()).apply();
                Log.i(TAG, "User selected SAF URI: " + mTreeUri.toString());
                Toast.makeText(this, "Output folder selected: " + getFileNameFromTreeUri(tree), Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "REQUEST_PICK_FOLDER result OK, but data or URI is null.");
                Toast.makeText(this, "Failed to select folder.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getFileNameFromTreeUri(Uri treeUri) {
        DocumentFile documentFile = DocumentFile.fromTreeUri(this, treeUri);
        if (documentFile != null && documentFile.getName() != null) {
            return documentFile.getName();
        }
        return "Selected Folder";
    }


    private boolean checkAndRequestPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!need.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + need);
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQUEST_PERMISSIONS);
            return false;
        }
        Log.d(TAG, "All required permissions already granted.");
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                } else {
                    Log.d(TAG, "Permission granted: " + permissions[i]);
                }
            }
            if (allGranted) {
                Log.d(TAG, "All permissions granted by user.");
                initializeCamera();
            } else {
                Toast.makeText(this, "Permissions are required to use the camera.", Toast.LENGTH_LONG).show();
                Log.e(TAG, "Not all permissions were granted.");
            }
        }
    }

    private void initializeCamera() {
        Log.d(TAG, "Initializing camera...");
        mCameraClient = new MultiCameraClient(this, new IDeviceConnectCallBack() {
            @Override
            public void onAttachDev(UsbDevice device) {
                if (device != null) {
                    Log.d(TAG, "USB Device Attached: " + device.getDeviceName());
                    mCameraClient.requestPermission(device);
                } else {
                    Log.w(TAG, "onAttachDev: device is null");
                }
            }

            @Override
            public void onDetachDec(UsbDevice device) {
                if (device != null) {
                    Log.d(TAG, "USB Device Detached: " + device.getDeviceName());
                    if (mCamera != null && device.equals(mCamera.getUsbDevice())) {
                        closeCamera();
                    }
                } else {
                    Log.w(TAG, "onDetachDec: device is null");
                }
                runOnUiThread(() -> {
//                    mCaptureButton.setEnabled(false);
                    mRecordButton.setEnabled(false);
                    mStatusTextView.setText(R.string.status_no_device);
                });
            }

            @Override
            public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock ctrl) {
                if (device == null || ctrl == null) {
                    Log.e(TAG, "onConnectDev: device or ctrl is null. Cannot connect.");
                    return;
                }
                Log.d(TAG, "USB Device Connected: " + device.getDeviceName());
                closeCamera();

                mCamera = new CameraUVC(MainActivity.this, device);
                mCamera.setUsbControlBlock(ctrl);
                mCamera.setCameraStateCallBack(MainActivity.this);

                CameraRequest req = new CameraRequest.Builder()
                        .setPreviewWidth(DEFAULT_WIDTH)
                        .setPreviewHeight(DEFAULT_HEIGHT)
                        .create();
                Log.d(TAG, "Opening camera with request: " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT);
                mCamera.openCamera(mCameraView, req);
            }

            @Override
            public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock ctrl) {
                if (device != null) {
                    Log.d(TAG, "USB Device Disconnected: " + device.getDeviceName());
                } else {
                    Log.w(TAG, "onDisConnectDec: device is null");
                }
                closeCamera();
                runOnUiThread(() -> {
//                    mCaptureButton.setEnabled(false);
                    mRecordButton.setEnabled(false);
                    mStatusTextView.setText(R.string.status_no_device);
                });
            }

            @Override
            public void onCancelDev(UsbDevice device) {
                if (device != null) {
                    Log.w(TAG, "USB Permission Canceled for device: " + device.getDeviceName());
                } else {
                    Log.w(TAG, "onCancelDev: device is null");
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Permission denied for USB device.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void startVideoCapture() {
        if (mCamera == null) {
            Toast.makeText(this, "Camera not available.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "startVideoCapture: mCamera is null.");
            return;
        }
        if (mTreeUri == null) {
            Toast.makeText(this, "Please select an output folder first.", Toast.LENGTH_LONG).show();
            pickFolder();
            return;
        }

        try {
            String name = "UVC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".mp4";
            mCurrentVideoPath = mTempSaveDir + File.separator + name; // <<<< SET mCurrentVideoPath
            Log.d(TAG, "Starting video capture. Temp path: " + mCurrentVideoPath);

            File tempDirFile = new File(mTempSaveDir);
            if (!tempDirFile.exists()) {
                if (tempDirFile.mkdirs()) {
                    Log.d(TAG, "Created temp save directory: " + mTempSaveDir);
                } else {
                    Log.e(TAG, "Failed to create temp save directory: " + mTempSaveDir);
                    Toast.makeText(this, "Error: Cannot create temp directory.", Toast.LENGTH_LONG).show();
                    mCurrentVideoPath = null; // Clear on failure
                    return;
                }
            }

            mCamera.captureVideoStart(videoCb, mCurrentVideoPath, 0L);
            //mCamera.captureVideoStart(videoCb, mCurrentVideoPath, 0L, null);
            mIsRecording = true;
            mRecordButton.setImageResource(android.R.drawable.ic_media_pause);
            Log.i(TAG, "Video recording started.");
        } catch (Exception e) {
            Log.e(TAG, "Cannot start recording", e);
            Toast.makeText(this, "Cannot start recording: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            mIsRecording = false;
            mRecordButton.setImageResource(android.R.drawable.ic_media_play);
            mCurrentVideoPath = null; // Clear on failure
        }
    }

    private void stopVideoCapture() {
        if (mCamera == null) {
            Log.w(TAG, "stopVideoCapture: mCamera is null.");
            return;
        }
        try {
            Log.d(TAG, "Stopping video capture.");
            mCamera.captureVideoStop();
            Log.i(TAG, "Video recording stop requested.");
        } catch (Exception e) {
            Log.e(TAG, "Cannot stop recording", e);
            Toast.makeText(this, "Cannot stop recording: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            mIsRecording = false;
            mRecordButton.setImageResource(android.R.drawable.ic_media_play);
            mCurrentVideoPath = null; // Clear path if stop itself fails badly
        }
    }

    private void copyToFolder(String localPath) {
        if (localPath == null) { // <<<< ADDED: Check if localPath is null
            Log.e(TAG, "copyToFolder: localPath is null. Cannot copy.");
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: Temp file path missing.", Toast.LENGTH_LONG).show());
            return;
        }
        if (mTreeUri == null) {
            Log.w(TAG, "copyToFolder: mTreeUri is null. Cannot copy.");
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: Output folder not selected.", Toast.LENGTH_LONG).show());
            return;
        }
        File localFile = new File(localPath);
        if (!localFile.exists()) {
            Log.e(TAG, "copyToFolder: Local file does not exist at: " + localPath);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: Temp file missing for copy.", Toast.LENGTH_LONG).show());
            return;
        }
        if (localFile.length() == 0) {
            Log.e(TAG, "copyToFolder: Local file is empty. Path: " + localPath);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: Temp file is empty.", Toast.LENGTH_LONG).show());
            return;
        }

        String name = localFile.getName();
        Log.d(TAG, "copyToFolder: Attempting to copy '" + name + "' ("+localFile.length()+" bytes) from '" + localPath + "' to SAF URI: " + mTreeUri.toString());

        DocumentFile tree = DocumentFile.fromTreeUri(this, mTreeUri);
        if (tree == null || !tree.canWrite()) {
            Log.e(TAG, "copyToFolder: Cannot write to selected SAF tree URI (null or can't write). URI: " + mTreeUri.toString());
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: Cannot write to output folder.", Toast.LENGTH_LONG).show());
            return;
        }

        DocumentFile dest = null;
        OutputStream out = null;
        InputStream in = null;
        boolean success = false;
        try {
            dest = tree.createFile("video/mp4", name);
            if (dest == null || !dest.canWrite()) {
                Log.e(TAG, "copyToFolder: Failed to create destination file in SAF tree, or cannot write. Dest URI: " + (dest != null ? dest.getUri().toString() : "null"));
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: Could not create file in output folder.", Toast.LENGTH_LONG).show());
                return;
            }
            Log.d(TAG, "copyToFolder: Destination SAF URI created: " + dest.getUri().toString());

            out = getContentResolver().openOutputStream(dest.getUri());
            in = new FileInputStream(localFile);

            if (out == null) {
                Log.e(TAG, "copyToFolder: getContentResolver().openOutputStream(dest.getUri()) returned null.");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: Could not open output stream for SAF.", Toast.LENGTH_LONG).show());
                return;
            }

            byte[] buf = new byte[8192];
            int len;
            long totalCopied = 0;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                totalCopied += len;
            }
            out.flush();
            success = true;
            Log.d(TAG, "copyToFolder: Successfully copied " + totalCopied + " bytes to " + dest.getUri().toString());
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Video saved: " + name, Toast.LENGTH_LONG).show());

        } catch (Exception ex) {
            Log.e(TAG, "copyToFolder: Copy process failed", ex);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Copy to folder failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            success = false;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (Exception e) {
                Log.e(TAG, "copyToFolder: Error closing streams", e);
            }
            if (!success && dest != null && dest.exists()) {
                Log.w(TAG, "copyToFolder: Deleting incomplete/failed SAF destination file: " + dest.getUri().toString());
                dest.delete();
            }
            if (localFile.exists()) {
                if(localFile.delete()) {
                    Log.d(TAG, "copyToFolder: Deleted temporary file: " + localPath);
                } else {
                    Log.w(TAG, "copyToFolder: Failed to delete temporary file: " + localPath);
                }
            }
        }
    }


    private File createPhotoFile() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "UVCCamera");
        if (!dir.exists()) {
            if(!dir.mkdirs()){
                Log.e(TAG, "Failed to create directory for photos: " + dir.getAbsolutePath());
                return null;
            }
        }
        File photoFile = new File(dir, "UVC_IMG_" + ts + ".jpg");
        Log.d(TAG, "Creating photo file at: " + photoFile.getAbsolutePath());
        return photoFile;
    }

    private void capturePhoto() {
        if (mCamera == null) {
            Toast.makeText(this, "Camera not ready for photo.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "capturePhoto: mCamera is null.");
            return;
        }
        mIsCapturing = true;
//        mCaptureButton.setEnabled(false);
        Log.d(TAG, "Attempting to capture photo.");

        new Thread(() -> {
            try {
                Thread.sleep(200);
                File f = createPhotoFile();
                if (f == null) {
                    Log.e(TAG, "Failed to create photo file object.");
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Error creating photo file.", Toast.LENGTH_SHORT).show();
//                        mCaptureButton.setEnabled(true);
                        mIsCapturing = false;
                    });
                    return;
                }
                mCamera.captureImage(new ICaptureCallBack() {
                    @Override
                    public void onBegin() {
                        Log.d(TAG, "captureImage.onBegin");
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "captureImage.onError: " + error);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Capture error: " + error, Toast.LENGTH_SHORT).show();
//                            mCaptureButton.setEnabled(true);
                            mIsCapturing = false;
                        });
                    }

                    @Override
                    public void onComplete(String path) {
                        Log.d(TAG, "captureImage.onComplete, path: " + path);
                        if (path != null) {
                            File imageFile = new File(path);
                            if (imageFile.exists()) {
                                Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                                Uri contentUri = Uri.fromFile(imageFile);
                                mediaScanIntent.setData(contentUri);
                                sendBroadcast(mediaScanIntent);
                                Log.d(TAG, "Image saved: " + path + ", sent media scan broadcast.");
                            }
                        }
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Photo saved: " + (path !=null ? new File(path).getName() : "Error"), Toast.LENGTH_LONG).show();
//                            mCaptureButton.setEnabled(true);
                            mIsCapturing = false;
                        });
                    }
                }, f.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Exception in capturePhoto thread", e);
                runOnUiThread(() -> {
//                    mCaptureButton.setEnabled(true);
                    mIsCapturing = false;
                });
            }
        }).start();
    }

    @Override
    public void onCameraState(MultiCameraClient.ICamera camera, State code, String msg) {
        Log.d(TAG, "onCameraState: Code = " + code + (msg != null ? ", Msg = " + msg : ""));
        runOnUiThread(() -> {
            switch (code) {
                case OPENED:
                    mStatusTextView.setText(R.string.status_device_connected);
//                    mCaptureButton.setEnabled(true);
                    mRecordButton.setEnabled(true);
                    break;
                case CLOSED:
                case ERROR:
                    mStatusTextView.setText(R.string.status_no_device);
//                    mCaptureButton.setEnabled(false);
                    mRecordButton.setEnabled(false);
                    break;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart called.");
        if (mCameraClient != null) {
            mCameraClient.register();
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop called.");
        if (mIsRecording) {
            Log.w(TAG, "onStop: Recording was in progress, stopping video capture.");
            stopVideoCapture();
        }
        if (mCameraClient != null) {
            mCameraClient.unRegister();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy called.");
        closeCamera();
        if (mCameraClient != null) {
            mCameraClient.destroy();
            mCameraClient = null;
        }
        super.onDestroy();
    }

    private void closeCamera() {
        Log.d(TAG, "closeCamera called.");
        if (mCamera != null) {
            try {
                if (mIsRecording) {
                    Log.w(TAG, "closeCamera: Forcing stop of video capture.");
                    mCamera.captureVideoStop();
                    mIsRecording = false;
                    mCurrentVideoPath = null; // Clear path
                    runOnUiThread(() -> {
                        if (mRecordButton != null) {
                            mRecordButton.setImageResource(android.R.drawable.ic_media_play);
                        }
                    });
                }
                mCamera.closeCamera();
                Log.i(TAG, "Camera closed successfully.");
            } catch (Exception e) {
                Log.e(TAG, "Error closing camera", e);
            } finally {
                mCamera = null;
            }
        }
    }
}