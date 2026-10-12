# "Data safety" form — answers

For **Play Console → App content → Data safety**. Each answer can be backed
by the code: no telemetry dependency, no analytics SDK, no advertising
identifier, and no host contacted other than the ones the user adds.

## Collection and sharing

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable: nothing is sent to the developer |
| Do you provide a way for users to request that their data is deleted? | Not applicable: there is no account and nothing held by the developer. Deleting a machine or uninstalling removes what is on the device |

The point to be ready to defend: the app does **transmit** keystrokes,
pointer events and clipboard text, but to **the user's own machine**, over
the SSH connection the user set up, and never to the developer or a third
party. Play does not count as collection a transfer to a destination the user
names and controls. It is the position of any SSH or remote desktop client.

If the form refuses "No" and asks for a detail per category, no box is to be
ticked: nothing comes to us.

## Data kept on the device (not collection)

| Data | Nature | Storage |
|---|---|---|
| Machines: user, host, port, mode, `gliff-server` path, host key fingerprint | Connection settings | `SharedPreferences`, private storage, clear text |
| The app's Ed25519 SSH key | Secret | PKCS#8 file in `noBackupFilesDir`, private storage, **not encrypted by the Android Keystore** |
| Settings, including the web mirror's token | Preferences | `SharedPreferences`, private storage, clear text |

`android:allowBackup="false"`: none of it goes into a device backup.

No video frame, keystroke or clipboard content is written to storage.

## Points a reviewer may raise

- **The web mirror is an unencrypted HTTP server on the device** (port 8765).
  It is off by default, its address carries a 10-character random token, and
  the option's text says that whoever has the address sees the screen. With
  "Control from the browser" on, the address also gives control. This is the
  weakest point of the app from a security review's view, and it is stated in
  the listing and in the privacy policy rather than left to be found.
- **The clipboard is read while a session is on screen**, when the option is
  on (the default), to offer copied text to the remote. Android shows its own
  "pasted from your clipboard" notice when that happens. Text copied on the
  remote, passwords included, lands on the device's clipboard.
- **The SSH private key is stored unencrypted** in app-private storage. An
  attacker needs root or a device exploit to read it; hardening it with the
  Keystore is open work.
- **No third-party analytics**: no Firebase, no Crashlytics, no advertising
  identifier.
- **No account of ours.** Authentication is the user's SSH key against the
  user's machine.
- **No background work today**: no service, no scheduled job. If a foreground
  service is added to keep a session alive in the background, `app-content.md`
  has to be revisited: that permission has its own declaration.
