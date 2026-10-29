# AI Studio for Android

A small Material 3 app for the studio's ComfyUI, in the style of Grok: describe an
image and get it, or add a photo (or tap **Edit this** on a result) and say what to
change. Results can be saved to the phone's gallery (Pictures/AI Studio).

It runs the same model as the **3 - Quick image** workflow (FLUX.2 Klein 4B, with the
realism negative prompt), so the models ComfyUI downloads are all it needs.

## Use
1. On the PC: `./studio.sh start comfyui --lan` and note the printed address.
2. On the phone (same Wi-Fi): install `outputs/android/AI-Studio.apk` (allow installing
   from this source when asked), open it and enter that address, e.g.
   `192.168.0.10:8188`. **Test connection** checks it.

Android 10 or newer. Images go through plain HTTP on your home network, like the
ComfyUI page itself, and there is no password: use it only on a network you trust.

## Build
```bash
android/build.sh
```
The first build downloads a pinned JDK 17, the Android command-line tools, the SDK
packages and Gradle into `cache/android` (about 1.5 GB) and asks you to accept the
Android SDK license. It writes `outputs/android/AI-Studio.apk`. The signing key is kept
in `data/android`, so a new build installs over the old one on the phone.
