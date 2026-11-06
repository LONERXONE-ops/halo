# Halo design rules

How every part of the island looks and moves, so new features feel like
the same family. The numbers live in code in `HaloTheme.kt`; change both
together.

Credit: Halo is by LONERXONE. The ideas below were studied from Apple's
Human Interface Guidelines (Live Activities), Samsung One UI, Material 3
Expressive motion (via expressive-cutout), Smart Island, DroidBubble,
LiveBridge and HyperBridge. Halo's code is its own; nothing is copied.

## Colour

- The island is pure black, so it melts into the camera cut-out.
- Text and icons are white. Secondary text is white at 70%, quiet text 55%.
- Colour means something: green for charging, answering, arrived and done;
  red for hanging up, recording and danger; orange for alarms, timers and
  warnings. Everything else stays white and grey.
- Music is the one place that takes on colour: the album's.

## The three sizes

| Size | What it shows | Rules |
| --- | --- | --- |
| Idle | The camera circle | Same size as the camera, nothing in it. |
| Compact | One small thing each side of the camera | 30 dp tall. Adaptive: each side of the camera is only as wide as its own content (6 to 9 dp from the end, 7 dp from the camera), so the pill sits off-centre when one side holds more. Never under 72 dp (or the camera plus 44 dp). An icon or image (18 to 20 dp) on one side; an icon, ring or short value (11.5 to 12 sp, tabular numbers) on the other. Never a sentence. |
| Open | A card | Up to 372 dp wide, 8 dp from the screen edges, 32 dp corners. 18 dp padding, 8 dp between rows. The top row sits beside the camera: app on the left, a status on the right. |

Every compact moment answers one question at a glance: what is it (left)
and how is it going (right).

## Type

- Titles 15.5 to 17 sp bold. Body 13 to 13.5 sp. Labels 10.5 to 12 sp.
- Numbers that change (times, percentages, codes) use tabular figures so
  they don't jiggle.

## Motion

Spatial springs, after Material 3 Expressive's tokens:

- Stiffness 640 for opening, closing and resizing; 720 for quick moments.
- Damping 0.4 while growing (about 25% overshoot, the "alive" bounce) and
  0.55 while shrinking (still springy as it settles). Halo leans bouncy on purpose.
- The user's Motion speed scales stiffness (Relaxed 0.6x, Quick 1.45x) and
  Bounce shifts damping (Subtle +0.14, Bouncy -0.1).
- Content appears once the shape is 45% open (300 ms springy scale-in),
  so it feels instant. Old content fades out in 100 ms.
- Opened cards: rows rise in one after another, 42 ms apart.
- New content at the same size: a single jelly squish (a spring kicked from
  its own resting place), never two chained animations.
- Taps: a quick press-in, and a springy release that carries the finger's
  motion.
- Feedback that matters gets a haptic: arrivals, ring and vibrate, a wrong
  PIN (the island shakes no), copying a code.

## Notifications

- A new alert always arrives as a small pill: the photo (with the app's
  badge) on the left of the camera, who it's from on the right (up to about
  74 dp). The message waits until you tap. The photo pops in with a spin,
  then the name springs out from the camera.
- Tap or pull down: it opens into the full card. Swipe up: dismiss. Hold:
  open the app.

## Quick moments

Short system moments (ring and silent, Bluetooth, Wi-Fi, hotspot, battery)
are calibrated in `Moments.kt`: 1.7 s for switches, 2 to 3 s for devices and
battery, 4.2 s for a critical battery. Icon left, switch or value right.

## Live activities, in priority order

1. Calls
2. Alarms
3. One-time codes
4. Screen recording
5. Timers
6. Rides and deliveries
7. Music
8. Navigation
9. Downloads
10. Flashlight

The first takes the pill; the second sits in the round bubble beside it.

## Keeping the app's own buttons

Wherever an app gives buttons (Pause, Resume and Cancel on a download;
Call driver on a ride; Snooze and Stop on an alarm; like and shuffle in a
player), the island shows those same buttons and sends exactly what the app
would. The main action is a white pill; danger (Cancel, Stop) is red-tinted.
