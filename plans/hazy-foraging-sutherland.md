# Suite : documenter l'après-installation de `devbox dev-env ios`

Le plan du port iOS lui-même est commité dans `plan/ios.md` (branche `ios`).
Ce fichier ne couvre que l'étape décidée ensuite.

## Contexte

`devbox dev-env ios` (dev-box v1.26) installe Swift, xtool, pymobiledevice3 et
lldb, mais ne dit que deux lignes sur la suite. Rien n'explique quel Xcode
télécharger, comment se déroule `xtool setup` avec le compte Apple, ni comment
appairer l'iPhone. Ces étapes n'ont pas encore été exécutées : elles demandent
le compte Apple de l'utilisateur. Décision : les faire une fois sur la box,
puis écrire la documentation à partir de ce qui a réellement été observé.

## Étapes

1. **Faire le setup sur la box, ensemble**, en notant chaque étape et chaque
   message :
   - téléchargement du `Xcode.xip` (version attendue : Xcode 27 pour
     Swift 6.4, à confirmer) et emplacement dans la box ;
   - `xtool setup` : connexion Apple, double authentification, compte gratuit
     ou payant, construction du SDK Darwin (`swift sdk list` doit montrer
     `darwin`), durée et place disque ;
   - appairage de l'appareil : bloc USB de `compose.override.yaml`,
     `devbox pkg add usbmuxd`, `sudo usbmuxd`, « Trust », mode développeur,
     `xtool devices` ;
   - premier `xtool new` / `xtool dev run` sur l'iPhone ;
   - installation par le réseau une fois appairé (Tailscale).
2. **Corriger ce que le setup révèle** dans
   `../dev-box/rootfs/usr/share/devbox/dev-envs/ios.sh` et dans le bloc USB de
   `../dev-box/compose.override.example.yaml` (non testé à ce jour).
3. **Écrire la documentation dans dev-box** :
   - `docs/ios.md` : le pas-à-pas complet, lié depuis la liste Documentation
     du README et depuis `docs/dev-envs.md` ;
   - `rootfs/usr/share/devbox/skills/devbox/ios.md` : le même contenu pour les
     agents, listé dans `SKILL.md` et dans le tableau de `docs/agents.md` ;
   - fin de `install()` de `ios.sh` : renvoyer vers `docs/ios.md` et le guide
     du skill au lieu des deux lignes actuelles.
4. **Publier** : commit sur une branche, fusion, tag `v1.27` avec une ligne
   d'annotation, sur demande.

## Vérification

- `shellcheck -e SC1091,SC2088` sur les scripts modifiés.
- `devbox dev-env ios` relancé sur la box : le message final pointe vers la
  doc, l'installation reste idempotente.
- La doc est relue contre les notes prises à l'étape 1 : chaque commande
  citée a été exécutée telle quelle.
- Ceci valide aussi l'essai 1 de la phase 0 de `plan/ios.md` (un « hello »
  SwiftUI sur l'iPhone).
