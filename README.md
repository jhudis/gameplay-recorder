# Gameplay Recorder

A barebones Android app that records portrait gameplay videos with a facecam.

Each recording is a 1080×1920, 30 fps MP4:

- **Top third:** the front camera, cropped to its vertical center.
- **Bottom two thirds:** the screen, scaled to full width with its top cropped off.
- **Audio:** game/media audio and the microphone, mixed into one track.

While recording, the cropped camera is shown on screen in the band just above the recorded area,
so you can see exactly what's being captured. That band is cropped out of the video, so the preview
never appears in it.

Recordings are saved to `Movies/GameplayRecorder`.

## Requirements

- Android 16 (API 36) or newer
- A front-facing camera

## Permissions

- **Camera** and **Microphone**: the facecam and your voice
- **Screen capture**: approved each time you start recording
- **Display over other apps**: the camera preview overlay, granted once in Settings
- **Notifications**: the recording notification, which has a Stop button

## Usage

1. Tap **Start recording** and grant the permissions.
2. Switch to your game.
3. Stop from the app, the notification, or the system's screen-recording indicator.

## Limitations

- Assumes games run in portrait.
- Games that block audio capture record without game audio; games that block screenshots record as black.
- Use headphones, or the microphone will pick up game audio from the speaker.

## Building

Open the project in Android Studio, or run:

```
./gradlew assembleDebug
```
