# Prelude Dash

Tableau de bord Android Auto pour la Prelude F20A : lit le datalog HTS (protocole ISR V3)
via le module Bluetooth branché sur le CN2 de l'ECU, et affiche régime, AFR, températures,
MAP, papillon, éthanol, batterie, avance et injecteurs sur l'écran de l'autoradio.

L'ATOTO F7 WE ne fait tourner aucune appli (Linux fermé) : tout passe par le téléphone et
Android Auto.

## Récupérer l'APK

Chaque modification poussée sur `main` est compilée automatiquement par GitHub Actions.
L'APK prêt à installer se trouve dans l'onglet **Releases** du repo (`PreludeDash.apk`).

## Installation (une seule fois)

### 1. Préparer le téléphone

1. **Options développeur** : Paramètres > À propos du téléphone > Informations sur le logiciel
   > appuie 7 fois sur **Numéro de build**.
2. **Shizuku** : installe-le depuis le Play Store.
3. **UnknownAuto** : télécharge l'APK sur https://github.com/GitMag/UnknownAuto/releases/latest
   et installe-le (autorise l'installation d'applis inconnues quand Android le demande).

Pourquoi : Android Auto refuse les applis qui ne viennent pas du Play Store, même avec ses
sources inconnues activées. UnknownAuto installe l'APK comme s'il venait du Play Store,
et il a besoin de Shizuku pour ça.

### 2. Lancer Shizuku (le téléphone s'appaire avec lui-même)

Le téléphone doit être en Wi-Fi (n'importe lequel, internet pas nécessaire).

1. Ouvre Shizuku > *Démarrer via le débogage sans fil* > **Appairage**. Autorise ses notifications.
2. Paramètres > Options pour les développeurs > **Débogage sans fil** : active-le, puis
   **Associer l'appareil avec un code d'association**. Laisse la fenêtre avec le code ouverte.
3. Descends le volet des notifications : dans celle de Shizuku, tape **Saisir le code** et entre les 6 chiffres.
4. Reviens dans Shizuku et appuie sur **Démarrer**. Il doit afficher *Shizuku est en cours d'exécution*.

Après un redémarrage du téléphone : réactive le débogage sans fil et appuie sur Démarrer, sans nouveau code.

### 3. Installer Prelude Dash

1. Télécharge `PreludeDash.apk` depuis les Releases du repo.
2. Ouvre UnknownAuto > **Charger un APK** > choisis `PreludeDash.apk` > **Installer**.
   Shizuku demande l'accès : choisis **Toujours autoriser**.
3. Si Play Protect propose d'analyser l'appli : *Plus de détails* > *Installer sans analyser*.
4. UnknownAuto doit afficher un **✔** vert : Android Auto acceptera l'appli.

### 4. Activer les sources inconnues dans Android Auto

1. Paramètres du tel > cherche **Android Auto** > ouvre ses réglages.
2. Descends jusqu'à **Version** et appuie dessus une dizaine de fois, confirme le mode développeur.
3. Menu **⋮** en haut à droite > **Paramètres pour les développeurs** > active **Sources inconnues**.

### 5. Premier lancement sur le téléphone

1. Contact mis. Ferme **TunerView** (un seul appareil peut parler au module à la fois).
2. Ouvre **Prelude Dash** sur le tel, autorise le Bluetooth.
3. **Choisir le module** : sélectionne ton module (déjà appairé puisque TunerView marche).
4. Les valeurs doivent bouger dans l'aperçu. Compare avec HTS ou TunerView, surtout l'AFR et la MAP.
5. Section **Trouver l'octet éthanol** : moteur tournant, tape le % affiché dans HTS et appuie
   sur Chercher, puis touche le bon octet.

Pas la voiture sous la main ? Le bouton **Mode démo** fait tourner l'interface avec de fausses valeurs.

### 6. Dans la voiture

Lance Android Auto comme d'habitude, ouvre le lanceur d'applis et touche **Prelude Dash**.
Le bouton **Détails** en haut à droite bascule sur la deuxième page (injection, corrections,
baro, tension sonde...).

### 7. Carte à côté de Waze (v4)

Prelude Dash s'affiche aussi comme une carte « musique » dans la colonne à côté de Waze :
AFR en gros, eau et éthanol, et une alerte rouge en cas de problème (pauvre en charge, eau
au-dessus de 105 °C, voyant moteur, batterie, injecteurs saturés). Les boutons de la carte
et du volant pilotent Spotify, dont le titre s'affiche sous les valeurs.

1. Dans l'appli sur le tel : **Autoriser l'accès aux notifications** (indispensable pour Spotify).
   Option grisée : Paramètres > Applications > Prelude Dash > ⋮ > Autoriser les paramètres restreints.
2. Dans la voiture : choisis Prelude Dash comme source musique dans Android Auto, puis ouvre Waze.
3. Pour le plein écran (compte-tours, 6 valeurs, page Détails), ouvre Prelude Dash depuis le lanceur.

## Mettre à jour

Télécharge le nouvel APK dans les Releases, puis UnknownAuto > Charger > Installer, comme la
première fois (Shizuku doit tourner). Tes réglages sont conservés.

## Réglages par défaut

| Réglage | Défaut | Pourquoi |
|---|---|---|
| Octet wideband | 2 | entrée sonde O2 de l'ECU (D14) |
| Conversion AFR | 0,5 V = 8,5 / 4,5 V = 18,0 | sortie analogique AEM 30-0300 |
| Octet éthanol | 32 | sortie éthanol de la CobraRTP, trouvée sur la voiture |
| Correction éthanol | brut + 14 % (gain 1) | même offset que dans HTS (66 % brut = 80 %) |
| MAP | -70 à 1790 mbar | capteur MAP Honda d'origine |
| Zone rouge | 7000 tr/min | modifiable |
| Taille de trame | 52 octets | protocole ISR V3 |

## Technique

- Protocole : on envoie `0x20` sur le CN2, l'ECU répond une trame de 52 octets (38 400 bauds
  côté module). Positions et conversions reprises de BMDatalogger (bouletmarc).
- Android Auto : appli de catégorie navigation, seule catégorie autorisée à dessiner
  librement sur l'écran de la voiture. Les jauges sont dessinées sur cette surface.
- Le même moteur de rendu sert pour l'aperçu téléphone et l'écran de la voiture.
