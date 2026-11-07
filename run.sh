#!/bin/bash
# Drives the emulator: installs Halo, Halo Launcher and the probe, turns
# everything on, plays scenes and saves screenshots plus one screen recording.
set -u
OUT=out
mkdir -p $OUT
H=com.lonerxone.halo
L=com.lonerxone.halo.launcher
N=0
log() { echo "== $*" | tee -a $OUT/steps.txt; }
shot() { N=$((N+1)); f=$(printf "%s/%02d_%s.png" $OUT $N "$1"); adb exec-out screencap -p > "$f"; log "shot $f"; }
probe() { adb shell am broadcast -n com.lonerxone.probe/.Probe --es cmd "$@" > /dev/null; }
tap() { adb shell input tap "$1" "$2"; }

adb wait-for-device
log "installing"
adb install -r -g apk/halo.apk | tee -a $OUT/steps.txt
adb install -r -g apk/launcher.apk | tee -a $OUT/steps.txt
adb install -r -g apk/probe.apk | tee -a $OUT/steps.txt

# A punch-hole camera like most phones (emulator overlay).
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole || true
adb shell cmd overlay list | grep -i cutout | tee -a $OUT/steps.txt
sleep 3

log "permissions"
adb shell appops set $H SYSTEM_ALERT_WINDOW allow
adb shell pm grant $H android.permission.POST_NOTIFICATIONS || true
adb shell pm grant $H android.permission.RECORD_AUDIO || true
adb shell cmd notification allow_listener $H/$H.HaloNotificationListener
adb shell settings put secure enabled_accessibility_services $H/$H.HaloService
adb shell settings put secure accessibility_enabled 1
adb shell cmd deviceidle whitelist +$H > /dev/null || true
adb shell cmd package set-home-activity $L/$L.LauncherActivity || true
sleep 2
adb shell dumpsys accessibility | grep -i -m3 'halo' | tee -a $OUT/steps.txt

W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1)
HH=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)
CUT=$(adb shell dumpsys display | grep -o 'boundingRect[^]]*\]' | head -3)
log "screen ${W}x${HH} cutout: $CUT"
CX=$((W/2)); CY=$((HH/34))
# Tap the island where the camera actually is.
R=$(echo "$CUT" | grep -o 'Rect([0-9]*, [0-9]* - [0-9]*, [0-9]*)' | tr -d 'Rect(),-' | awk '$3>0 {print; exit}')
if [ -n "$R" ]; then
  set -- $R
  CX=$((($1+$3)/2)); CY=$((($2+$4)/2))
fi
log "island at $CX,$CY"

# Launcher first (home).
adb shell input keyevent KEYCODE_HOME
sleep 4
shot home
adb shell input swipe $CX $((HH*3/4)) $CX $((HH/4)) 300
sleep 2
shot home_swipe_up
adb shell input keyevent KEYCODE_HOME
sleep 2

# Halo's own app.
adb shell am start -n $H/$H.MainActivity > /dev/null
sleep 3
shot halo_app
adb shell input swipe $CX $((HH*3/4)) $CX $((HH/4)) 300
sleep 1
shot halo_app_scrolled
adb shell input keyevent KEYCODE_HOME
sleep 2
shot idle_island

# One recording through all the scenes.
adb shell screenrecord --bit-rate 6000000 --time-limit 170 /sdcard/scenes.mp4 &
REC=$!
sleep 2
T0=$(date +%s.%N)
mark() { echo "$(echo "$(date +%s.%N) - $T0 + 2" | bc) $1" >> $OUT/marks.txt; log "mark $1"; }

mark "message arrives"
probe msg
sleep 0.4; shot msg_0.4s
sleep 1.2; shot msg_pill
mark "tap pill"
tap $CX $CY
sleep 0.9; shot msg_open
sleep 2
mark "swipe up dismiss"
adb shell input swipe $CX $((CY+120)) $CX 0 120
sleep 1.2; shot msg_dismissed

mark "download 30"
probe dl --ei pct 30
sleep 1.5; shot dl_30
probe dl --ei pct 70
sleep 1; shot dl_70
mark "tap download"
tap $CX $CY
sleep 1; shot dl_open
probe dl --ei pct 99
sleep 0.8
mark "download done"
probe dldone
sleep 0.25; shot done_0.25s
sleep 0.3; shot done_0.55s
sleep 0.4; shot done_0.95s
sleep 1; shot done_2s
sleep 2; shot done_gone

mark "music"
probe music
sleep 2; shot music_pill
mark "tap music"
tap $CX $CY
sleep 1.2; shot music_open
sleep 1
adb shell input swipe $CX $((CY+300)) $CX 0 150
sleep 1.5; shot music_closed

mark "otp"
probe otp
sleep 1.5; shot otp_pill
tap $CX $CY
sleep 1.2; shot otp_open
sleep 2

mark "second message"
probe msg --es who Ada --es text "Sent you the slides, check them"
sleep 1.5; shot msg2_pill
sleep 6

mark "notification center"
tap $CX $CY
sleep 0.3
adb shell input keyevent KEYCODE_HOME
sleep 1.5
tap $CX $CY
sleep 1.5; shot panel
adb shell input keyevent KEYCODE_BACK
sleep 2
probe clear
sleep 2; shot end

kill -INT $REC 2>/dev/null; sleep 3
adb pull /sdcard/scenes.mp4 $OUT/scenes.mp4 || true
adb logcat -d -b crash > $OUT/crash.txt || true
adb logcat -d | grep -iE 'halo|AndroidRuntime' | tail -400 > $OUT/logcat.txt || true
adb shell dumpsys notification --noredact | grep -A2 -i 'probe' | head -60 > $OUT/notifs.txt || true
log "done"
