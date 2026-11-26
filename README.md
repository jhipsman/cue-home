# Cue Home

A home screen for Google TV and Android TV, built on Cue. The remote's Home
button opens it; on it you see what you were watching (from Cue, and from
Netflix and the other apps), Live TV on now, your streaming services and
your apps, and Cue's rows for you. Picking something opens the right app at
it, or plays it in Cue.

It grows from Cue's Fire TV / Google TV app (jhipsman/cueapp, `android/`),
so it has all of that app too: Search, Movies, Shows, the Live TV guide,
Browse, title pages and the player. `TV-APP-NOTES.md` is that app's README,
kept for its build and test notes.

Not for Fire TV: Amazon doesn't let another app be the home screen.

## The home screen

- **Along the top**: who's watching, the date and the time.
- **CONTINUE**: the last thing you were watching, big, with where you are.
- **Continue watching**: Cue's titles and the other apps' together (those
  watched in the last day first). Picking one of the other apps' opens it
  there, at that episode where the app allows.
- **Live TV**: the TV guide, then the big channels on now; a channel plays
  at once.
- **Your services**: each one's page in Cue (its Top 10, what's new, its
  originals), with **Open Netflix** (or whichever) to go to its app.
- **Apps**: the apps on the TV; the side menu's **Apps** has all of them.
- **For you**: Cue's rows.

Home always comes back here; Back on it stays put.

## Installing it

1. Install `cue-home.apk` on the TV: with Downloader, from this repository's
   [home-latest release](../../releases/tag/home-latest), or
   `adb install cue-home.apk`. It installs beside the Cue TV app, and signs
   in to your Cue the same way.
2. Make it the home screen, once, from a computer on the same Wi-Fi:
   - On the TV: Settings › System › About, press **Build** seven times
     (Developer options are on), then Settings › System › Developer options:
     switch on **USB debugging** (Android TV) or **Wireless debugging**
     (Google TV, Android 11 and later).
   - Install Android's platform-tools (ADB) on the computer.
   - Run, with the TV's address:
     - Mac or Linux: `scripts/use-cue-home.sh 192.168.1.50` (on Google TV:
       `scripts/use-cue-home.sh 192.168.1.50 pair`, which pairs first: have
       Wireless debugging › Pair device with pairing code open on the TV)
     - Windows: `scripts\use-cue-home.bat 192.168.1.50` (on Google TV, pair
       first: `adb pair IP:port code`, from the same screen)

   It makes Cue Home the home screen, turns on its **Home button** service
   (for TVs that send Home to Google's home screen whatever's chosen), and
   lets it read the other apps' Continue watching. It saves the TV's own
   settings first (`scripts/cue-home-backup.txt`).
3. Settings › **Cue Home** on the TV shows whether each part is on.

To go back to the TV's own home screen: `scripts/use-original-home.sh
192.168.1.50` (or the `.bat`).

## The other apps' Continue watching

Android keeps a **Watch Next** list that apps such as Netflix, Disney+ and
YouTube add to, for the TV's home screen. Android lets other home screens
read it only with a permission granted over ADB (step 2 does it). Whether a
given app's progress shows depends on that app adding to the list on your
TV; where the TV refuses the permission, Continue watching shows Cue's
titles only, and says so.

## Building

```
./gradlew assembleRelease        # app/build/outputs/apk/release/
./gradlew testDebugUnitTest      # pictures of the screens: app/build/screens
./gradlew lintRelease
```

CI (`.github/workflows/build.yml`) builds and checks every push and, on
`main`, publishes the APK as the home-latest release, where Cue Home looks
for its updates.

## Credits

The Home button service and the setup scripts follow Horizon Home Kit's
(github.com/jyotiprakash101/horizon-home-kit, MIT licence).
