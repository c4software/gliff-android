# Plan : implémentation iOS / iPadOS de gliff

## État (12 octobre 2026)

| Phase | État |
|---|---|
| 0a. Outils dans la dev-box | Fait : `devbox dev-env ios` est livré dans l'image (Swift 6.4, xtool 1.21, pymobiledevice3). Reste `xtool setup` (compte Apple, `Xcode.xip`) et l'appairage USB de l'appareil. |
| 0b. Essais | 2 fait : les klibs iOS compilent sous Linux, l'édition de liens y est ignorée (`SKIPPED`), pas en échec. 3 : `Shared.xcframework` se lie sur le runner macOS (25 min) et `scripts/fetch-ios-framework.sh` le récupère ; son en-tête correspond aux appels de l'app Swift. Reste à voir xtool l'accepter et l'écran Compose s'afficher. 1 (hello sur l'iPhone) : attend `xtool setup`. |
| 1. Refactors communs | Fait et testé (JVM, desktop, APK). Reste l'essai de non-régression de l'app Android sur le téléphone. |
| 2. Actuals iOS en Kotlin | Écrit ; compile pour `iosArm64` et `iosSimulatorArm64` sous Linux. Jamais exécuté. |
| 3. App hôte Swift | `GliffSSH` (le client ssh) fait et testé sous Linux contre un vrai `sshd` et contre le portable (Tailscale SSH). L'app elle-même (`iosApp/Sources/Gliff`) est écrite mais n'a jamais été compilée : il faut le SDK iOS et le framework. |
| 4. iPad avancé | Écrit (fenêtre par machine, pointeur masqué) ; à vérifier sur appareil, comme le reste. Le Picture in Picture a été retiré des deux plateformes le 13 octobre 2026, jugé inutile. |
| 5. CI, docs | `ci.yml` et README faits. TestFlight : pas commencé. |

Écarts par rapport au plan, et pourquoi :

- **NIOPosix, pas NIOTransportServices.** Un seul chemin de code, le même
  sous Linux (où il est testé) et sur iOS. `swift-nio-ssh` n'a pas de
  keepalive ssh : la veille est faite par TCP (15 s).
- **Deux vues pour le clavier.** Kotlin ne peut pas redéfinir `inputView`
  (une catégorie d'UIKit) : au lieu d'une vue qui masque son clavier, une vue
  de saisie (`UIKeyInput`) et une vue muette se passent le focus.
- **Un bouton pour fermer la session**, absent d'Android : iOS n'a ni geste
  retour ni barre de titre.
- **`SshBridge` par rappels**, sans rien de bloquant côté Swift ; c'est
  Kotlin qui attend. La décision sur la clé d'hôte reste en Kotlin, Swift ne
  passe que le blob de la clé.
- **Fenêtres iPad en SwiftUI** (`WindowGroup(for:)`), qui pose le
  `NSUserActivity` lui-même.

## Contexte

Le client Android est fait ; le code est déjà en Kotlin Multiplatform pour qu'un
port iPhone/iPad réutilise la même base. Côté iOS il n'existe que deux
placeholders (`shared/src/iosMain`, 29 lignes) : pas de SSH, pas de décodeur,
pas de clavier, pas d'app hôte.

Décisions prises :

- **Pas de Mac.** Build et déploiement depuis la dev-box (Arch, conteneur) avec
  la chaîne de [omarchy-apple-dev](https://github.com/joshuaswarren/omarchy-apple-dev) :
  Swift + xtool + pymobiledevice3, SDK iPhoneOS tiré d'un `Xcode.xip`.
  Conséquences : pas de simulateur, tests sur un vrai appareil uniquement, et
  l'app hôte est un projet **xtool/SwiftPM**, pas un `.xcodeproj`.
- **SSH en Swift** avec `swift-nio-ssh`, derrière une petite interface Kotlin.
- **Périmètre v1** : parité de base (machines, session vidéo, tactile, clavier
  logiciel et matériel, presse-papiers texte) + iPad avancé (une fenêtre par
  machine, pointeur masqué, Picture in Picture). Pas de miroir web.

Deux points ne sont pas vérifiés et conditionnent tout le reste ; la phase 0
les tranche avant d'écrire du code produit :

1. Kotlin/Native ne sait lier un framework Apple que sur macOS. Sous Linux il
   compile les klibs iOS, pas le binaire final. Le framework `Shared` devra
   donc très probablement sortir d'un runner macOS GitHub (gratuit, le dépôt
   est public).
2. xtool doit accepter ce framework comme `binaryTarget` en compilation
   croisée, et lier VideoToolbox, AVFoundation, Metal (Skia de Compose).

## Phase 0 — Faisabilité (go / no-go)

### 0a. Outils iOS dans la dev-box (`../dev-box`)

Oui, sous la forme d'un nouvel environnement `devbox dev-env ios` :
`rootfs/usr/share/devbox/dev-envs/ios.sh` (`details`, `install`, `uninstall`,
`is_installed`, `is_supported`), sur le modèle de `android.sh`. À essayer
d'abord en local dans `~/.config/dev-box/dev-envs/ios.sh`, puis à livrer dans
le dépôt avec une ligne dans `docs/dev-envs.md` et l'annotation du prochain tag.

La règle des dev-envs est « `mise use -g` uniquement, ni pacman ni `curl | sh` »,
alors que l'installeur d'omarchy-apple-dev passe par l'AUR. Répartition visée :

| Outil | Voie dans la dev-box | À vérifier |
|---|---|---|
| Swift 6.x | `mise use -g swift` | que mise fournit un build qui tourne sur Arch ; sinon exception « image » comme PHP |
| xtool | `mise use -g github:xtool-org/xtool` (binaire de release) | omarchy-apple-dev compile un xtool patché (4 correctifs) : voir s'ils sont nécessaires |
| pymobiledevice3 | `mise use -g pipx:pymobiledevice3` | — |
| SDK Darwin | `xtool setup` avec un `Xcode.xip` déposé dans le home (persistant) | mode « full » obligatoire : le mode sans Xcode ne couvre qu'une courte liste de frameworks |
| usbmuxd | `devbox pkg add usbmuxd` + bloc commenté dans `compose.override.example.yaml` (`/dev/bus/usb`) | accès USB depuis un conteneur |

La dev-box étant jointe par Tailscale, le chemin normal d'installation sur
l'appareil est `device-run.sh --over-tailnet` (iOS 27, appairage USB une seule
fois, depuis le portable). Aucune migration dev-box n'est nécessaire : c'est un
ajout.

### 0b. Trois essais, dans l'ordre

1. `xtool new` + `xtool dev run` : un « hello » SwiftUI arrive sur l'iPhone.
2. `./gradlew :shared:compileKotlinIosArm64` sous Linux (les klibs), puis
   tentative de `linkReleaseFrameworkIosArm64` : échec attendu, à constater.
3. Workflow `ios-framework.yml` (runner macOS, `workflow_dispatch` + tags) qui
   produit `Shared.xcframework` ; `scripts/fetch-ios-framework.sh` le
   télécharge dans `iosApp/Frameworks/` ; un écran Compose vide s'affiche sur
   l'appareil.

**No-go** si l'essai 3 échoue sans contournement : on revient en discuter
(UI SwiftUI native au-dessus de la logique Kotlin, ou accès à un Mac).

## Phase 1 — Refactors communs (testables sous Linux, sans appareil)

But : sortir de `jvmMain`/`androidMain` tout ce qui n'est pas lié à la
plateforme, pour que iOS n'ait à fournir que le strict minimum.

- **`SshTarget` en commun.** Déplacer `SshTarget`, `shellQuote` et
  `KEYMAP_SCRIPT` de `transport/src/jvmMain/.../transport/Ssh.kt` vers
  `transport/src/commonMain/.../transport/SshTarget.kt`. Les deux plateformes
  lancent exactement la même commande distante.
- **Format des clés en commun.** `SshWire` : ligne `authorized_keys` à partir
  des 32 octets de la clé publique Ed25519, et empreinte `SHA256:...` à partir
  du condensat. `SshKeys.kt` (JVM) s'en sert ; iOS aussi.
- **Registre des sessions en commun.** Extraire de
  `shared/src/androidMain/.../android/AppGraph.kt` la partie portable
  (`session`, `endSession`, `front`, `Tap`) vers
  `shared/src/commonMain/.../client/Sessions.kt`, le miroir restant un
  `FrameSink` optionnel. L'`AppGraph` Android délègue.
- **Racine d'UI commune.** Un composable `App(...)` avec la navigation
  machines → session, pour iOS ; Android garde ses deux activités.
  `MachinesScreen` accepte `mirrorUrls = null` et masque alors les options du
  miroir.
- **H.264.** Dans `shared/src/commonMain/.../client/H264.kt` : découpe d'une
  unité d'accès en NAL, extraction SPS/PPS, conversion Annex B → AVCC
  (préfixes de longueur), ce que VideoToolbox attend.
- **Touches matérielles.** Table usage HID → code evdev (`HidKeys`), à côté de
  `Keyboard.kt`.

Tests : `SshTargetTest`, `SshWireTest` (comparé à `SshKeys` JVM), `H264Test`
étendu, `HidKeysTest`, `SessionsTest` ; `SessionControllerTest`, `ScreensTest`
et les tests Android existants doivent passer sans changement.

## Phase 2 — Actuals iOS en Kotlin (`shared/src/iosMain`)

- **`IosConnector` / `IosTransport`.** Interface `SshBridge` implémentée en
  Swift : `connect(target, trustedFingerprint)` rend un canal ou un résultat
  d'erreur typé (clé inconnue, clé changée, autre). Kotlin en fait les
  `UnknownHostKeyException` / `ChangedHostKeyException` / `TransportException`
  existants, et adapte le canal (blocs `NSData` poussés par Swift) en
  `ByteSource`/`ByteSink` bloquants avec une file bornée ; `close()` débloque
  une lecture en cours, comme l'exige `Transport`.
- **`VideoToolboxDecoder : VideoDecoder`.** `VTDecompressionSession` créée
  depuis SPS/PPS, une image décodée = un `CVPixelBuffer` posé sur un
  `AVSampleBufferDisplayLayer`, et `onFrameDone(frameId)` dans le callback
  (même contrat que `MediaCodecDecoder` : toute image est acquittée, affichée
  ou non). Session invalidée ou erreur → `onNeedKeyframe()`.
- **`VideoSurface.ios.kt`.** `UIKitView` hébergeant la couche, étirée aux
  bornes comme le `SurfaceView` Android.
- **`SoftKeyboard.ios.kt`.** Une `UIView` invisible conforme à `UIKeyInput`
  (sans correction ni suggestion) pour le clavier logiciel ;
  `pressesBegan/Ended` pour le clavier matériel, via `HidKeys` → `KeySink.raw`.
- **Glue.** `IosClipboard` (`UIPasteboard`, `changeCount` à la place du
  timestamp, `hasStrings` avant toute lecture pour ne pas déclencher la
  bannière « coller »), `UserDefaultsStore : KeyValueStore`, `IosIdentity`,
  `keepAwake` (`idleTimerDisabled`), orientation.
- **Entrée.** `MainViewController(deps)` = `ComposeUIViewController { GliffTheme { App(...) } }`.
- **Gradle.** `binaries.framework { baseName = "Shared"; isStatic = true }` sur
  les trois cibles iOS de `shared/build.gradle.kts`, et la tâche XCFramework.

## Phase 3 — App hôte Swift (`iosApp/`, projet xtool)

- `Package.swift` : `swift-nio-ssh`, `swift-nio-transport-services`, et
  `Shared.xcframework` en `binaryTarget`. `xtool.yml` : bundle id, Info.plist.
- **`SshConnector.swift`** : connexion, vérification de la clé d'hôte
  (empreinte SHA-256, confiance au premier usage), authentification par clé
  Ed25519, premier canal exec pour le keymap distant (délai 6 s, 512 Kio max,
  comme sur JVM), second canal pour `gliff-server --stdio` ; stderr lu en
  continu vers le log ; `TCP_NODELAY` et keepalive 15 s.
- **`KeyStore.swift`** : clé `Curve25519.Signing` de CryptoKit, dans le
  trousseau, `ThisDeviceOnly` (même règle qu'Android : la clé ne suit pas une
  sauvegarde vers un autre appareil).
- **Info.plist** : `NSLocalNetworkUsageDescription`,
  `ITSAppUsesNonExemptEncryption`, orientations, scènes multiples.

Jalon : une session complète sur iPhone (vidéo, tactile, clavier,
presse-papiers).

## Phase 4 — iPad avancé

- **Une fenêtre par machine** : une `UIScene` par session
  (`NSUserActivity` portant l'id de machine), l'équivalent de la tâche par
  machine d'Android ; `Sessions.front()` suit la scène active.
- **Pointeur** : `UIPointerInteraction` avec style masqué pour
  `hiddenPointerIcon()` ; survol, clics et défilement de la souris ou du
  trackpad — à vérifier que les événements pointeur de Compose suffisent,
  sinon reconnaisseurs UIKit sur la vue vidéo.
- **Picture in Picture** : `AVPictureInPictureController` sur la couche
  vidéo, relié au paramètre `pip` de `SessionScreen`.

Comme sur Android, une session est perdue quand le système suspend l'app :
hors périmètre, même ligne « Not done yet » du README.

## Phase 5 — CI, docs, diffusion

- `ci.yml` : ajouter `./gradlew :shared:compileKotlinIosArm64` (Linux, peu
  coûteux) pour que les cibles iOS ne cassent plus en silence.
- README : section iOS (modules, build depuis la dev-box), retirer « the iOS
  app » de « Not done yet » une fois la phase 3 faite.
- TestFlight : `ship.sh --upload` d'omarchy-apple-dev (compte développeur
  payant requis).

## Fichiers clés

| Fichier | Rôle |
|---|---|
| `transport/src/jvmMain/kotlin/.../transport/Ssh.kt`, `SshKeys.kt` | référence du comportement SSH ; source de ce qui passe en commun |
| `transport/src/commonMain/kotlin/.../transport/Transport.kt` | contrat que `IosTransport` respecte |
| `shared/src/commonMain/kotlin/.../client/Platform.kt` | `Connector`, `Identity`, `VideoDecoder`, `FrameSink` |
| `shared/src/androidMain/kotlin/.../android/AppGraph.kt`, `MediaCodecDecoder.kt`, `AndroidClipboard.kt` | modèles pour les équivalents iOS |
| `shared/src/androidMain/kotlin/.../ui/SoftKeyboard.android.kt`, `VideoSurface.android.kt` | modèles des actuals |
| `app/src/main/kotlin/.../android/SessionActivity.kt` | comportements d'hôte à reproduire (PiP, orientation, presse-papiers au focus) |
| `shared/src/iosMain/kotlin/.../ui/*.ios.kt` | placeholders à remplacer |
| `../dev-box/rootfs/usr/share/devbox/dev-envs/ios.sh` | nouvel environnement |

## Vérification

- **Phase 1** : `./gradlew :proto:jvmTest :transport:jvmTest :shared:desktopTest :app:assembleDebug`,
  plus un essai de l'app Android sur le téléphone (aucune régression).
- **Phase 2** : `./gradlew :shared:compileKotlinIosArm64` sous Linux ; le
  framework se lie sur le runner macOS.
- **Phases 3–4** : sur appareil, depuis la dev-box (`xtool dev run` /
  `device-run.sh --over-tailnet --console`), contre le `gliff-server` du
  portable par Tailscale SSH : première connexion (invite de clé d'hôte), clé
  changée, vidéo fluide et acquittements, redimensionnement et rotation,
  clavier logiciel en bépo, clavier matériel, copier-coller dans les deux
  sens, puis sur iPad : deux machines côte à côte, souris, PiP.
- Sans simulateur, aucun test d'UI iOS automatisé : la logique reste couverte
  par les tests communs (`commonTest`, `desktopTest`).
