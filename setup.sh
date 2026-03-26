#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# setup-android-sdk.sh  —  YukariLauncher build environment
# Matches build.gradle.kts exactly:
#   compileSdk      = 34
#   buildToolsVersion = "34.0.0"
#   ndkVersion      = "25.2.9519653"
#   minSdk          = 26
#   Java            = 17
#
# CodeAnywhere / Ubuntu · Debian
# Usage:  bash setup-android-sdk.sh
# ═══════════════════════════════════════════════════════════════════════════════

set -e

ANDROID_HOME="$HOME/android-sdk"
COMPILE_SDK="34"
BUILD_TOOLS="34.0.0"
NDK_VERSION="25.2.9519653"           # Exact version from build.gradle.kts
CMDLINE_TOOLS_ID="11076708"          # cmdline-tools 11.0

echo "════════════════════════════════════════════════"
echo "  YukariLauncher — SDK + NDK Setup"
echo "  SDK dir : $ANDROID_HOME"
echo "  NDK     : $NDK_VERSION"
echo "════════════════════════════════════════════════"

# ── 2. cmdline-tools ──────────────────────────────────────────────────────────
echo ""
echo "▶ [2/6] Downloading Android cmdline-tools..."
mkdir -p "$ANDROID_HOME/cmdline-tools"

wget -q --show-progress \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_ID}_latest.zip" \
    -O /tmp/cmdline-tools.zip

unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
rm /tmp/cmdline-tools.zip

# Google ships as "cmdline-tools/cmdline-tools/" — must rename to "latest"
[ -d "$ANDROID_HOME/cmdline-tools/cmdline-tools" ] && \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

echo "    Done."

# ── 3. Export env vars ────────────────────────────────────────────────────────
echo ""
echo "▶ [3/6] Setting environment variables..."

export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

EXPORT_BLOCK="
# ── Android SDK / NDK (YukariLauncher) ───────────────────
export ANDROID_HOME=\"$ANDROID_HOME\"
export ANDROID_SDK_ROOT=\"$ANDROID_HOME\"
export ANDROID_NDK_HOME=\"$ANDROID_HOME/ndk/$NDK_VERSION\"
export JAVA_HOME=\"$JAVA_HOME\"
export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$JAVA_HOME/bin:\$PATH\"
# ─────────────────────────────────────────────────────────
"
grep -q "ANDROID_HOME" ~/.bashrc  || echo "$EXPORT_BLOCK" >> ~/.bashrc
grep -q "ANDROID_HOME" ~/.profile || echo "$EXPORT_BLOCK" >> ~/.profile
echo "    Written to ~/.bashrc"

# ── 4. Accept licenses ────────────────────────────────────────────────────────
echo ""
echo "▶ [4/6] Accepting SDK licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true
echo "    Done."

# ── 5. Install SDK + NDK packages ─────────────────────────────────────────────
echo ""
echo "▶ [5/6] Installing SDK packages..."
sdkmanager --install \
    "platform-tools" \
    "platforms;android-${COMPILE_SDK}" \
    "build-tools;${BUILD_TOOLS}"

echo "    ✓ platform-tools"
echo "    ✓ platforms;android-$COMPILE_SDK"
echo "    ✓ build-tools;$BUILD_TOOLS"

echo ""
echo "    Installing NDK $NDK_VERSION (this is ~500 MB, takes a few minutes)..."
sdkmanager --install "ndk;${NDK_VERSION}"
echo "    ✓ ndk;$NDK_VERSION"

# ── 6. Write local.properties ─────────────────────────────────────────────────
echo ""
echo "▶ [6/6] Writing local.properties..."

for dir in \
    "$HOME/YukariLauncher" \
    "$HOME/workspace/YukariLauncher" \
    "/workspace/YukariLauncher" \
    "$(pwd)"
do
    if [ -f "$dir/settings.gradle.kts" ] || [ -f "$dir/settings.gradle" ]; then
        cat > "$dir/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$ANDROID_HOME/ndk/$NDK_VERSION
EOF
        echo "    Written → $dir/local.properties"
        break
    fi
done

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════"
echo "  ✓  All done!"
echo ""
echo "  SDK → $ANDROID_HOME"
echo "  NDK → $ANDROID_HOME/ndk/$NDK_VERSION"
echo ""
echo "  Next steps:"
echo "    source ~/.bashrc"
echo "    cd ~/YukariLauncher"
echo "    chmod +x gradlew"
echo "    ./gradlew :YukariLauncher:assembleDebug"
echo ""
echo "  APK output:"
echo "    YukariLauncher/build/outputs/apk/debug/"
echo "════════════════════════════════════════════════"
