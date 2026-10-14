# ARCHITECTURE.md — Technical architecture

How the client is built, and why. What it does is in [README.md](./README.md);
the wire protocol is in [docs/protocol-notes.md](./docs/protocol-notes.md).

The structure follows that of FreshRSS-Discover (`:domain` decides, the rest
displays, stores and calls), adapted to one constraint that project does not
have: the code is shared with an iPhone and iPad app through Kotlin
Multiplatform. Each divergence from it is stated where it applies.

---

## 1. Module split

```
:proto      Pure Kotlin — the gliff wire format (CBOR, messages, framing)
:domain     Pure Kotlin — decides
:transport  ssh, the protocol session, the web mirror server
:shared     data · di · presentation, for Android, iOS and the desktop tests
:app        Android — the activities around :shared
iosApp      Swift — the iOS host around :shared, and its ssh client
```

### 1.1 `:domain` has nothing to depend on

`:domain` is a Kotlin Multiplatform module (JVM and iOS targets) whose only
dependencies are `:proto` and `kotlinx-coroutines-core`. Compose, ssh,
serialization and the platform APIs are not on its classpath: depending on
one is a **compilation error**, not a review remark.

This is the same guarantee as FreshRSS-Discover's `kotlin("jvm")` module,
obtained differently: a JVM-only module cannot be compiled for iOS, and the
domain is precisely what the two apps must share.

What this guarantees:

- the decisions are tested without a device, a window or a network, in
  milliseconds: input mapping, geometry, clipboard exchange, address parsing;
- no rule can depend on a `Context`, a `UIViewController` or a preference
  file.

### 1.2 Why `:domain` depends on `:proto`

FreshRSS-Discover keeps every detail of its remote API below the data layer.
Here, one part of the protocol does cross into the domain, deliberately: the
**messages** of `:proto` (`ClientMsg`, `ClipboardMsg`, …). A key typed, a
finger moved, a clipboard offered *are* gliff messages: the trackpad's job is
to produce `PointerMove`s, and translating them into a domain type and back
would be a copy of `:proto` for nothing.

What stays below the domain is everything else of the connection: ssh, host
key checks, the handshake, framing, the read and write loops — `:transport`,
behind the `Connector` contract.

### 1.3 Why `:transport` is separate from `:shared`

`:transport` runs without Compose: the `probe` CLI, the fake server and the
web mirror test tool use it on a bare JVM. Merging it into `:shared` would
make each of them carry the UI toolkit.

---

## 2. Layers and dependencies

```
Screen (Compose)                 :shared/presentation
 ↓ immutable UiState
ViewModel                        :shared/presentation
 ↓
Repository (interface)           :domain
 ↓ implementation
Repository, Sessions             :shared/data
 ↓
KeyValueStore · Connector · VideoDecoder · ClipboardWriter
 ↓
SharedPreferences / NSUserDefaults · sshj / swift-nio-ssh · MediaCodec / VideoToolbox
```

The dependencies **all point towards `:domain`**. `:transport` depends on it
too, for the two types it shares with the rest (`Viewport`, `HostKeyInfo`).

> **There is no use case class**, as in FreshRSS-Discover: a use case that
> merely relayed a call would be structure for later. The decisions live in
> `:domain` as pure functions and plain classes — `remoteMode`, `areaScale`,
> `Machine.edited`, `Trackpad`, `ClipboardSync` — called by whoever needs them.

### 2.1 The session engine is data, not domain

`SessionController` and `Sessions` hold the connection, its coroutines and
its decoder: they are the equivalent of a repository implementation, and
live in `:shared/data`. What they decide is delegated to the domain:
`remoteMode` for the size asked of the server, `Viewport.laidLike` for an
imposed resolution, `ClipboardSync` for the clipboard exchange.

Sessions outlive their screens: a rotation, a window resize or a recreated
activity must not cut a connection. `Sessions` is therefore a singleton of
the graph, on the application's scope, and a screen only *holds* a session
through its ViewModel (§6.2).

---

## 3. Dependency injection — Koin

FreshRSS-Discover uses Hilt, whose graph is checked at compile time. Hilt
does not exist outside Android; Koin runs on every Kotlin Multiplatform
target, and its ViewModel support (`koin-compose-viewmodel`) builds on the
multiplatform `androidx.lifecycle`. The price is that a missing binding is a
**runtime** error: `AppTest.theGraphMakesEveryViewModel` builds the whole
graph and every ViewModel, so that it fails in a test rather than on a
device.

Koin is held at **4.1.1**: 4.2 requires Kotlin 2.3 and Compose
Multiplatform 1.10, and the lifecycle library is the one it is built against
(2.9.3).

Modules, all in `shared/src/*/kotlin/…/di/`:

| Module | Provides |
|---|---|
| `dispatcherModule` | `AppDispatchers` — main and IO |
| `timeModule` | The `Clock` |
| `coroutineScopeModule` | The application's `CoroutineScope` |
| `dataModule` | The domain's repositories bound to their implementations, `Sessions` |
| `presentationModule` | The ViewModels |
| `androidModule(context)` | Storage, ssh key, ssh, decoder, clipboard, log, web mirror |
| `iosModule(bridge)` | The same for iOS, without the web mirror |

`appModules(platform)` assembles them. The graph is started once per process:
by `GliffApplication` on Android, by `IosGraph` on iOS.

### 3.1 Dispatchers injected, never referenced

`kotlinx.coroutines.Dispatchers` is named in one place of the app:
`DispatcherModule`. Everywhere else a dispatcher is received — including by
`transport.Session`, which used to pick `Dispatchers.IO` itself.

`AppDispatchers` is one type with a field per dispatcher, rather than
qualified `CoroutineDispatcher`s as in FreshRSS-Discover: two qualifiers of
the same type can be swapped without the compiler noticing, two fields
cannot.

The `probe` command-line tool of `:transport` is its own entry point, outside
the graph, and chooses its dispatcher there.

### 3.2 A single source of time

The wall clock (`kotlin.time.Clock.System`) is read only in `TimeModule`.
Everything else receives a `Clock` (`:domain`); tests pass a lambda.

One exception, kept: the long press of the session screen measures an
elapsed duration with `TimeSource.Monotonic`, inside the pointer loop, next
to the events' own uptimes. It is not the wall clock, and nothing outside the
gesture reads it.

---

## 4. Network access

The ssh connection differs by platform — sshj on Android, swift-nio-ssh on
iOS through `SshBridge` — and is reached only through `Connector`, which
returns a `Transport`. Trust is on first use: the connector throws
`UnknownHostKeyException` or `ChangedHostKeyException`, which the session
turns into a `SessionState.HostKeyPrompt`.

None of these leave the data layer as exceptions: a session's failure
reaches the screen as a `SessionError` (§7).

---

## 5. Persistence

Two stores, without overlap:

| Store | Contents |
|---|---|
| `KeyValueStore` (SharedPreferences, NSUserDefaults) | The machines, the options |
| Private file (Android), keychain (iOS) | The device's ssh key |

### 5.1 The stored format is frozen

The machines and the options are stored as JSON, through
`MachineRecord` and `SettingsRecord`: the domain models carry no
serialization annotation, and a persistence detail cannot shape them.

The records' field names and defaults are **those of the versions already
installed**. Renaming one loses what users saved; adding one needs a default.
`StoredRepositoriesTest` pins the written JSON, and the reading of a list
written by an earlier version.

### 5.2 The ssh key never leaves the device

It is excluded from backups on Android (`noBackupFilesDir`) and kept in the
keychain "this device only" on iOS: a restored backup must not carry it to
another device.

---

## 6. Presentation

### 6.1 One immutable state per screen

Each screen has a `…UiState` produced by its ViewModel, and is split in two:

- a **route** (`MachinesRoute`, `SessionRoute`) obtains the ViewModel,
  collects its state and wires the callbacks;
- a **content** (`MachinesContent`, `SessionContent`) is stateless: it
  displays what it is given. `ScreensTest` and `StoreShots` render the
  contents with hand-made states, without injection — which is what
  FreshRSS-Discover's private `@Preview`s are for.

A ViewModel that **observes** sources publishes with `UiStateSharing`
(`WhileSubscribed(5 s)`): with no one on screen, the observation stops; the
grace covers a rotation.

What stays in the composables is presentation state no one else needs:
which dialog is open, whether the keyboard is up, the screen laid down, the
pinch zoom. The gesture recognisers (`Trackpad`, `Keyboard`) are domain
classes the screen feeds, as FreshRSS-Discover's screen feeds `ReadDetector`.

### 6.2 A session's ViewModel ends it

`SessionViewModel` is the screen's hold on a session of `Sessions`. It ends
the session when it is **cleared**, which is when its window is gone for
good — not when it is recreated:

- on Android, the ViewModel belongs to `SessionActivity`, and is cleared
  when the activity finishes (the user closed the window), not on a rotation
  or a resize;
- on iOS, `App` gives the session its own `ViewModelScope`, cleared when the
  user leaves the session.

`end()` does the same at once, for a user who leaves explicitly; it acts only
once, so that a window that has left cannot end a session reopened since.

### 6.3 Navigation

Android has one activity per screen: `MainActivity` for the machines,
`SessionActivity` per machine, each in a task of its own so several machines
sit side by side in desktop mode. iOS has one window per machine on an iPad,
or one window for everything on a phone: `App` switches between the
machines and the session that was opened.

---

## 7. Errors

An error goes through three forms, and only the last is shown:

```
Exception (ssh, protocol)            :transport, :shared/data
        ↓ translated by SessionController
SessionError (sealed)                :domain
        ↓ translated by Strings.failure
Message on screen                    :shared/presentation
```

`SessionError` distinguishes the causes that call for a different message
or a different action: a machine removed, a server that closed the
connection, an incompatible version (retrying is pointless), a connection
failure. The text the other end gave is kept as `detail` and shown as it
is — that is what lets a user tell `command not found` from a refused key.

No sentence meant for the user is written below the presentation layer:
they are all in `Strings`, in English and in French.

---

## 8. Tests

| Scope | Where | What is tested |
|---|---|---|
| Wire format | `proto/src/commonTest` | Encoding, framing, the reference vectors |
| `:domain` | `domain/src/commonTest`, `jvmTest` | Input mapping, geometry, clipboard, machines |
| Transport | `transport/src/jvmTest` | Session loops, ssh against a local `sshd`, the mirror |
| Data | `shared/src/commonTest`, `desktopTest` | Storage format, sessions against a scripted server |
| ViewModels | `shared/src/commonTest`, `desktopTest` | State and what reaches the session |
| Screens and graph | `shared/src/desktopTest` | Rendering off-device, the whole Koin graph |

The doubles are hand-written fakes (`MemoryStore`, `FakeDecoder`, `Peer`),
not generated mocks.

---

## 9. Map of the repository

Packages and their role — a list of files would be wrong by the next commit.

```
domain/                        Pure Kotlin — decides
├── clipboard/                 the clipboard exchange with the remote
├── identity/                  the device's ssh key, as a contract
├── input/                     keys, layouts, the trackpad, the browser's events
├── machine/                   a machine, its address, its contract of storage
├── session/                   session state and errors, geometry, viewport
├── settings/                  the options and their contract of storage
└── time/                      Clock

shared/src/
├── commonMain/…/data/
│   ├── clipboard/             where text copied on a remote goes
│   ├── local/                 the key-value store and the stored repositories
│   ├── mirror/                the web mirror, as a contract
│   ├── session/               Sessions, SessionController, Connector
│   └── video/                 the decoder contract, H.264 rewriting
├── commonMain/…/di/           one Koin module per family
├── commonMain/…/presentation/
│   ├── machines/              the machine list
│   ├── navigation/            the one-window root (iOS)
│   ├── session/               the remote screen
│   ├── settings/              the options dialog
│   └── theme/                 colours, Strings
├── androidMain/               Android implementations of data/, androidModule
├── iosMain/                   iOS implementations of data/, iosModule, the iOS entry point (ios/)
└── desktopMain/               stand-ins for the platform views, for the tests
```

### 9.1 Not migrated: Kotlin Toolchain

The Kotlin Toolchain (0.13, October 2026) is recommended by JetBrains for new
projects, while the gaps for existing Gradle projects are being closed. Here
it would lose, for now:

- the version code and name derived from the git tag — it takes values
  typed into `module.yaml`;
- publishing to the Play Store's internal track (`gradle-play-publisher`);
- the `probe` and `toolsClasspath` tasks, used by `scripts/fake-server.sh`;
- the static XCFramework that the xtool-built Swift app links — its iOS app
  needs an Xcode project and a Mac.

The migration is to be reconsidered when these have an equivalent.
