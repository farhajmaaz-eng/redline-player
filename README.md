# Redline Player

A minimal Android MP3 player with a black and red interface.

## Features

- Choose local audio files with Android's system picker
- Play and pause MP3 audio
- Seek through a track
- Skip back 10 seconds or forward 30 seconds
- Remembers the last selected track
- No account, ads, or network permission

## Build

Open this directory in Android Studio and run the `app` configuration, or use:

```bash
gradle :app:assembleDebug
```

The installable APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The GitHub Actions workflow also builds the debug APK and uploads it as a workflow artifact.
