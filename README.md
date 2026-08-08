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
observed position when the user exits Dune. The initial seek is retried until
the expected media reports the requested position, avoiding early seek commands
being lost while large files are still initializing.

Before every playback, the bridge tries to locate the same media file in Dune's
active NFS mounts using its filename and, when available, its size. It then opens
that file through Dune's native `nfs://`, `nfs-tcp://`, or `nfs-udp://` URL. The
original Jellyfin HTTP URL is retained as a fallback when no matching NFS file
is available. Direct NFS playback also lets the native player discover adjacent
subtitle files while preserving Jellyfin's title and resume position.

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

## Releases

Release APKs are signed with a project-specific key. Keep the keystore and its
passwords outside the repository, restrict their permissions, and maintain an
independent backup. Losing the signing key prevents future APKs from updating
existing installations.

Local release builds use these environment variables:

- `ANDROID_RELEASE_KEYSTORE`
- `ANDROID_RELEASE_STORE_PASSWORD` or `ANDROID_RELEASE_STORE_PASSWORD_FILE`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD` or `ANDROID_RELEASE_KEY_PASSWORD_FILE`

The `Release APK` GitHub Actions workflow uses corresponding repository secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Pushing a tag that exactly matches `v<versionName>`, for example `v0.3.0`, runs
the tests and release lint, builds and verifies the signed APK, and creates a
GitHub release containing the APK and its SHA-256 checksum.

In Jellyfin, enable the external player. When Android asks which player should
handle the video, select **Dune Player (Jellyfin Bridge)**. The bridge will open
the original Dune player automatically.

For direct playback, the original media file must also be available through an
NFS share already configured and mounted in Dune. Keep external subtitle files
next to the media using a name recognized by Dune, for example `Episode.pl.srt`
beside `Episode.mkv`. No Jellyfin-to-NFS path mapping needs to be configured in
the bridge. If no matching NFS file is found, playback falls back to Jellyfin's
HTTP URL; resume and returned progress still work.

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
