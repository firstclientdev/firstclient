(readme fait avec de l'ia me petez pas les couilles j'avais la flemme ok?)
allez voir tutoriel.md aussi
# FirstClient 

> **Avertissement.** Ce mod est fourni tel quel, pour un usage personnel et
> éducatif. L'auteur n'est pas responsable de ce que vous en faites :
> bannissement d'un serveur, perte d'objets, sanction du staff ou tout autre
> problème lié à son utilisation. Vous l'utilisez à vos propres risques.
> Lisez toujours le règlement du serveur avant d'activer un module
> d'automatisation.

Mod client Minecraft Fabric 1.21.1 pensé pour le serveur **FirstSky**.
Il automatise les tâches répétitives du jeu : passer les contrôles anti AFK,
farmer les mobs en boucle et récolter les champs, le tout depuis une
interface sombre entièrement en français.

Le mod est 100 % côté client : il ne modifie rien côté serveur et ne demande
aucun mod serveur pour fonctionner.

## Fonctionnalités

### AFK : réponse automatique aux contrôles

Le serveur téléporte les joueurs suspects vers le spawn pour vérifier leur
présence. FirstClient détecte ce téléport, même entre deux mondes (par
exemple depuis le monde OneBlock vers l'Overworld), puis rejoue
automatiquement vos commandes après un délai réglable.

* position cible XYZ configurable, tolérance en blocs, dimension requise
* une ou plusieurs commandes, chacune avec son ordre, son délai et son
  interrupteur (`/home`, `/warp spawn`, `/sit`…)
* délai fixe ou aléatoire avec min et max, cooldown anti boucle
* détection très légère : quelques comparaisons par tick, zéro allocation,
  aucun déclenchement quand on marche simplement dans la zone

### Mob Farm : frappe en boucle (touche G)

Verrouille le mob vivant le plus proche puis le frappe en boucle jusqu'à
20 coups par seconde. Idéal sur les serveurs qui empilent les entités
(`x100 piglins` qui descend à `x99`…).

* regarde la cible à chaque tick avant de frapper
* reste verrouillé sur son mob et ignore tous les autres
* sélectionne toute seule la première épée de la barre d'action, puis
  restaure votre slot à la coupure
* se coupe toute seule si la cible disparaît ou sort de portée

### Auto Farm : récolte automatique (touche H)

Scanne les cultures autour de la hauteur du joueur sur 2 chunks de rayon,
affiche un contour vert sur les cultures mûres et blanc sur celles en
pousse, marche jusqu'à la plus proche, la casse avec votre houe, puis passe
à la suivante.

* détection générique : blé, carottes, patates, betteraves, nether wart,
  cacao, baies, melons, potirons, canne à sucre, cactus, bambou…
* sélection auto de la houe, replantation laissée au Harvester du serveur
* se coupe toute seule quand il n'y a plus rien à récolter, en cas de lave,
  de chute, ou si la houe disparaît

### Interface et HUD

* menu moderne à thème sombre, entièrement en français, avec les onglets
  AFK, Mob Farm et Auto Farm (touche Right Shift par défaut)
* overlay discret en haut à droite : une ligne de texte par module actif,
  sans fond ni cadre, masqué automatiquement avec F1
* configuration en JSON, sauvegardée automatiquement et rechargée au
  démarrage, avec secours propre si le fichier est corrompu

## Installation

1. Installez Minecraft 1.21.1 avec Fabric Loader 0.16.x et Fabric API.
2. Téléchargez `firstclient-1.5.3.jar` depuis la page Releases.
3. Placez le fichier dans le dossier `mods` puis lancez le jeu.
4. Retenez les touches : Right Shift pour le menu, G pour Mob Farm,
   H pour Auto Farm. Tout est rebindable dans Options, Contrôles.

## Touches par défaut

| Touche      | Action             |
| ----------- | ------------------ |
| Right Shift | Ouvrir le menu     |
| G           | Activer Mob Farm   |
| H           | Activer Auto Farm  |

## Configuration

Le fichier `firstclient.json` se trouve dans le dossier `config` de
Minecraft. Tous les réglages du menu y sont sauvegardés : position cible,
tolérance, commandes et délais, portée et cadence de Mob Farm, portée
d'Auto Farm. Inutile de l'éditer à la main, le menu s'en charge, mais tout
y reste lisible et modifiable.

## Versions utilisées

| Composant     | Version          |
| ------------- | ---------------- |
| Minecraft     | 1.21.1           |
| Yarn mappings | 1.21.1+build.3   |
| Fabric Loader | 0.16.14          |
| Fabric API    | 0.102.0+1.21.1   |
| Fabric Loom   | 1.7.4            |
| Gradle        | 8.10.2 (wrapper) |
| Java          | 21               |

## Architecture du code

```text
com.firstclient/
├── FirstClient.java            # point d'entrée côté client
├── config/                     # configuration JSON persistante
├── afk/                        # détection de téléport + commandes auto
├── mobfarm/                    # verrouillage + frappe en boucle
├── autofarm/                   # scan cultures + marche auto + ESP
├── hud/                        # overlay des modules actifs
├── keybind/                    # touches configurables
├── screen/                     # menu sombre en français
└── util/                       # sélection d'outils, notifications
```

## Compiler soi même

Prérequis : un JDK 21 installé et une connexion internet (le premier build
télécharge Minecraft et les dépendances Mojang et Fabric).

```bash
git clone https://github.com/firstclientdev/firstclient.git
cd firstclient
./gradlew build        # Linux et macOS
gradlew.bat build      # Windows
```

Le fichier final se trouve dans `build/libs/firstclient-*.jar`. Copiez le
dans votre dossier `mods` et lancez le jeu. Pour tester directement dans un
client de développement : `./gradlew runClient`.

Note : le premier build prend plusieurs minutes. Les suivants sont bien
plus rapides grâce au cache Gradle. Pour copier le jar automatiquement vers
votre instance de test après chaque build, créez un fichier
`local.properties` (jamais versionné) à côté de `build.gradle` avec :
`firstclient.modsDir=C:/chemin/vers/votre/instance/mods`.

## Licence

MIT, voir le fichier LICENSE.
