#!/bin/bash
# Starts the Flux TV test runner. First start: sets up the Android SDK in /data and registers with GitHub
# using RUNNER_TOKEN (from the repo's Settings → Actions → Runners → New self-hosted runner). After that the
# registration is kept in /data/runner, so restarts need no token.
set -e

REPO_URL=${REPO_URL:-https://github.com/ProfoundBandit86/Fluxtv}
NAME=${RUNNER_NAME:-unraid}

if [ ! -e /dev/kvm ]; then
  echo "!! /dev/kvm is missing. On Unraid: Settings → VM Manager → Enable VMs = Yes, then add '--device=/dev/kvm'"
  echo "!! to this container's Extra Parameters. Without it the Android emulator can't run."
fi

# Android SDK (kept in the volume).
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== Setting up the Android SDK in $ANDROID_HOME (first start only)"
  mkdir -p "$ANDROID_HOME/cmdline-tools" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME"
  cp -r /opt/cmdline-tools/latest "$ANDROID_HOME/cmdline-tools/latest"
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "emulator" "platforms;android-35" "build-tools;35.0.0" \
      "system-images;android-33;google_apis;x86_64" "system-images;android-30;android-tv;x86"
fi
mkdir -p ~/.android && ln -sfn "$ANDROID_AVD_HOME" ~/.android/avd

# The runner (kept in the volume so its registration survives restarts and updates).
if [ ! -d /data/runner ]; then cp -r /opt/runner-dist /data/runner; fi
cd /data/runner
if [ ! -f .runner ]; then
  if [ -z "$RUNNER_TOKEN" ]; then
    echo "!! Not registered yet: set RUNNER_TOKEN to the token GitHub shows under"
    echo "!! $REPO_URL/settings/actions/runners/new  (it's the value after --token)."
    sleep 600; exit 1
  fi
  ./config.sh --unattended --replace --url "$REPO_URL" --token "$RUNNER_TOKEN" \
      --name "$NAME" --labels "flux-emulator" --work /data/work
fi
echo "== Runner ready. Waiting for test runs."
exec ./run.sh
