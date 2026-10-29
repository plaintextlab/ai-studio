#!/usr/bin/env bash
# Builds the AI Studio Android app into outputs/android/AI-Studio.apk.
#
# Like the rest of the studio, nothing is installed on the host: a pinned JDK, the
# Android command-line tools, the SDK packages and Gradle are downloaded once into
# cache/android (checked against their checksums). The Android SDK needs its license
# accepted: on first use sdkmanager asks; ACCEPT_ANDROID_SDK_LICENSE=yes accepts it.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(dirname "$HERE")"
A="$ROOT/cache/android"

JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz"
JDK_SHA256="8682892fc02965930b9022c066fa164dd6f458ef4a5dc262016aa28333b30f49"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
GRADLE_SHA256="31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"
# build-tools 34: the version the Android Gradle plugin 8.7 uses by default.
SDK_PACKAGES=("platform-tools" "platforms;android-35" "build-tools;34.0.0")

log() { printf '==> %s\n' "$*" >&2; }

# fetch URL SHA256 FILE: downloads once into cache/android/dl and checks it.
fetch() {
    local dest="$A/dl/$3"
    mkdir -p "$A/dl"
    if [ ! -f "$dest" ] || ! echo "$2  $dest" | sha256sum -c --quiet 2>/dev/null; then
        log "Downloading $3"
        curl -fL --retry 3 -o "$dest.part" "$1"
        echo "$2  $dest.part" | sha256sum -c --quiet || { rm -f "$dest.part"; echo "Checksum mismatch: $3" >&2; exit 1; }
        mv "$dest.part" "$dest"
    fi
    echo "$dest"
}

if [ ! -x "$A/jdk/bin/java" ]; then
    rm -rf "$A/jdk"; mkdir -p "$A/jdk"
    # legal/ holds symlinks to license texts, which exFAT/NTFS cannot store; not needed to run.
    tar -xzf "$(fetch "$JDK_URL" "$JDK_SHA256" jdk17.tar.gz)" -C "$A/jdk" --strip-components=1 \
        --wildcards --exclude='*/legal/*'
fi
if [ ! -x "$A/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
    rm -rf "$A/sdk/cmdline-tools" "$A/tmp-cmdline"; mkdir -p "$A/sdk/cmdline-tools" "$A/tmp-cmdline"
    unzip -q "$(fetch "$CMDLINE_URL" "$CMDLINE_SHA256" cmdline-tools.zip)" -d "$A/tmp-cmdline"
    mv "$A/tmp-cmdline/cmdline-tools" "$A/sdk/cmdline-tools/latest"; rm -rf "$A/tmp-cmdline"
fi
if [ ! -x "$A/gradle/bin/gradle" ]; then
    rm -rf "$A/gradle" "$A/tmp-gradle"; mkdir -p "$A/tmp-gradle"
    unzip -q "$(fetch "$GRADLE_URL" "$GRADLE_SHA256" gradle.zip)" -d "$A/tmp-gradle"
    mv "$A/tmp-gradle"/gradle-* "$A/gradle"; rm -rf "$A/tmp-gradle"
fi

export JAVA_HOME="$A/jdk"
export ANDROID_HOME="$A/sdk"
# The debug signing key lives in data/ (kept across rebuilds and cache cleanups), so
# a newer build installs over an older one on the phone.
export ANDROID_USER_HOME="$ROOT/data/android"
export GRADLE_USER_HOME="$A/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -d "$ANDROID_HOME/licenses" ]; then
    log "The Android SDK needs its license accepted (https://developer.android.com/studio/terms)"
    # `yes` ends with SIGPIPE once sdkmanager stops reading, so check the result instead.
    if [ "${ACCEPT_ANDROID_SDK_LICENSE:-}" = yes ]; then yes | "$SDKMANAGER" --licenses >/dev/null || true
    else "$SDKMANAGER" --licenses || true; fi
    [ -d "$ANDROID_HOME/licenses" ] || { echo "The Android SDK license was not accepted." >&2; exit 1; }
fi
if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ] || [ ! -d "$ANDROID_HOME/platforms/android-35" ]; then
    log "Installing Android SDK packages"
    "$SDKMANAGER" "${SDK_PACKAGES[@]}" >/dev/null
fi

# Release build (smaller) signed with the release key when there is one; the same key
# is in the repository secrets, so local and GitHub builds update each other.
KEY="$ROOT/data/android/release.properties"
if [ -f "$KEY" ]; then
    set -a; . "$KEY"; set +a
fi
log "Building"
"$A/gradle/bin/gradle" --no-daemon --quiet -p "$HERE" \
    --project-cache-dir "$A/project-cache" assembleRelease
mkdir -p "$ROOT/outputs/android"
cp "$HERE/app/build/outputs/apk/release/app-release.apk" "$ROOT/outputs/android/AI-Studio.apk"
log "Done: outputs/android/AI-Studio.apk"
