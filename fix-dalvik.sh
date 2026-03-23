#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# fix-rebuild.sh
# 1. Removes the broken --patch-module block from build.gradle.kts
# 2. Deletes CriticalNative.java (the stub the Android SDK already provides)
# 3. Cleans build cache
#
# Run from repo root: /workspaces/YukariLauncher
# bash fix-rebuild.sh
# ═══════════════════════════════════════════════════════════════════════════════

set -e

BUILD_GRADLE="YukariLauncher/build.gradle.kts"
CRITICAL_NATIVE="YukariLauncher/src/main/java/dalvik/annotation/optimization/CriticalNative.java"

# ── Step 1: Remove the broken --patch-module block ────────────────────────────
echo "▶ [1/3] Removing broken --patch-module block from build.gradle.kts..."

if grep -q "patch-module" "$BUILD_GRADLE"; then
    # Remove from the comment line down to the closing brace of the tasks block
    python3 - <<'PYEOF'
import re

with open("YukariLauncher/build.gradle.kts", "r") as f:
    content = f.read()

# Remove the entire block we added: from the comment to the closing }
pattern = r"\n// ── Fix: Java 17 module system.*?^\}\n"
cleaned = re.sub(pattern, "", content, flags=re.DOTALL | re.MULTILINE)

with open("YukariLauncher/build.gradle.kts", "w") as f:
    f.write(cleaned)

print("    Removed --patch-module block.")
PYEOF
else
    echo "    Not found — already clean."
fi

# ── Step 2: Delete CriticalNative.java ───────────────────────────────────────
echo ""
echo "▶ [2/3] Deleting CriticalNative.java stub..."

if [ -f "$CRITICAL_NATIVE" ]; then
    rm "$CRITICAL_NATIVE"
    # Remove the now-empty directory if nothing else is in it
    rmdir "$(dirname "$CRITICAL_NATIVE")" 2>/dev/null || true
    echo "    Deleted: $CRITICAL_NATIVE"
else
    echo "    Not found at: $CRITICAL_NATIVE"
    # Search for it anywhere in the tree
    FOUND=$(find YukariLauncher/src -name "CriticalNative.java" 2>/dev/null)
    if [ -n "$FOUND" ]; then
        echo "    Found at: $FOUND — deleting..."
        rm "$FOUND"
        rmdir "$(dirname "$FOUND")" 2>/dev/null || true
        echo "    Deleted."
    fi
fi

# ── Step 3: Clean build cache ─────────────────────────────────────────────────
echo ""
echo "▶ [3/3] Cleaning build cache..."
./gradlew clean --no-daemon -q
echo "    Clean done."

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════"
echo "  ✓ Fixed. Now rebuild with:"
echo ""
echo "  ./gradlew YukariLauncher:assembleDebug -Darch=arm64 --no-daemon"
echo "════════════════════════════════════════════════"