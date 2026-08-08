# Dune Jellyfin Bridge

Dune Jellyfin Bridge lets Jellyfin Android TV use the original Dune HD player
while keeping resume and watched progress working. When an NFS copy of the media
is available, the bridge also opens it directly so the Dune player can discover
adjacent subtitle files.

## Install

1. Install the official **Jellyfin for Android TV** application from Google Play
   on the Dune HD player.
2. Download `dune-jellyfin-bridge-vX.Y.Z.apk` from the
   [latest release](https://github.com/infinitygp/dune-jellyfin-bridge/releases/latest).
   Do not download the `.sha256` file instead of the APK.
3. Install the APK on the Dune. You can copy it to the device and open it with
   the Android package installer, or install it over ADB:

   ```sh
   adb connect <DUNE_IP>:5555
   adb -s <DUNE_IP>:5555 install -r dune-jellyfin-bridge-vX.Y.Z.apk
   ```

   Replace `<DUNE_IP>` with the Dune's IP address, for example
   `192.168.1.100`. If Android asks, allow installation from unknown sources for
   the application opening the APK.
4. In Jellyfin, open the playback settings and enable the external video
   player.
5. Start a video. In Android's player chooser select
   **Dune Player (Jellyfin Bridge)** and choose **Always**. The bridge will open
   the original Dune player automatically.

To update, download the APK from a newer release and install it over the current
version. If an older debug or differently signed build is installed, Android may
report `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Remove that build first and then
install the release APK:

```sh
adb -s <DUNE_IP>:5555 uninstall dev.dunehd.jellyfinbridge
adb -s <DUNE_IP>:5555 install dune-jellyfin-bridge-vX.Y.Z.apk
```

Uninstalling resets Android's saved player choice, so select the bridge again on
the next playback.

## NFS playback and subtitles

Direct NFS playback is recommended. The original media must be available
through an NFS share already configured and mounted in Dune. No
Jellyfin-to-NFS path mapping needs to be configured in the bridge: before each
playback it locates the file in Dune's active NFS mounts using its filename and,
when available, its size.

Keep external subtitles next to the media and name them in a form recognized by
Dune, for example:

```text
Episode.mkv
Episode.pl.srt
```

The bridge opens a matching file through Dune's native `nfs://`, `nfs-tcp://`,
or `nfs-udp://` URL. If it cannot find one, it falls back to Jellyfin's HTTP
URL. Resume and returned progress still work over HTTP, but adjacent NFS
subtitle files are then unavailable to the player.

## Playback behavior

The bridge forwards Jellyfin's video intent to Dune, repeatedly applies the
requested resume position while the media is loading, and tracks playback
through Dune's local IP Control API. When the user exits the Dune player, the
last observed position is returned to Jellyfin.

Jellyfin Android TV reports this position to the server after the external
player closes. It does not show live progress from the bridge in the Jellyfin
dashboard while the video is playing.

The current implementation was tested with:

- Dune HD 8K Pro Plus, firmware `250815_1012_r22`
- Jellyfin for Android TV `0.19.9`

## Development

### Requirements

- JDK 21 (declared in `.mise.toml`)
- Android SDK command-line tools 22.0 (declared in `.mise.toml`)
- Android SDK Platform 36 and Build Tools 36.0.0
- ADB for installation and diagnostics

Install the declared tools and build a debug APK:

```sh
mise install
sdkmanager \
  'platforms;android-36' \
  'build-tools;36.0.0' \
  'platform-tools'
./gradlew test lintDebug assembleDebug
```

Install the debug build:

```sh
adb connect <DUNE_IP>:5555
adb -s <DUNE_IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release builds

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

### Diagnostics

While a video is playing, Dune's status can be inspected without changing its
state:

```sh
curl 'http://<DUNE_IP>/cgi-bin/do?cmd=status&result_syntax=json'
```

After leaving the Dune player, inspect bridge logs:

```sh
adb -s <DUNE_IP>:5555 logcat -d -s DuneJellyfinBridge:I
```

Original APKs pulled from the test device are kept under `artifacts/original/`
and ignored by Git because they are vendor binaries.
