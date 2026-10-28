<p align="center">
  <img src="docs/logo.svg" width="120" alt="Halo logo">
</p>

<h1 align="center">Halo</h1>

<p align="center">
  A dynamic island for Android. A black shape around your camera that grows into music, calls,
  messages, timers and more, then tucks itself back into the lens.<br>
  Works on Android 11 and newer, and feels most at home on Samsung One UI.
</p>

---

## Download

**[⬇ Get the latest APK](https://github.com/LONERXONE-ops/halo/releases/latest)**

1. Download `Halo-x.y.z.apk` from **Assets** and open it. Allow your browser to install unknown apps if asked.
2. Open **Halo** and follow **Setup**. Every row should turn green.
3. After that you never need the browser again: **Halo → Updates → Download & install** fetches new versions from this page.

> **A switch is greyed out?** Android blocks sideloaded apps from accessibility and notification
> access until you allow it: **Settings → Apps → Halo → ⋮ → Allow restricted settings**, then try again.

## What it does

| | |
|---|---|
| **Camera circle** | At rest, Halo is a black circle sitting exactly on your camera, so you don't see it. Tap the camera to open it. |
| **Camera fit** | Finds the front camera on any phone: centred punch-holes, corner punch-holes, waterdrop and wide notches. Corner cameras keep the circle on the lens while the pill opens in the middle; phones with no cutout get an invisible tap zone at the top. Fine-tune it in Settings → Camera fit. |
| **Notification center** | Tap the camera (or the bell beside the pill): date and time beside the lens, battery up top, a mini music player, then your notifications. Scroll, tap one to read it, or Clear all. |
| **Reply sheet** | Opening a notification shows the whole chat (WhatsApp, Telegram, SMS…) with a reply box, plus Open, Mark as read and Dismiss. |
| **Music** | Cover on the left, bouncing bars on the right. Tap for the full player: blurred cover behind, big play button, a seek bar you can drag. Swipe sideways to skip. |
| **Messages** | Drawn like a chat: contact or group photo with the app badge, who said what in groups, unread count, image previews, and Reply / Mark as read right on the banner. Banking and OTP apps are off by default. |
| **Calls** | Caller and a live timer; tap for end call (and answer while ringing). |
| **Timers** | Countdown or stopwatch from the Clock app, with its Pause / Cancel buttons. |
| **Downloads** | A progress ring for downloads, uploads and app installs, and a "Done" pop at the end. |
| **Navigation** | Next turn and distance from Google Maps. |
| **Split bubble** | Two things at once (say a timer while music plays): the second one sits in a circle beside the pill. With only one, a small bell sits there instead and opens the notification center. |
| **Charging** | A pop when you plug in; tap it for the charging card: big percentage, wattage, time to full and temperature. |
| **Flashlight** | Stays on the island while the torch is on. Tap it to switch it off. |
| **Little pops** | Fully charged, low battery, silent / vibrate / ring, earbuds connected (with their battery), Do Not Disturb, Wi-Fi, airplane mode, power saving. |

Touch anywhere else on the screen and an open island retracts. Apps that fire lots of alerts in a row get a short cooldown so they can't flood the island.

## Settings

- **On the island**: turn each activity on or off, and choose which apps may pop up.
- **Look & feel**: island size, move it left/right/up/down, glass strength (0 = pure black), blurred album art, hide in fullscreen, haptics, low-power mode.
- **Live status**: shows exactly what Halo sees right now (music sessions, last notification, why it is hidden). Screenshot it when something looks wrong.

## How it works

- **Accessibility service**, used only to draw the island above the status bar. Halo never reads what is on your screen.
- **Notification access**, used for music sessions, messages, calls, timers, downloads and navigation.
- Two overlay windows: a fixed one that draws (so Android never stretches frames mid-animation) and a small invisible one that follows the shape and catches touches.
- Every part of the shape (width, height, corners, position) moves on its own spring, solved exactly each frame, so motion is the same at 60 or 120 Hz and can be interrupted without snapping.
- Nothing leaves your phone. The only network call is the update check against this repository's releases.

## Samsung tips

- **Battery**: Setup → *Battery: unrestricted*. Also add Halo to *Settings → Battery → Background usage limits → Never sleeping apps*.
- **Animation speed** follows *Developer options → Animator duration scale*.
- If the island sits slightly off your camera, nudge it in *Look & feel*.

## Building

Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release.
Kotlin, plain Android views, no extra libraries. `minSdk 30`, `targetSdk 35`.

## Made by

**LONERXONE** ([LONERXONE-ops](https://github.com/LONERXONE-ops)). Issues and ideas welcome.

## Thanks

Ideas and lessons from other open-source islands, each worth a look:
[Smart Island](https://github.com/agupta07505/SmartIsland) (pill proportions and outside-tap retract),
[Island](https://github.com/Arnav-Dugad/dynamic-island-android) (closed-form spring physics, audio-only Bluetooth pops),
and [Islandify](https://github.com/Ctps1234/Islandify) (time and battery inside the island).
Halo's code is written from scratch.
