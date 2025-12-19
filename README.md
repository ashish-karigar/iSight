# My Lens for Android

This project demonstrates how to connect and preview a USB Video Class (UVC) camera on Android devices.

## Features

- Automatic detection of USB camera devices
- Requesting USB permissions automatically
- Real-time preview of camera feed
- Status indicators for device connection

## Requirements

- Android device with USB host support (Android 5.0+)
- UVC compatible camera
- Android Studio 4.2+

## Setup

1. Clone this repository
2. Open the project in Android Studio
3. Connect your UVC camera to your Android device using a USB OTG adapter if needed
4. Build and run the application on your device

## How it works

The app uses the [UVCCamera library](https://github.com/saki4510t/UVCCamera) by saki4510t to handle communication with UVC devices. It implements the following flow:

1. Monitors USB device connections
2. Requests permission to access the UVC device when connected
3. Opens a connection to the camera
4. Displays the camera feed on a texture view
5. Properly handles device disconnection

## Permissions

The application requires the USB Host feature and automatically requests permission to access UVC devices when they are connected.

## Known limitations

- Some UVC cameras may not be compatible or may require specific settings
- Performance may vary based on the device and camera specifications
- The default resolution is set to 640x480

## Acknowledgements

This project is based on the [UVCCamera library](https://github.com/saki4510t/UVCCamera) by saki4510t.

## License

MIT License 

## Building the apk file
Navigate to the root directory of your Android project (the folder containing gradlew and settings.gradle).
1. cd /path/to/your/project
2. Clean the project (optional but recommended)
   - ./gradlew clean
3. Build the APK
    - ./gradlew assembleDebug
    This will generate a debug APK.
    If you want a release APK, use:
    - ./gradlew assembleRelease
    Note: For release builds, you'll need to have signing configs set up in your build.gradle.
   
4. APK Output Location
    After build completes, your APK will be located at:
    app/build/outputs/apk/debug/app-debug.apk
    or for release:
    app/build/outputs/apk/release/app-release.apk

## Wireless pairing and connecting method for android debugging and testing:
- adb devices: lists all the connected devices
- adb pair [ip-(select pair device with pairing code under developer options in android settings)]: pairs ide with android device
- adb connect [IP address & Port]: Connects both the devices 

## Login
1. axk6361@psu.edu: Qwerty1@2