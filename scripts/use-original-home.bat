@echo off
rem Puts the TV's own home screen back (undoes use-cue-home.bat):
rem   use-original-home.bat 192.168.1.50
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "TV=%~1"
if "%TV%"=="" ( echo Usage: use-original-home.bat TV-IP-address & exit /b 1 )
adb connect %TV% >nul
adb -s %TV% shell pm enable com.google.android.tvlauncher >nul 2>&1
adb -s %TV% shell pm enable com.google.android.apps.tv.launcherx >nul 2>&1
if exist cue-home-backup.txt (
  for /f "tokens=1,* delims==" %%a in (cue-home-backup.txt) do set "B_%%a=%%b"
  echo !B_home! | find "/" >nul && adb -s %TV% shell cmd package set-home-activity !B_home!
  if "!B_services!"=="null" ( adb -s %TV% shell settings delete secure enabled_accessibility_services ) else ( adb -s %TV% shell settings put secure enabled_accessibility_services "!B_services!" )
  if "!B_enabled!"=="null" ( adb -s %TV% shell settings delete secure accessibility_enabled ) else ( adb -s %TV% shell settings put secure accessibility_enabled !B_enabled! )
) else (
  adb -s %TV% shell settings delete secure enabled_accessibility_services
  adb -s %TV% shell pm clear-default app.cue.home
)
adb -s %TV% shell input keyevent KEYCODE_HOME
echo Done: the TV's own home screen is back.
