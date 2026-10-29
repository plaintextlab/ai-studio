# AI Studio for Android

A small Material 3 app for the studio's ComfyUI, in the style of Grok: describe an
image and get it, or add a photo (or tap **Edit this** on a result) and say what to
change. Results can be saved to the phone's gallery (Pictures/AI Studio).

It runs the same workflow as **3 - Quick image**: the studio's prompt enhancer first
rewrites your prompt in detail (switch it off in settings), then the best model for it is
picked on the server (Z-Image Turbo for realistic people, Lustify for adult content,
Animagine for anime, FLUX.2 Klein for edits and everything else). Under each image the app
shows the model used and the enhanced prompt.

## Use
1. On the PC: `./studio.sh start comfyui --lan` and note the printed address.
2. On the phone (same Wi-Fi): install the APK from the repository's
   [Releases](https://github.com/plaintextlab/ai-studio/releases) page (or a local
   build, `outputs/android/AI-Studio.apk`), allowing installs from that source when
   asked. Open it and enter that address, e.g. `192.168.0.10:8188`;
   **Test connection** checks it.

Android 10 or newer. Images go through plain HTTP on your home network, like the
ComfyUI page itself, and there is no password: use it only on a network you trust.

## Build
```bash
android/build.sh
```
The first build downloads a pinned JDK 17, the Android command-line tools, the SDK
packages and Gradle into `cache/android` (about 1.5 GB) and asks you to accept the
Android SDK license. It writes `outputs/android/AI-Studio.apk`, a release build signed
with the key in `data/android/release.properties` (the debug key when that file is
missing), so a new build installs over the old one on the phone.

## Releases
Pushing a tag like `v1.2.0` runs `.github/workflows/android-release.yml`, which builds
the APK and attaches it to the GitHub release of that tag (version 1.2.0). It signs
with the same release key, kept in the repository's Actions secrets
(`SIGNING_KEYSTORE_BASE64` = the keystore in base64, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). Without them it uses a throwaway key, and
installing that release needs the old app removed first.
