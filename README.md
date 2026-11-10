# AIR Navig 1.0 — code source

AIR Navig est un navigateur de la marque **AIR**, créé par **Rami**, un peu passionné de développement.

## Contenu
- `java/` : code de l'appli (MainActivity, IncognitoActivity, SandboxActivity, Blocklist, Updater)
- `assets/` : interface (chrome.html), pages internes (home, blocked, error)
- `AndroidManifest.xml`, `res/xml/network_security_config.xml`
- La liste de blocage (`assets/blocklist.txt`) vient de https://github.com/StevenBlack/hosts (licence MIT, voir `blocklist-LICENSE.txt`).

## Mises à jour de l'appli
Renseigner `Updater.UPDATE_URL` (adresse HTTPS d'un fichier JSON) :
`{"versionCode": 7, "versionName": "1.4", "url": "https://…/AIR-Navig.apk", "sha256": "…", "notes": "…"}`
L'appli refuse tout APK dont l'empreinte ou la signature diffère de la clé AIR.

## Compiler (sans Android Studio)
    javac --release 8 -cp android.jar -d classes java/*.java
    java -cp r8.jar com.android.tools.r8.D8 --release --min-api 24 --lib android.jar --output . classes/app/airnavig/*.class
puis reconstruire avec apktool et signer avec la clé `air-navig.jks` (alias `air`).

## Licence
À choisir par Rami (par exemple MIT ou GPL-3.0) avant publication du dépôt.

## Version 3.2 personnalisée (interface uniquement)
Modifications dans `assets/chrome.html` et `assets/home.html` (le code Java n'a pas changé) :
- **Réglages réorganisés** en catégories : Protections, Thèmes et apparence, Barre et boutons, Page d'accueil, Moteur, Autorisations.
- **Anti-pubs / anti-traqueurs activables et désactivables** : interrupteur général « Toutes les protections », interrupteurs séparés (liste StevenBlack, réseaux nommés, liens piégés, sites dangereux, mode discret), **liste de blocage perso**, **pause des protections site par site** (aussi depuis le Bouclier).
- **5 thèmes mélangés** façon « colorways » de Firefox : Aurore boréale, Crépuscule, Abysses (sombres), Barbe à papa, Agrumes (clairs). Auto / Noir / Blanc sont conservés.
- **Apparence** : couleur d'accent (palette ou code #hex), police, arrondis, taille du texte des menus, animations, contours.
- **Barre** : position, taille (compacte/normale/grande), choix des boutons affichés.
- **Accueil** : fond (thème, 10 dégradés, couleur unie, photo importée du téléphone, assombrissement, flou), message de bienvenue avec prénom, message perso, éléments affichés, position et taille, bouton ✎ Personnaliser.
- Menu ⋯ : « Thèmes », « Protections », « Personnaliser l'accueil ».
- Le bouton ✎ Personnaliser ne peut plus être masqué (style complet ou discret) ; entrée « Personnaliser l'accueil » dans le menu ⋯.
- Retirés à la demande : fond par lien, horloge, titre perso, et tous les raccourcis de sites sur l'accueil (avec leurs logos et l'option pour en ajouter).

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

## Onglets verticaux, groupes et mise en veille
- **Onglets verticaux** (Réglages › Barre et boutons › Onglets) : barre latérale à gauche ou à droite, avec recherche, navigation, bouclier et la liste des onglets par groupes. Le bouton ▯ la réduit en rail d'icônes.
- **Groupes d'onglets** : nom + couleur, repliables, menu ⋯ (renommer, nouvel onglet dans le groupe, mettre en veille, dégrouper, fermer). Bouton **Ranger par site** : regroupe automatiquement les onglets d'un même site. Un lien ouvert depuis un groupe reste dans ce groupe.
- **Mise en veille** : chaque onglet récent garde son propre WebView (retour instantané, historique et défilement conservés). Au-delà du nombre choisi (1 à 5), après le délai choisi (5 min à 1 h) ou quand Android manque de mémoire, les onglets cachés sont endormis : leur WebView est détruit et ils se rechargent quand on y revient (icône lune).
