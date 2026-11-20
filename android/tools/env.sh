# Build environment: Gradle and AGP need a JDK older than Homebrew's default.
# Usage: source tools/env.sh
if [ -z "${MARGINVOICE_JAVA_HOME:-}" ]; then
  for candidate in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17; do
    if [ -d "$candidate/libexec/openjdk.jdk/Contents/Home" ]; then
      MARGINVOICE_JAVA_HOME="$candidate/libexec/openjdk.jdk/Contents/Home"
      break
    fi
  done
fi
[ -n "${MARGINVOICE_JAVA_HOME:-}" ] && export JAVA_HOME="$MARGINVOICE_JAVA_HOME"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
