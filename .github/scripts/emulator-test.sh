#!/bin/bash
# Installe AIR Navig sur l'émulateur, l'ouvre, fait des captures et garde le journal.
# Chaque commande est limitée dans le temps pour qu'un blocage ne fige pas l'essai.
set -x
mkdir -p out
A() { timeout 60 adb "$@"; }
shot() { timeout 30 adb exec-out screencap -p > "out/$1.png"; }
APK=$(ls build/outputs/apk/debug/*.apk | head -1)
timeout 180 adb install -r -g "$APK" > out/install.txt 2>&1
A logcat -c
A shell am start -n app.airnavig/.MainActivity
sleep 35
shot 1-demarrage
W=$(A shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
A shell input tap $((SW*90/100)) $((SH*95/100)); sleep 5
shot 2-menu
A shell input tap $((SW*50/100)) $((SH*20/100)); sleep 3
A shell am start -a android.intent.action.VIEW -d "https://www.lemonde.fr" app.airnavig
sleep 40
shot 3-lemonde
A shell am start -a android.intent.action.VIEW -d "https://fr.wikipedia.org/wiki/Paris" app.airnavig
sleep 25
shot 4-wikipedia
timeout 60 adb logcat -d > out/logcat-complet.txt
grep -E "GeckoConsole|AIRNavig|AndroidRuntime|FATAL" out/logcat-complet.txt | tail -1500 > out/logcat-gecko.txt
exit 0
