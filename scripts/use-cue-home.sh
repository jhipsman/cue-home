#!/usr/bin/env bash
# Makes Cue Home the TV's home screen (Google TV / Android TV), from a
# computer on the same Wi-Fi with ADB (Android's platform-tools):
#   ./use-cue-home.sh 192.168.1.50          (the TV's address)
#   ./use-cue-home.sh 192.168.1.50 pair     (Android 11+ / Google TV: pair first)
# It saves what it changes (cue-home-backup.txt); use-original-home.sh puts it back.
# After Horizon Home Kit's scripts (MIT licence).
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:?give the TV IP address, e.g. ./use-cue-home.sh 192.168.1.50}"
PKG=app.cue.home
command -v adb >/dev/null || { echo "ADB isn't installed: get Android's platform-tools (developer.android.com/tools/releases/platform-tools)."; exit 1; }

if [ "${2:-}" = "pair" ]; then
  echo "On the TV: Settings > System > Developer options > Wireless debugging > Pair device with pairing code."
  read -rp "The pairing address shown (IP:port): " PAIR
  read -rp "The 6-digit code: " CODE
  adb pair "$PAIR" "$CODE"
  read -rp "The IP address & port shown on the Wireless debugging screen: " TV
fi
adb connect "$TV" >/dev/null
adb -s "$TV" get-state >/dev/null || { echo "Can't reach the TV at $TV. Is it on, on the same Wi-Fi, with debugging on?"; exit 1; }
A=(adb -s "$TV" shell)

"${A[@]}" pm list packages "$PKG" | grep -q "$PKG" || { echo "Install Cue Home on the TV first (cue-home.apk)."; exit 1; }

# What's there now, to put back later.
if [ ! -f cue-home-backup.txt ]; then
  {
    echo "home=$("${A[@]}" cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')"
    echo "services=$("${A[@]}" settings get secure enabled_accessibility_services | tr -d '\r')"
    echo "enabled=$("${A[@]}" settings get secure accessibility_enabled | tr -d '\r')"
  } > cue-home-backup.txt
  echo "Saved the TV's settings in scripts/cue-home-backup.txt."
fi

# 1. The home screen.
"${A[@]}" cmd package set-home-activity "$PKG/app.cue.tv.home.HomeActivity"
# 2. Continue watching from the other apps (Android's Watch Next list).
"${A[@]}" pm grant "$PKG" com.android.providers.tv.permission.READ_EPG_DATA || echo "(This TV won't let Cue Home read other apps' Continue watching: it shows Cue's.)"
# 3. The Home button, on TVs that send it to Google's home anyway.
SVC="$PKG/app.cue.tv.home.HomeButtonService"
NOW="$("${A[@]}" settings get secure enabled_accessibility_services | tr -d '\r')"
case "$NOW" in
  *"$SVC"*) ;;
  ""|null) "${A[@]}" settings put secure enabled_accessibility_services "$SVC" ;;
  *) "${A[@]}" settings put secure enabled_accessibility_services "$NOW:$SVC" ;;
esac
"${A[@]}" settings put secure accessibility_enabled 1
"${A[@]}" input keyevent KEYCODE_HOME
echo "Done: Home opens Cue Home. To undo: ./use-original-home.sh $TV"
