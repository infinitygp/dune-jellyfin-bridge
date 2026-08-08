# Dune Jellyfin Bridge

Android bridge that launches the original Dune HD video player and returns the
final playback position to Jellyfin Android TV.

## Why it is needed

Jellyfin starts external players with `startActivityForResult()` and expects
`RESULT_OK` plus a numeric `position` or `extra_position` extra in milliseconds.
The Dune `com.dunehd.shell.PlayerProxyActivity` forwards playback to
`MainActivity` in a new task and finishes without returning a result. Jellyfin
therefore sees a cancelled playback with no position.

The bridge leaves the signed system player untouched. It forwards Jellyfin's
video intent to Dune, applies Jellyfin's initial position with Dune's local IP
Control API, polls the same API while playback is active, and returns the last
observed position when the user exits Dune.

## Requirements

- JDK 21 (declared in `.mise.toml`)
- Android SDK command-line tools 22.0 (declared in `.mise.toml`)
- Android SDK Platform 36 and current Build Tools (installed with `sdkmanager`)
- ADB for installation and diagnostics

Install the declared JDK and build:

```sh
mise install
sdkmanager \
  'platforms;android-36' \
  'build-tools;36.0.0' \
  'platform-tools'
./gradlew test lintDebug assembleDebug
```

Install the debug APK on Dune:

```sh
adb connect 192.168.1.181:5555
adb -s 192.168.1.181:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

In Jellyfin, enable the external player. When Android asks which player should
handle the video, select **Dune Player (Jellyfin Bridge)**. The bridge will open
the original Dune player automatically.

## Verification

While a video is playing, Dune's status can be inspected without changing its
state:

```sh
curl 'http://192.168.1.181/cgi-bin/do?cmd=status&result_syntax=json'
```

After leaving the Dune player, inspect bridge logs:

```sh
adb -s 192.168.1.181:5555 logcat -d -s DuneJellyfinBridge:I
```

Jellyfin 0.19.9 reports the returned position to the server only after the
external player closes. Live dashboard progress would additionally require a
Jellyfin-side integration; Android activity results cannot stream intermediate
updates.

## Local evidence

Original APKs pulled from the test device are kept under `artifacts/original/`
and ignored by Git because they are vendor binaries. The bridge was designed
against Dune firmware `250815_1012_r22` and Jellyfin Android TV `0.19.9`.
