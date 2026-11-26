#!/usr/bin/env bash
# Puts the TV's own home screen back (undoes use-cue-home.sh):
#   ./use-original-home.sh 192.168.1.50
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:?give the TV IP address}"
adb connect "$TV" >/dev/null
A=(adb -s "$TV" shell)
"${A[@]}" pm enable com.google.android.tvlauncher >/dev/null 2>&1 || true
"${A[@]}" pm enable com.google.android.apps.tv.launcherx >/dev/null 2>&1 || true
if [ -f cue-home-backup.txt ]; then
  home="$(sed -n 's/^home=//p' cue-home-backup.txt)"
  services="$(sed -n 's/^services=//p' cue-home-backup.txt)"
  enabled="$(sed -n 's/^enabled=//p' cue-home-backup.txt)"
  case "$home" in */*) "${A[@]}" cmd package set-home-activity "$home" ;; esac
  if [ -z "$services" ] || [ "$services" = null ]; then "${A[@]}" settings delete secure enabled_accessibility_services; else "${A[@]}" settings put secure enabled_accessibility_services "$services"; fi
  if [ -z "$enabled" ] || [ "$enabled" = null ]; then "${A[@]}" settings delete secure accessibility_enabled; else "${A[@]}" settings put secure accessibility_enabled "$enabled"; fi
else
  # No backup: just take Cue Home's Home button off the list.
  NOW="$("${A[@]}" settings get secure enabled_accessibility_services | tr -d '\r')"
  LEFT="$(echo "$NOW" | tr ':' '\n' | grep -v '^app.cue.home/' | paste -sd: -)"
  if [ -z "$LEFT" ]; then "${A[@]}" settings delete secure enabled_accessibility_services; else "${A[@]}" settings put secure enabled_accessibility_services "$LEFT"; fi
  "${A[@]}" pm clear-default app.cue.home >/dev/null 2>&1 || true
fi
"${A[@]}" input keyevent KEYCODE_HOME
echo "Done: the TV's own home screen is back."
