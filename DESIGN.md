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
| Compact | One small thing each side of the camera | 34 dp tall. At least 100 dp wide (or the camera plus 70 dp). One icon or image on the left (20 to 24 dp), one short value on the right (12 to 13.5 sp, tabular numbers). Never a sentence. |
| Open | A card | Up to 372 dp wide, 8 dp from the screen edges, 32 dp corners. 18 dp padding, 8 dp between rows. The top row sits beside the camera: app on the left, a status on the right. |

Every compact moment answers one question at a glance: what is it (left)
and how is it going (right).

## Type

- Titles 15.5 to 17 sp bold. Body 13 to 13.5 sp. Labels 10.5 to 12 sp.
- Numbers that change (times, percentages, codes) use tabular figures so
  they don't jiggle.

## Motion

Spatial springs, after Material 3 Expressive's tokens:

- Stiffness 780 for opening, closing and resizing; 900 for quick moments.
- Damping 0.36 while growing (about 30% overshoot, the "alive" bounce) and
  0.5 while shrinking (still springy as it settles). Halo leans bouncy on purpose.
- The user's Motion speed scales stiffness (Relaxed 0.6x, Quick 1.45x) and
  Bounce shifts damping (Subtle +0.14, Bouncy -0.1).
- Content appears once the shape is 35% open (320 ms springy scale-in from
  80%), so it feels instant. Old content fades out in 90 ms.
- Arriving: the pill drops out of the camera with a stretch, then settles.
- Leaving: a quick bulge, then it tucks back into the lens.
- Swiped away: it carries the flick up into the camera.
- Opened cards unfold from the top; rows rise in one after another, 34 ms apart.
- New content at the same size: a single jelly squish (a spring kicked from
  its own resting place), never two chained animations.
- Taps: a quick press-in, and a springy release that carries the finger's
  motion.
- Feedback that matters gets a haptic: arrivals, ring and vibrate, a wrong
  PIN (the island shakes no), copying a code.

## Notifications

- A new alert always arrives as the smallest pill: just the photo (with
  the app's badge) beside the camera. It pops in with a spin and a ring
  ripples out from it like a ping.
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

## Downloads

Open, a download shows the app and what's happening (Downloading, Uploading,
Installing, Paused, Done) at the top; a 48 dp ring with the kind of file in
it (video, picture, music, app, file), the name and the app's line (size,
speed, time left), and the percentage in big tabular numbers; an 8 dp bar
that glides to each new amount with a light running along it; and the
app's own buttons.

## Finishing

Anything with a progress ring (downloads, uploads, installs, from any app)
finishes the same way: the ring sweeps to full and turns green, a green disc
pops inside it with an overshoot, a white tick draws itself on top, the
island gives one jelly squish and a confirm tap. It stays 2.4 s, then goes.
Open, the big ring does the same, the bar fills green with one bright
sweep, the number becomes Done, and the app's buttons give way to Open in
the same place (no gap).
No separate "Done" pop.

## Keeping the app's own buttons

Wherever an app gives buttons (Pause, Resume and Cancel on a download;
Call driver on a ride; Snooze and Stop on an alarm; like and shuffle in a
player), the island shows those same buttons and sends exactly what the app
would. The main action is a white pill; danger (Cancel, Stop) is red-tinted.
