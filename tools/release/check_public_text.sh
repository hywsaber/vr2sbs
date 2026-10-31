#!/usr/bin/env bash
# Scans public project text for personal identifiers before a release.
#
# Usage:
#   tools/release/check_public_text.sh              # all tracked text files
#   tools/release/check_public_text.sh --file F     # one file (for example release notes)
#   tools/release/check_public_text.sh --commits R  # commit messages in range R (for example v1.0.0..v1.0.1)
#   tools/release/check_public_text.sh --denylist F # extra literal terms, never commit this file
#
# The default denylist path is ~/.config/vr2sbs/privacy-denylist.txt. Each
# non-comment line is matched literally. Exit status is 1 when anything is
# found and 0 otherwise.
set -euo pipefail

mode=files
target=
denylist="${XDG_CONFIG_HOME:-$HOME/.config}/vr2sbs/privacy-denylist.txt"

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --file)
      mode=file
      target="${2:?--file needs a path}"
      shift 2
      ;;
    --commits)
      mode=commits
      target="${2:?--commits needs a range}"
      shift 2
      ;;
    --denylist)
      denylist="${2:?--denylist needs a path}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

repo_root=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ "$mode" != "file" ] && [ -z "$repo_root" ]; then
  echo "error: run this inside the repository" >&2
  exit 2
fi

# A single-file scan keeps the caller's working directory so relative paths work.
if [ "$mode" != "file" ]; then
  cd "$repo_root"
fi

excludes=(
  ':(exclude)app/src/main/cpp/third_party'
  ':(exclude)tools/release/check_public_text.sh'
)

patterns=(
  '(^|[^0-9.])(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3})([^0-9.]|$)'
  '(^|[^0-9A-Fa-f])([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}([^0-9A-Fa-f]|$)'
  '(adb[[:space:]]+(connect|tcpip))|:55{2}5'
  '\b(TB-[0-9]{4}[A-Za-z]?|NX[0-9]{3,4}[A-Za-z]?)\b'
  '(^|[^A-Za-z0-9_-])[A-Za-z0-9][A-Za-z0-9_-]{2,}(_(5G|2\.4G|2G|WiFi|wifi|WLAN|wlan))([^A-Za-z0-9_-]|$)'
  '([Cc]:[/\\]+Users[/\\]|/Users/[^/[:space:]]+/|/home/[^/[:space:]]+/)'
  '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
)

labels=(
  "private IPv4 address"
  "MAC address"
  "ADB endpoint"
  "device model or serial"
  "SSID-like network name"
  "local user path"
  "email address"
)

found=0

report() {
  local label=$1
  local matches=$2
  if [ -n "$matches" ]; then
    echo "== $label =="
    printf '%s\n' "$matches"
    echo
    found=1
  fi
}

scan_commits() {
  local sha
  local body
  git rev-list "$target" | while IFS= read -r sha; do
    body=$(git log -1 --format=%B "$sha")
    printf '%s\n' "$body" | grep -n -I "$@" | sed "s|^|$sha:|" || true
  done
}

for i in "${!patterns[@]}"; do
  pattern=${patterns[$i]}
  case "$mode" in
    file)
      matches=$(grep -n -I -E -e "$pattern" -- "$target" || true)
      ;;
    commits)
      matches=$(scan_commits -E -e "$pattern")
      ;;
    files)
      matches=$(git grep -n -I -E -e "$pattern" -- . "${excludes[@]}" || true)
      ;;
  esac

  # Local aliases such as user@host.local are not personal addresses.
  if [ "$i" -eq 6 ]; then
    matches=$(printf '%s\n' "$matches" | grep -vE '@(example\.(com|org|net)|[A-Za-z0-9.-]+\.local)([^A-Za-z0-9.-]|$)' || true)
  fi

  report "${labels[$i]}" "$matches"
done

if [ -f "$denylist" ]; then
  filtered=$(mktemp)
  trap 'rm -f "$filtered"' EXIT
  grep -vE '^[[:space:]]*(#|$)' "$denylist" > "$filtered" || true

  if [ -s "$filtered" ]; then
    case "$mode" in
      file)
        matches=$(grep -n -I -F -f "$filtered" -- "$target" || true)
        ;;
      commits)
        matches=$(scan_commits -F -f "$filtered")
        ;;
      files)
        matches=$(git grep -n -I -F -f "$filtered" -- . "${excludes[@]}" || true)
        ;;
    esac
    report "denylisted term (from $denylist)" "$matches"
  fi
fi

if [ "$found" -ne 0 ]; then
  echo "Personal information found in public text. Redact it before publishing." >&2
  exit 1
fi

echo "No personal information patterns found."
