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
sleep 1
adb shell am start -a android.intent.action.VIEW -d "https://example.org" app.airnavig
sleep 25
adb exec-out screencap -p > out/2-site.png
adb shell am start -a android.intent.action.VIEW -d "https://fr.wikipedia.org/wiki/Paris" app.airnavig
sleep 30
adb exec-out screencap -p > out/3-wikipedia.png
adb shell dumpsys activity top | head -60 > out/activite.txt 2>&1
adb logcat -d > out/logcat-complet.txt
grep -E "GeckoConsole|AIRNavig|GeckoView|Gecko[A-Z][a-zA-Z]*|AndroidRuntime|JavaScript|chromium|Error|Exception" out/logcat-complet.txt | grep -v "^$" | tail -1500 > out/logcat-gecko.txt
exit 0
