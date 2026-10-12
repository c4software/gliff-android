# Politique de confidentialité — Gliff Client

Dernière mise à jour : 12 octobre 2026

## En résumé

Gliff Client ne collecte rien et ne nous envoie rien. Il n'y a ni
serveur à nous, ni compte chez nous, ni mesure d'audience, ni publicité. Les
seules machines auxquelles l'application parle sont **celles que vous ajoutez
vous-même**, en SSH.

## Données manipulées par l'application

Tout ce qui suit reste sur votre appareil, dans le stockage privé de
l'application, et disparaît à la désinstallation :

| Donnée | Pourquoi | Où elle va |
|---|---|---|
| Les machines que vous ajoutez : utilisateur, hôte, port, mode d'écran, chemin de `gliff-server` | Pour s'y connecter | Préférences locales, sur l'appareil uniquement |
| L'empreinte de la clé d'hôte de chaque machine | Pour repérer une machine qui n'est pas celle à laquelle vous avez fait confiance | Préférences locales, sur l'appareil uniquement |
| La clé SSH de l'application (Ed25519), créée au premier usage | Pour s'authentifier auprès de vos machines sans mot de passe | Un fichier privé, exclu des sauvegardes. La partie privée ne quitte jamais l'appareil |
| Vos réglages (échelle d'affichage, mode tactile, presse-papiers, miroir web et son adresse secrète) | Pour retenir vos choix | Préférences locales, sur l'appareil uniquement |

L'application ne demande aucun mot de passe et n'en conserve aucun. Elle ne
collecte jamais votre nom, votre adresse électronique, vos contacts, vos
fichiers, votre position, ni aucun identifiant d'appareil ou publicitaire.

## Ce qui circule pendant une session

Tant qu'une session est ouverte, l'application échange ce qui suit avec
**votre machine**, à l'intérieur de la connexion SSH, et avec rien d'autre :

- l'image du bureau distant, qui est affichée et non enregistrée ;
- ce que vous tapez, là où vous pointez et cliquez ;
- avec l'option « Presse-papiers partagé » activée (c'est le cas par défaut) :
  le texte copié sur l'appareil est proposé à la machine, et le texte copié
  sur la machine est placé dans le presse-papiers de l'appareil. Désactiver
  l'option arrête les deux.

## Le miroir web

L'option « Miroir web » est **désactivée par défaut**. Quand vous l'activez,
l'application répond aux navigateurs web sur les réseaux auxquels l'appareil
est connecté, sur le port 8765, et leur montre la session ouverte. Avec
« Contrôle depuis le navigateur » activé en plus, la page peut piloter la
session.

- L'adresse de la page contient un secret aléatoire. **Quiconque possède
  l'adresse voit votre écran**, et peut le piloter si vous avez autorisé le
  contrôle.
- La liaison entre le navigateur et l'appareil est en HTTP simple : elle
  n'est **pas chiffrée**. À utiliser sur des réseaux de confiance.
- Rien ne passe par un serveur à nous : le navigateur parle directement à
  votre appareil.

## Connexions sortantes

L'application ouvre des connexions SSH vers les machines que vous avez
ajoutées, quand vous les ouvrez, et vers rien d'autre. Rien n'est récupéré ni
envoyé quand aucune session n'est ouverte.

## Permissions

| Permission | À quoi elle sert |
|---|---|
| `INTERNET` | Joindre vos machines en SSH, et répondre aux navigateurs quand le miroir web est activé |

## Supprimer vos données

Supprimer une machine dans l'application efface ce qui est conservé à son
sujet. Désinstaller l'application efface tout, y compris sa clé SSH ; pensez
à retirer la ligne de cette clé de `~/.ssh/authorized_keys` sur vos machines.

## Enfants

L'application ne s'adresse pas aux enfants et ne collecte de données sur
personne.

## Modifications

Toute modification de cette politique sera publiée dans le dépôt public de
l'application, avec la version à laquelle elle s'applique.

## Contact

Questions ou demandes : ouvrez un ticket sur
<https://github.com/c4software/gliff-android/issues>, ou écrivez à
<c4software@gmail.com>.
