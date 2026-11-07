<p align="center">
  <img src="docs/logo.png" width="160" alt="Halo logo: the island with an angel's halo">
</p>

<h1 align="center">Halo</h1>

<p align="center">
  A dynamic island for Android. A black shape around your camera that springs open into
  notifications, music, calls, codes, rides, timers and more, then tucks itself back into the lens.<br>
  Works on Android 11 and newer, and feels most at home on Samsung One UI.
</p>

---

## Download

**[⬇ Get the latest APK](https://github.com/LONERXONE-ops/halo/releases/latest)**

1. Download `Halo-x.y.z.apk` from **Assets** and open it. Allow your browser to install unknown apps if asked.
2. Open **Halo** and go to **Permissions**. Every row should say **On**.
3. From then on Halo updates itself: it checks every few hours, sends a notification when a new
   version is out, and **Halo → Updates** downloads and installs it (with what's new, size, speed
   and time left).

> **A switch is greyed out?** Android blocks sideloaded apps from accessibility and notification
> access until you allow it: **Settings → Apps → Halo → ⋮ → Allow restricted settings**, then try again.
>
> **After some updates** (new abilities like the screenshot switch), turn Halo off and on once in
> **Settings → Accessibility** so Android grants them.

## What it does

### Notifications
| | |
|---|---|
| **Alert pills** | A new notification arrives as a small pill with just the sender's photo (with the app's badge). Tap it (or pull it down) and it opens into the whole notification; swipe it up to dismiss, hold it to open the app. |
| **Full alerts** | Contact and group photos, who said what in groups, unread count, image previews, and Reply / Mark as read (or Open / Dismiss). Swipe sideways to go through your other notifications. |
| **Reply center** | The whole chat with a reply box, quick reply chips (the app's own suggestions, Android's smart replies, everyday ones) and Open, Mark as read, Dismiss. Works in more apps than usual (it also uses replies apps only give watches). |
| **Notification center** | Tap the camera: date and time, chips (battery, flashlight, Do Not Disturb, silent…), your quick buttons, a mini player, then every notification. It stays up to date while open; clearing the last one shows "You're all caught up". |
| **One-time codes** | A code in a message (OTP, verification code) shows on the island with a key. Open it for big digit tiles, **Copy** and **Copy and open**. Copies are marked sensitive. |
| **Privacy** | Follows Do Not Disturb, silent channels and lock screen privacy. Banking apps start switched off; choose which apps may pop up. |

### Live activities
| | |
|---|---|
| **Music** | A tiny round cover and the beat in the pill. Open it: the album's colour, soft waves that move with the bass, mids and highs, a Samsung-style wave scrubber you can drag, where it's playing (your earbuds' name), and the app's own buttons (like, shuffle, add) that change the moment you tap. |
| **Calls** | Caller and a timer that starts when they pick up ("Calling…" before); answer and hang up. |
| **Rides and deliveries** | Bolt, Uber, inDrive, Chowdeck, Glovo and others: minutes away in the pill; open it for the status, a track with the stages and a little car or scooter, and the app's buttons. |
| **Alarms** | An alarm going off (or a timer ending) opens big with Snooze and Stop. |
| **Timers** | Countdown or stopwatch from the Clock app, ticking in step with it, with its own buttons. |
| **Downloads** | A progress ring; open it for the size and speed line and the app's own Pause, Resume and Cancel. When it finishes, the ring fills, turns green and becomes a tick that draws itself with a bounce, for every app's downloads, uploads and installs. |
| **Screen recording** | Halo's own recorder (with quality and sound choices) or another app's: a red dot and timer, pause and stop. |
| **Navigation** | Next turn and distance from Google Maps. |
| **Split bubble** | Two at once: the second sits in a circle beside the pill. Tap it to swap. |

### System moments
Short moments that flick out and tuck back in, each with its own switch and a preview button:
ring, vibrate and silent (the island shakes with vibrate and sways with ring), Do Not Disturb,
Bluetooth gear connecting and leaving, Bluetooth on and off, earbuds' battery, Wi-Fi connecting
and really dropping, hotspot, airplane mode, power saving, charging (tap it for the charging card)
and low battery at 20%, 10% and 5%.

### Lock screen
A padlock that springs open when you unlock. A wrong PIN, pattern or fingerprint makes the island
**shake no**, like an iPhone.

### Look and feel
- Pure black, white and grey, with colour only where it means something (green, red, orange) and in music.
- **Motion**: Speed (Relaxed, Normal, Quick) and Bounce (Subtle, Normal, Bouncy). Everything springs.
- **Fine sizing**: pill height, pill width and open card width, with − and + for exact steps; move it left, right, up and down.
- Glass strength, your own pictures inside the island and behind the notification center, low-power mode.
- **Show in screenshots**: switch off and the island steps out of button screenshots.
- Every settings page has its own **Reset** at the top.

## How it works

- **Accessibility service**, used to draw the island above everything. Halo never reads your apps;
  only while the phone is locked does it read the lock screen's message line (for the shake).
- **Notification access**, for music sessions, messages, codes, rides, calls, timers, downloads and navigation.
- Two overlay windows: a fixed one that draws (so frames are never stretched) and a small invisible one that follows the shape and catches touches.
- Every part of the shape (width, height, corners, position) moves on its own spring, solved exactly each frame, so motion is the same at 60 or 120 Hz and can be interrupted without snapping.
- The rules every feature follows (sizes, colours, springs, timings) are in **[DESIGN.md](DESIGN.md)**, and in code in `HaloTheme.kt`.
- Nothing leaves your phone except the update check against this repository's releases.

## Works with Halo Launcher

[Halo Launcher](https://github.com/LONERXONE-ops/halo-launcher) is a home screen made for Halo:
its music widget, notification badges and quick commands talk to the island, and each app's
Updates page shows the other's version.

## Samsung tips

- **Battery**: Setup → *Battery: unrestricted*. Also add Halo to *Settings → Battery → Background usage limits → Never sleeping apps*.
- **Animation speed** also follows *Developer options → Animator duration scale*.
- If the island sits slightly off your camera, fine-tune it in *Camera fit*.

## Building

Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release, with
the commit message as its notes. Kotlin, plain Android views, no extra libraries.
`minSdk 30`, `targetSdk 35`.

## Made by

**LONERXONE** ([LONERXONE-ops](https://github.com/LONERXONE-ops)). Issues and ideas welcome.

## Thanks

Ideas and lessons from other islands and design systems, each worth a look:
[Smart Island](https://github.com/agupta07505/SmartIsland),
[expressive-cutout](https://github.com/EvanKoe/expressive-cutout) (Material Expressive motion),
DroidBubble,
[LiveBridge](https://github.com/appsfolder/livebridge) and
[HyperBridge](https://github.com/D4vidDf/HyperBridge) (notification parsing ideas),
[Island](https://github.com/Arnav-Dugad/dynamic-island-android),
[Islandify](https://github.com/bughunter11/Islandify),
Apple's Human Interface Guidelines and Samsung One UI.
Halo's code is written from scratch.
