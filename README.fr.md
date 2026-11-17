[English](README.md) · **Français**

# Relay pour Android

Client Android natif (Kotlin, Jetpack Compose) d'un serveur **Relay**, gestionnaire de connexions auto-hébergé
(SSH, RDP, VNC, consoles Proxmox, coffre d'identifiants, synchronisation Proxmox/NetBox). Même périmètre que
l'app iPhone : voir [docs/PARITE.md](docs/PARITE.md).

## Fonctions

- **Appairage par QR code** (Relay web → Administration → Appareils), lien `relay://pair` ou saisie manuelle.
  Le jeton d'appareil est chiffré par le Keystore Android ; HTTPS obligatoire.
- **Connexions** : arborescence, recherche, favoris, récents, témoins de disponibilité.
- **Sessions SSH, RDP, VNC et consoles Proxmox** dans l'app (protocole Guacamole, rendu natif) :
  pavé tactile ou toucher direct, pincer pour zoomer, clavier avec barre Échap/Ctrl/Alt/flèches/F1–F12,
  clavier physique, presse-papiers, plusieurs sessions ouvertes, maintien en arrière-plan (notification).
- **Fichiers** : SFTP en SSH, lecteur « Relay » en RDP (envoi depuis le téléphone, téléchargement, partage).
- **Disponibilité**, **coffre** (empreinte/visage/code, TOTP), **commandes SSH** sur plusieurs serveurs,
  **enregistrements** (lecteur), **espaces de travail**, **connexion rapide**, **journal**.
- **Édition** des entrées, dossiers (identifiants hérités), coffre et pièces jointes ;
  **administration** : sources de synchro, doublons, import RDM, sauvegarde chiffrée.

La gestion des appareils reste réservée à Relay web (un téléphone perdu ne doit pas pouvoir en appairer d'autres).

## Installation

Téléchargez `Relay-X.Y.Z-N.apk` depuis la page [Releases](../../releases) et installez-le (autorisez l'installation
depuis votre navigateur ou votre gestionnaire de fichiers). Android 8.0 ou plus récent. Les mises à jour s'installent
par-dessus la version précédente. Vous pouvez vérifier le fichier avec `SHA256SUMS`.

## Prérequis serveur

- [Relay](https://github.com/overnite22/relay-server-prod) dans une version qui propose **Administration → Appareils** (jetons d'appareil).
- Si Relay est derrière un SSO (oauth2-proxy…), une exception sur `/api/` : l'app s'authentifie par jeton.

## Développement

```
./gradlew :relaykit:test              # logique partagée (JVM)
./gradlew :app:testDebugUnitTest      # rendu au pixel et parcours (Robolectric, sans émulateur)
./gradlew :app:recordRoborazziDebug   # idem, avec captures dans app/screenshots/
APP_VERSION=0.4.0 APP_BUILD=1 ./gradlew :app:assembleRelease
```

- `relaykit/` : modèles, client API (OkHttp, sans redirection), appairage, protocole Guacamole, enregistrements,
  commandes, administration. Aucune dépendance Android.
- `app/` : interface Compose, rendu Guacamole sur `Bitmap`/`Canvas`, écran distant, sessions.
- Les tests n'utilisent que des données fictives (`example.com`, adresses `192.0.2.x`).

La version signée utilise une clé conservée hors du dépôt (`~/.relay-android-signing/keystore.properties` sur la machine de build) :
garder toujours la même clé, sinon Android refuse la mise à jour.

Sous licence Apache 2.0.
