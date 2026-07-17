FROM mcr.microsoft.com/devcontainers/java:21-bookworm

RUN apt-get update && apt-get install -y wget unzip usbutils && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/android-sdk /opt/kotlin && chown -R vscode:vscode /opt/android-sdk /opt/kotlin

USER vscode
ENV ANDROID_HOME=/opt/android-sdk
ENV KOTLIN_HOME=/opt/kotlin
ENV JAVA_HOME=/usr/local/sdkman/candidates/java/current
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${KOTLIN_HOME}/bin

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted \
    && mv /tmp/cmdline-tools-extracted/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extracted

RUN yes | sdkmanager --licenses \
    && sdkmanager "platform-tools" \
                  "platforms;android-34" \
                  "platforms;android-35" \
                  "build-tools;34.0.0" \
                  "build-tools;35.0.0"

RUN wget -q https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip -O /tmp/kotlin.zip \
    && unzip -q /tmp/kotlin.zip -d /tmp/kotlin-extracted \
    && mv /tmp/kotlin-extracted/kotlinc/* /opt/kotlin/ \
    && rm -rf /tmp/kotlin.zip /tmp/kotlin-extracted
