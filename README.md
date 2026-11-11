# AIR Navig 1.0 — code source

## Moteur : GeckoView (Mozilla), plus aucun WebView
Depuis cette version, AIR Navig n'utilise plus le WebView d'Android. Tout passe par **GeckoView**, le moteur
Gecko de Firefox pour Android, publié et mis à jour par Mozilla (https://maven.mozilla.org). La compilation
prend automatiquement la dernière version stable. Aucune télémétrie : l'appli n'active ni Glean ni le
gestionnaire de plantages de GeckoView.

| Avant (WebView) | Maintenant (GeckoView) |
|---|---|
| Interface `chrome.html` dans un WebView + `addJavascriptInterface` | Interface dans une session Gecko, page d'une **extension intégrée** (`assets/air/`), pont par **messagerie native** (`bridge.js` ↔ `MainActivity`) |
| Bloqueur dans `shouldInterceptRequest` | Bloqueur dans `background.js` (`webRequest` bloquant), même liste StevenBlack + règles perso |
| Mode sombre forcé par `evaluateJavascript` | `content.js` de l'extension + thème préféré annoncé aux sites |
| `WebChromeClient` (dialogues, fichiers, permissions) | `Prompts.java`, `Perms.java` (listes déroulantes, dates, fichiers, caméra/micro/position site par site) |
| Navigation privée / bac à sable dans des processus WebView séparés | Sessions **privées** Gecko : rien n'est gardé à la fermeture |
| Compilation `javac` + `apktool` | Compilation **Gradle** (GeckoView arrive comme bibliothèque Android depuis Mozilla) |

Fichiers principaux : `java/Engine.java` (moteur partagé + extension), `java/MainActivity.java`,
`java/Prompts.java`, `java/Perms.java`, `java/SandboxActivity.java`, `assets/air/*`.

L'APK pèse plus lourd qu'avant (un vrai moteur de navigateur est embarqué) et ne fonctionne que sur les
téléphones 64 bits (arm64-v8a), soit la quasi-totalité des téléphones récents.

## Version 3.3 — chargement plus rapide
Le moteur reste le WebView du système (Blink/V8, le même que Chrome). La lenteur venait de la façon dont l'appli l'utilisait :
- **Traqueurs bloqués envoyés par lots** (toutes les 600 ms) à l'interface, au lieu d'un aller-retour et d'un redessin de la barre *par requête bloquée*. L'interface et les sites partagent le même processus de rendu : chaque redessin ralentissait le site.
- **Règles de blocage en table de hachage** + verdict mémorisé par domaine (avant : jusqu'à 5000 comparaisons par requête).
- **Barre de progression** mise à jour par paliers de 10 % ; scripts d'adaptation / mode sombre forcé **injectés une seule fois par page** (avant : à chaque étape du chargement).
- **Sauvegarde des onglets** regroupée (500 ms) au lieu d'une écriture à chaque événement de navigation.
- Rendu **prioritaire** pour la page visitée et dessin anticipé pour un défilement fluide.
- Code Java sans lambdas (classes anonymes) : il se compile aussi bien avec `d8` qu'avec l'ancien `dx`.
- Compilation automatique : `.github/workflows/build.yml` (GitHub Actions) produit l'APK signé à chaque envoi sur `main`.

## AIR Navig 1.0 — widget et nouveau nom
- L'appli s'appelle désormais **AIR Navig 1.0** (version affichée : 1.0.x, x = numéro de compilation).
- **Widget barre de recherche** pour l'écran d'accueil (`java/SearchWidget.java`, `res/layout/air_widget_search.xml`) : logo AIR à gauche, loupe à droite ; un appui ouvre l'appli. Il suit le thème clair/sombre du téléphone et s'élargit horizontalement.
