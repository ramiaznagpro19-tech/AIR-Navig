#!/bin/bash
# Installe AIR Navig sur l'émulateur, l'ouvre, fait des captures et garde le journal.
set -x
mkdir -p out
APK=$(ls build/outputs/apk/debug/*.apk | head -1)
adb install -r -g "$APK" > out/install.txt 2>&1
adb logcat -c
adb shell am start -n app.airnavig/.MainActivity
sleep 35
adb exec-out screencap -p > out/1-demarrage.png
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
# Menu « ••• » (en bas à droite)
adb shell input tap $((SW*90/100)) $((SH*95/100)); sleep 4
adb exec-out screencap -p > out/2-menu.png
adb shell input keyevent KEYCODE_BACK; sleep 3
adb shell am start -a android.intent.action.VIEW -d "https://www.lemonde.fr" app.airnavig
sleep 35
adb exec-out screencap -p > out/3-lemonde.png
adb shell am start -a android.intent.action.VIEW -d "https://fr.wikipedia.org/wiki/Paris" app.airnavig
sleep 25
adb exec-out screencap -p > out/4-wikipedia.png
adb logcat -d > out/logcat-complet.txt
grep -E "GeckoConsole|AIRNavig|AndroidRuntime|FATAL" out/logcat-complet.txt | tail -1500 > out/logcat-gecko.txt
exit 0
