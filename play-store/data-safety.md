# Google Play Data Safety Draft for i-Sight Med

This draft is based on the current app state after removing login, internet access, cloud services, ads, and analytics.

## Data Collection

Does the app collect or share user data?

Suggested answer: No, the app does not collect or share user data.

Rationale: The app processes camera preview, photos, videos, and audio locally on the device. It does not include internet permission, account login, ads, analytics, or cloud upload.

## Data Sharing

Suggested answer: No user data is shared with third parties.

## Security Practices

Data encrypted in transit:

Suggested answer: Not applicable if no user data is transmitted off device.

Users can request data deletion:

Suggested answer: Not applicable for server-side data because no user data is collected by the developer. Users can delete locally saved photos and videos from their device.

## Permissions and Sensitive Access

Declare that the app uses these permissions/features for app functionality:

- Camera: preview and capture from a connected USB camera.
- Microphone: record video with audio from the device microphone.
- Storage/media/document access: save and open locally captured photos/videos.
- USB host: connect to compatible USB/UVC cameras.

## Important Future Update Rule

If ads, analytics, crash reporting, cloud upload, account login, remote support, or any third-party SDK that transmits data is added later, this Data Safety form and privacy policy must be updated before publishing that version.
