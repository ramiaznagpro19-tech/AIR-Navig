#!/bin/bash
# Installe AIR Navig sur l'émulateur, l'ouvre, essaie les fonctions et garde captures + journal.
# Chaque commande est limitée dans le temps pour qu'un blocage ne fige pas l'essai.
set -x
mkdir -p out
A() { timeout 60 adb "$@"; }
shot() { sleep 1; timeout 30 adb exec-out screencap -p > "out/$1.png"; }
T() { A shell am start -n app.airnavig/.MainActivity --es airtest "$1"; }
APK=$(ls build/outputs/apk/debug/*.apk | head -1)
timeout 180 adb install -r -g "$APK" > out/install.txt 2>&1
A logcat -c
A shell am start -n app.airnavig/.MainActivity
sleep 35
shot 01-demarrage
A shell am start -a android.intent.action.VIEW -d "https://fr.wikipedia.org/wiki/Paris" app.airnavig
sleep 30
shot 02-wikipedia
T "show:tabs"; sleep 6
shot 03-onglets
T "collapse"; sleep 2
T "reader"; sleep 15
shot 04-lecture
T "reader"; sleep 8
T "show:menu"; sleep 4
shot 05-menu
T "show:set_sync"; sleep 3
shot 06-synchro
T "show:addons"; sleep 6
shot 07-extensions
T "collapse"; sleep 2
A shell am start -a android.intent.action.VIEW -d "https://de.wikipedia.org/wiki/Berlin" app.airnavig
sleep 30
shot 08-allemand
T "translate:do"; sleep 60
shot 09-traduit
T "show:tabs"; sleep 6
shot 10-onglets
T "type:localhost:8080"; sleep 8
shot 11-localhost
T "type:/sdcard/"; sleep 5
shot 12-fichiers
A shell input keyevent KEYCODE_BACK; sleep 2
T "show:set_tr"; sleep 3
shot 13-traduction
timeout 60 adb logcat -d > out/logcat-complet.txt
grep -E "GeckoConsole|AIRNavig|AndroidRuntime|FATAL|Translations|GeckoView" out/logcat-complet.txt | grep -v "MozAfterPaint\|No impl for message" | tail -2000 > out/logcat-gecko.txt
exit 0
