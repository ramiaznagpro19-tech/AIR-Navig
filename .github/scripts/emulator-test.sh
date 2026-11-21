#!/bin/bash
# Installe AIR Navig sur l'émulateur, l'ouvre, essaie les fonctions et garde captures + journal.
# Chaque commande est limitée dans le temps pour qu'un blocage ne fige pas l'essai.
set -x
mkdir -p out
A() { timeout 60 adb "$@"; }
shot() { sleep 1; timeout 30 adb exec-out screencap -p > "out/$1.png"; }
T() { A shell am start -n app.airnavig/.MainActivity --es airtest "$1"; }
V() { A shell am start -a android.intent.action.VIEW -d "$1" app.airnavig; }
# Touche le bouton dont le texte est $1 (via uiautomator)
tap_text() {
  A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  A shell cat /sdcard/ui.xml > out/ui.xml
  b=$(grep -io "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" out/ui.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  [ -z "$b" ] && { echo "bouton $1 introuvable"; return 1; }
  set -- $(echo "$b" | tr '[],' '   ')
  A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
APK=$(ls build/outputs/apk/debug/*.apk | head -1)
timeout 180 adb install -r -g "$APK" > out/install.txt 2>&1
A logcat -c
A shell am start -n app.airnavig/.MainActivity
sleep 35
shot 01-demarrage
for x in ublock-origin duckduckgo-for-firefox; do
  T "install:https://addons.mozilla.org/firefox/downloads/latest/$x/latest.xpi"; sleep 25
  tap_text "Ajouter" || tap_text "AJOUTER"; sleep 12
  tap_text "Oui"; sleep 2
done
shot 11-extensions-installees
V "https://fr.wikipedia.org/wiki/Lyon"; sleep 20
shot 12-logos-barre
T "tapext:1"; sleep 12
shot 13-fenetre-extension
A shell input keyevent KEYCODE_BACK; sleep 3
A shell input swipe 600 1530 300 1530 300; sleep 2
shot 14-logos-defiles
T "show:addons"; sleep 6
shot 15-extensions
T "show:engines"; sleep 4
shot 16-moteurs
timeout 60 adb logcat -d > out/logcat-complet.txt
grep -E "GeckoConsole|AIRNavig|AndroidRuntime|FATAL|Translations|GeckoView|WebExtension|addon" out/logcat-complet.txt | grep -v "MozAfterPaint\|No impl for message" | tail -3000 > out/logcat-gecko.txt
exit 0
