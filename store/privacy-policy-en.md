# Privacy Policy — Gliff Client

Last updated: 12 October 2026

## Summary

Gliff Client collects nothing and sends nothing to us. It has no server
of ours, no account of ours, no analytics and no advertising. The only
machines it talks to are **the ones you add yourself**, over SSH.

## Data the app handles

All of the following stays on your device, in the app's private storage, and
is deleted when the app is uninstalled:

| Data | Why | Where it goes |
|---|---|---|
| The machines you add: user name, host, port, screen mode, path of `gliff-server` | To connect to them | Local preferences, on the device only |
| The host key fingerprint of each machine | To detect a machine that is not the one you trusted | Local preferences, on the device only |
| The app's SSH key (Ed25519), created on first use | To authenticate to your machines without a password | A private file, excluded from backups. The private half never leaves the device |
| Your settings (display scale, touch mode, clipboard, web mirror and its secret address) | To remember your choices | Local preferences, on the device only |

The app asks for no password and stores none. It never collects your name,
your email address, your contacts, your files, your position or any device or
advertising identifier.

## What travels during a session

While a session is open, the app exchanges the following with **your
machine**, inside the SSH connection, and with nothing else:

- the picture of the remote desktop, which is displayed and not recorded;
- what you type, where you point and click;
- with the "Shared clipboard" option on (the default): text you copy on the
  device is offered to the machine, and text copied on the machine is placed
  on the device's clipboard. Turning the option off stops both.

## The web mirror

The "Web mirror" option is **off by default**. When you turn it on, the app
answers web browsers on the networks the device is connected to, on port
8765, and shows them the open session. With "Control from the browser" also
on, the page can drive the session.

- The page's address contains a random secret. **Anyone who has the address
  sees your screen**, and can control it if you allowed control.
- The link between the browser and the device is plain HTTP: it is **not
  encrypted**. Use it on networks you trust.
- Nothing goes through a server of ours: the browser talks to your device
  directly.

## Outgoing connections

The app opens SSH connections to the machines you added, when you open them,
and to nothing else. Nothing is fetched or sent while no session is open.

## Permissions

| Permission | What it is for |
|---|---|
| `INTERNET` | Reaching your machines over SSH, and answering browsers when the web mirror is on |

## Deleting your data

Deleting a machine in the app removes what is stored about it. Uninstalling
the app removes everything, including its SSH key; remember to remove the
key's line from `~/.ssh/authorized_keys` on your machines.

## Children

The app is not directed at children and collects no data from anyone.

## Changes

Any change to this policy will be published in the application's public
repository, together with the version it applies to.

## Contact

Questions or requests: open an issue on
<https://github.com/c4software/gliff-client/issues>, or write to
<c4software@gmail.com>.
