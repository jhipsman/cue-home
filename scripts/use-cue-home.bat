@echo off
rem Makes Cue Home the TV's home screen (Google TV / Android TV), from a
rem Windows PC on the same Wi-Fi with ADB (Android's platform-tools in PATH):
rem   use-cue-home.bat 192.168.1.50
rem Android 11+ / Google TV: first "adb pair IP:port code" (Developer options >
rem Wireless debugging > Pair device with pairing code).
rem It saves what it changes (cue-home-backup.txt); use-original-home.bat puts it back.
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "TV=%~1"
if "%TV%"=="" ( echo Usage: use-cue-home.bat TV-IP-address & exit /b 1 )
set "PKG=app.cue.home"
where adb >nul 2>&1 || ( echo ADB isn't installed: get Android's platform-tools and add it to PATH. & exit /b 1 )
adb connect %TV% >nul
adb -s %TV% get-state >nul 2>&1 || ( echo Can't reach the TV at %TV%. & exit /b 1 )
if not exist cue-home-backup.txt (
  for /f "delims=" %%a in ('adb -s %TV% shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') do set "HOME=%%a"
  for /f "delims=" %%a in ('adb -s %TV% shell settings get secure enabled_accessibility_services') do set "SVCS=%%a"
  for /f "delims=" %%a in ('adb -s %TV% shell settings get secure accessibility_enabled') do set "ENABLED=%%a"
  > cue-home-backup.txt (
    echo home=!HOME!
    echo services=!SVCS!
    echo enabled=!ENABLED!
  )
)
adb -s %TV% shell cmd package set-home-activity %PKG%/app.cue.tv.home.HomeActivity
adb -s %TV% shell pm grant %PKG% com.android.providers.tv.permission.READ_EPG_DATA
set "SVC=%PKG%/app.cue.tv.home.HomeButtonService"
for /f "delims=" %%a in ('adb -s %TV% shell settings get secure enabled_accessibility_services') do set "NOW=%%a"
echo !NOW! | find "%SVC%" >nul
if errorlevel 1 (
  if "!NOW!"=="null" ( adb -s %TV% shell settings put secure enabled_accessibility_services %SVC% ) else ( adb -s %TV% shell settings put secure enabled_accessibility_services "!NOW!:%SVC%" )
)
adb -s %TV% shell settings put secure accessibility_enabled 1
adb -s %TV% shell input keyevent KEYCODE_HOME
echo Done: Home opens Cue Home. To undo: use-original-home.bat %TV%
