#!/usr/bin/env bash
# Run a command with the same JDK 21 in Maven and the CLI.
set -euo pipefail

select_jdk() {
  local java_command="$1" settings detected_jdk specification compiler
  settings=$("$java_command" -XshowSettings:properties -version 2>&1) || return 1
  specification=$(printf '%s\n' "$settings" | sed -n 's/^[[:space:]]*java.specification.version = //p')
  [ "$specification" = 21 ] || return 1
  detected_jdk=$(printf '%s\n' "$settings" | sed -n 's/^[[:space:]]*java.home = //p')
  [ -x "$detected_jdk/bin/java" ] && [ -x "$detected_jdk/bin/javac" ] || return 1
  compiler=$("$detected_jdk/bin/javac" -version 2>&1) || return 1
  case "$compiler" in
    'javac 21'|'javac 21.'*|'javac 21-'*) ;;
    *) return 1 ;;
  esac
  jdk_dir="$detected_jdk"
}

jdk_dir=""
if [ -n "${JDK21:-}" ]; then
  if ! select_jdk "$JDK21/bin/java"; then
    echo "JDK21 must point to a Java 21 JDK with java and javac: $JDK21" >&2
    exit 1
  fi
elif [ -n "${JAVA_HOME:-}" ] && select_jdk "$JAVA_HOME/bin/java"; then
  :
elif select_jdk java; then
  :
elif [ "$(uname -s)" = Darwin ] && [ -x /usr/libexec/java_home ] && \
     mac_jdk=$(/usr/libexec/java_home -v 21 2>/dev/null) && select_jdk "$mac_jdk/bin/java"; then
  :
else
  echo "Java JDK 21 was not found. Set JAVA_HOME to its installation directory or put its bin directory on PATH." >&2
  exit 1
fi

export JAVA_HOME="$jdk_dir"
export PATH="$JAVA_HOME/bin:$PATH"
exec "$@"
