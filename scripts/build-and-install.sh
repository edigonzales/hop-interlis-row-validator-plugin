#!/usr/bin/env bash
# Build the verified ZIP and install only this plugin into a local Hop distribution.
set -euo pipefail

fail() { printf 'Fehler: %s\n' "$*" >&2; exit 1; }
if [[ ${1:-} == --help || ${1:-} == -h ]]; then
  printf 'Aufruf: %s [Hop-Verzeichnis]\nStandard: /Users/stefan/Downloads/hop\n' "$0"
  exit 0
fi
[[ $# -le 1 ]] || fail 'Genau ein optionales Hop-Verzeichnis angeben.'

REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
HOP_TARGET=${1:-/Users/stefan/Downloads/hop}
if [[ -z ${HOP_CI_DIR:-} ]]; then
  if [[ -f "$REPO_ROOT/.ci/hop-plugin-ci/scripts/write_maven_settings.py" ]]; then
    HOP_CI_DIR="$REPO_ROOT/.ci/hop-plugin-ci"
  else
    HOP_CI_DIR="$REPO_ROOT/../hop-plugin-ci"
  fi
fi
# Resolve caller-relative paths before changing to the repository root.
[[ -d "$HOP_TARGET/plugins/transforms" && -f "$HOP_TARGET/lib/core/hop-core-2.19.0.jar" ]] ||
  fail "Kein Hop 2.19.0 mit plugins/transforms unter: $HOP_TARGET"
HOP_TARGET=$(cd -- "$HOP_TARGET" && pwd -P)
[[ -w "$HOP_TARGET/plugins/transforms" ]] || fail 'Das Hop-Plugin-Verzeichnis ist nicht beschreibbar.'
[[ -f "$HOP_CI_DIR/scripts/write_maven_settings.py" ]] ||
  fail 'hop-plugin-ci fehlt. HOP_CI_DIR auf dessen Checkout setzen.'
HOP_CI_DIR=$(cd -- "$HOP_CI_DIR" && pwd -P)
for tool in python3 mvn; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool ist nicht installiert."
done

is_jdk21() {
  [[ -x "$1/bin/java" && -x "$1/bin/javac" ]] &&
    "$1/bin/java" -version 2>&1 | grep -Eq 'version "21([.\"]|$)'
}
if [[ -n ${JAVA_HOME:-} ]] && is_jdk21 "$JAVA_HOME"; then
  : # Prefer the caller's JDK when it is compatible.
else
  if [[ -n ${JAVA_HOME:-} ]]; then
    printf 'JAVA_HOME verweist nicht auf JDK 21; suche eine passende Installation.\n'
  fi
  unset JAVA_HOME
  java_candidate=''
  if [[ -x /usr/libexec/java_home ]]; then
    java_candidate=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  fi
  if is_jdk21 "$java_candidate"; then
    JAVA_HOME=$java_candidate
  else
    for java_candidate in "${SDKMAN_CANDIDATES_DIR:-$HOME/.sdkman/candidates}"/java/21*; do
      if is_jdk21 "$java_candidate"; then
        JAVA_HOME=$java_candidate
        break
      fi
    done
  fi
  [[ -n ${JAVA_HOME:-} ]] || fail 'JDK 21 nicht gefunden. JAVA_HOME auf JDK 21 setzen.'
fi
JAVA_HOME=$(cd -- "$JAVA_HOME" && pwd -P)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

PLUGIN_DIR="$HOP_TARGET/plugins/transforms/interlis-row-validator"
[[ ! -L "$PLUGIN_DIR" ]] || fail "Plugin-Ziel ist ein symbolischer Link: $PLUGIN_DIR"
[[ ! -e "$PLUGIN_DIR" || -d "$PLUGIN_DIR" ]] || fail "Plugin-Ziel ist kein Verzeichnis: $PLUGIN_DIR"
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/interlis-row-validator-build.XXXXXX")
STAGE_DIR=''
cleanup() {
  local result=$?
  trap - EXIT
  if [[ -n "$STAGE_DIR" ]]; then
    # The backup is kept until the replacement rename succeeds. Never delete a
    # backup that could not be restored (e.g. if permissions changed meanwhile).
    if [[ -d "$STAGE_DIR/previous" && ! -e "$PLUGIN_DIR" ]]; then
      if ! mv -- "$STAGE_DIR/previous" "$PLUGIN_DIR"; then
        printf 'Wiederherstellung fehlgeschlagen; Sicherung bleibt unter %s\n' "$STAGE_DIR/previous" >&2
        rm -rf -- "$WORK_DIR"
        exit 1
      fi
    fi
    rm -rf -- "$STAGE_DIR"
  fi
  rm -rf -- "$WORK_DIR"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd -- "$REPO_ROOT"
MAVEN_SETTINGS="$WORK_DIR/maven-settings.xml"
settings_args=(--output "$MAVEN_SETTINGS")
if [[ -n ${INTERLIS_MAVEN_USERNAME:-} || -n ${INTERLIS_MAVEN_PASSWORD:-} ]]; then
  [[ -n ${INTERLIS_MAVEN_USERNAME:-} && -n ${INTERLIS_MAVEN_PASSWORD:-} ]] ||
    fail 'INTERLIS_MAVEN_USERNAME und INTERLIS_MAVEN_PASSWORD gemeinsam setzen.'
  # The shared helper references these generic names; values stay in the environment.
  export MAVEN_USERNAME="$INTERLIS_MAVEN_USERNAME"
  export MAVEN_PASSWORD="$INTERLIS_MAVEN_PASSWORD"
  settings_args+=(--server-id sogeo-snapshots)
fi
python3 "$HOP_CI_DIR/scripts/write_maven_settings.py" "${settings_args[@]}"
printf 'Build mit JDK 21: %s\n' "$JAVA_HOME"
mvn -s "$MAVEN_SETTINGS" -B -ntp clean verify
shopt -s nullglob
archives=("$REPO_ROOT"/assemblies/plugin/target/hop-interlis-row-validator-plugin-*.zip)
[[ ${#archives[@]} -eq 1 ]] || fail 'Es muss genau ein installierbares Plugin-ZIP vorhanden sein.'
PLUGIN_ZIP=${archives[0]}
python3 scripts/check-distribution.py

# Stage on the target filesystem so the final directory moves are renames.
STAGE_DIR=$(mktemp -d "$HOP_TARGET/plugins/transforms/.interlis-row-validator-install.XXXXXX")
python3 - "$PLUGIN_ZIP" "$STAGE_DIR" <<'PY'
from pathlib import Path
import stat
import sys
import zipfile

destination = Path(sys.argv[2]).resolve()
with zipfile.ZipFile(sys.argv[1]) as archive:
    for member in archive.infolist():
        path = (destination / member.filename).resolve()
        if destination not in path.parents or stat.S_ISLNK(member.external_attr >> 16):
            raise SystemExit(f"Ungueltiger ZIP-Pfad: {member.filename}")
    archive.extractall(destination)
PY
if [[ -d "$PLUGIN_DIR" ]]; then
  mv -- "$PLUGIN_DIR" "$STAGE_DIR/previous"
fi
mv -- "$STAGE_DIR/plugins/transforms/interlis-row-validator" "$PLUGIN_DIR"
printf '\nZIP: %s\nInstalliert: %s\nHop neu starten, um das Plugin zu laden.\n' "$PLUGIN_ZIP" "$PLUGIN_DIR"
