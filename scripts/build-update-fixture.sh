#!/bin/sh
set -eu
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${JAVA_HOME:?Set JAVA_HOME}"
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT
cat > "$fixture_dir/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.streampocket.devtest" android:versionCode="99999" android:versionName="9.0.0"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37"/><application android:label="Pocket update test" android:debuggable="true"/></manifest>
XML
"$ANDROID_HOME/build-tools/36.0.0/aapt2" link -o "$fixture_dir/unsigned.apk" --manifest "$fixture_dir/AndroidManifest.xml" -I "$ANDROID_HOME/platforms/android-37.0/android.jar"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$project_dir/app/src/androidTest/assets/update-fixture.apk" "$fixture_dir/unsigned.apk"
