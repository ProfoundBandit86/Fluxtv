# Flux TV test runner for a home server (Unraid or any Linux box with KVM).
# A GitHub Actions "self-hosted runner" with what Flux's emulator tests need. The Android SDK, emulator and system
# images download on first use into /data (a volume), so they're kept between runs and the image stays small.
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/data/android-sdk \
    ANDROID_SDK_ROOT=/data/android-sdk \
    ANDROID_AVD_HOME=/data/avd \
    GRADLE_USER_HOME=/data/gradle \
    RUNNER_ALLOW_RUNASROOT=1 \
    LANG=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget git unzip zip jq sudo tzdata \
        openjdk-17-jdk-headless python3 ffmpeg samba \
        libpulse0 libgl1 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libasound2 \
        libx11-xcb1 libxkbfile1 libdbus-1-3 libtcmalloc-minimal4 cpu-checker \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools (copied into the SDK volume on first start).
RUN mkdir -p /opt/cmdline-tools && cd /opt/cmdline-tools \
    && curl -fsSL -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q tools.zip && rm tools.zip && mv cmdline-tools latest

# The GitHub Actions runner (latest release).
RUN mkdir -p /opt/runner-dist && cd /opt/runner-dist \
    && V=$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest | jq -r .tag_name | sed 's/^v//') \
    && curl -fsSL -o r.tgz "https://github.com/actions/runner/releases/download/v${V}/actions-runner-linux-x64-${V}.tar.gz" \
    && tar xzf r.tgz && rm r.tgz && ./bin/installdependencies.sh

COPY start.sh /start.sh
RUN chmod +x /start.sh

VOLUME ["/data"]
LABEL org.opencontainers.image.source="https://github.com/ProfoundBandit86/Fluxtv" \
      org.opencontainers.image.description="Flux TV emulator test runner"
ENTRYPOINT ["/start.sh"]
