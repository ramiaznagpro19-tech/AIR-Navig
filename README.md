# AIR Navig 1.0 — code source

## Moteur : GeckoView (Mozilla), plus aucun WebView
Depuis cette version, AIR Navig n'utilise plus le WebView d'Android. Tout passe par **GeckoView**, le moteur
Gecko de Firefox pour Android, publié et mis à jour par Mozilla (https://maven.mozilla.org). La compilation
prend automatiquement la dernière version stable.

**Télémétrie de Gecko coupée** : à chaque démarrage, AIR Navig impose au moteur un fichier de réglages
(`Engine.PRIVACY_PREFS`) qui désactive la télémétrie Gecko, l'envoi Glean/FOG, les études Normandy, la mesure
publicitaire (PPA), les rapports de plantage (aussi coupés par variable d'environnement) et les connexions de
vérification vers Mozilla. L'appli n'active pas non plus Glean ni le gestionnaire de plantages de GeckoView.

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

## Nouveautés de la branche gecko (novembre 2026)
- **Vrais onglets** : chaque onglet a sa propre session Gecko (la page, le défilement et les formulaires restent quand on change d'onglet). Les onglets peu utilisés s'endorment au-delà de 5 et se rouvrent avec leur historique.
- **Aperçu réel des onglets** : capture de la page (`GeckoView.capturePixels`), visible dans la liste avant de cliquer. **Appui long puis glisser** pour réordonner.
- **Liens « nouvel onglet » et fenêtres de connexion** (« Se connecter avec Google… ») ouverts dans un vrai onglet relié à la page d'origine.
- **Mots de passe** (`java/Passwords.java`, `PasswordsActivity.java`) : Gecko repère les formulaires, AIR Navig propose d'enregistrer et remplit. Stockage chiffré par une clé du coffre matériel Android (AndroidKeyStore, AES-256-GCM). Écran protégé par le verrouillage du téléphone, captures interdites. **Import CSV** (Chrome, Firefox, Bitwarden, Proton Pass…) et export.
- **Extensions Firefox** (`java/Addons.java`) depuis addons.mozilla.org, avec la liste de ce qu'elles pourront faire avant installation ; activation, navigation privée, options, suppression. Le bouton d'une extension apparaît dans la barre d'adresse, et un moteur de recherche fourni par une extension (`chrome_settings_overrides.search_provider`) s'ajoute aux moteurs d'AIR Navig.
- **Mode lecture** (bouton livre dans la barre d'adresse quand la page est un article) : Readability, la bibliothèque du mode lecture de Firefox (`assets/air/readability.js`, Apache 2.0, voir `assets/readability-LICENSE.txt`) ; taille, police, thème clair / sépia / sombre.
- **Vidéo en incrustation** (Picture-in-Picture) : depuis le menu, ou automatiquement en quittant l'appli pendant une vidéo plein écran.
- **Traduction des pages en 60 langues** : sur le téléphone (Firefox Translations, rien n'est envoyé) pour les langues que ce moteur connaît, sinon par Google Traduction (signalé à l'utilisateur) ; proposée ou automatique, langue cible au choix.
- **Compatibilité** : HTTPS en priorité géré par Gecko (repli http sans casser les formulaires), correctifs webcompat de Mozilla, authentification HTTP, menu d'appui long (ouvrir dans un nouvel onglet, copier, télécharger l'image), copier-coller dans les pages.
- **Fichiers locaux et serveurs locaux** : `file:///sdcard/…` (accès aux fichiers demandé quand tu actives « Fichiers locaux »), `localhost:3000`, `192.168.1.20:8080`, `monpc.local` reconnus dans la barre d'adresse ; les sites locaux accèdent au réseau local, les sites publics doivent demander.

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
