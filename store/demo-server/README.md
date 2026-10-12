# Demonstration server — app access (Play Console)

The *App access* field of the Play Console wants a way past the first screen
for the reviewer. The app is a client for the user's **own** machine: there
is no account to hand over, so a machine has to be provided.

This folder is that machine. It is an `sshd` that lets any key in and, in
place of whatever the client asks to run, starts [`server.py`](./server.py):
a stand-in for `gliff-server` that speaks the protocol and loops a recorded
desktop. The app is not modified: it believes it is mirroring an ordinary
gliff machine.

**It is not deployed anywhere yet.** It needs a host with a public address
that stays up; see *Deployment*.

---

## What it does, and what it does not

| Step of the app | What answers |
|---|---|
| SSH connection with the device's own key | `sshd`, with `AuthorizedKeysCommand` returning the very key offered ([`any-key`](./any-key)) |
| Host key shown on first connection | The server's Ed25519 key, kept in a volume so it never changes |
| Reading the remote keyboard layout | The forced command, which answers nothing: the app falls back to the US layout |
| `gliff-server --stdio …` | `server.py`: `HelloAck` for a mirrored output `DEMO-1`, `Ping`, `StreamConfig` 1280 × 720 H.264, then the pictures of `sample.h264` in a loop, paced by the client's acknowledgements |

The server announces a **mirror**, whatever mode the machine was added with,
so that the app draws the pointer itself: a finger on the screen moves an
arrow, even though nothing behind the picture reacts.

Deliberately absent:

- **Input does nothing.** Keys, clicks and scrolling are read and dropped.
  The recording does not answer them, and the instructions say so.
- **`Resize` is ignored**: the recording has one size. The app fits it to the
  window, upright or lying down.
- **A requested keyframe is not produced**: the recording has one every
  second, which is the longest a new session waits for its first picture.
- **No clipboard.**

A session is closed after 30 minutes, and a connection that sends no `Hello`
within 20 seconds is dropped.

## What the account can do

Nothing but run `server.py`. `ForceCommand` replaces any command, and the
key is authorised with `restrict`: no terminal, no port, agent or X11
forwarding, no `~/.ssh/rc`. The container runs read-only with its
capabilities dropped. What remains exposed is `sshd` itself and about two
hundred lines of Python reading a length-prefixed stream; keep the image
updated.

---

## Deployment

Any host with Docker or Podman and a public address:

```sh
docker build -t gliff-demo store/demo-server
docker run -d --name gliff-demo --restart unless-stopped \
  -p 2222:2222 -v gliff-demo-keys:/keys --read-only --tmpfs /run --tmpfs /tmp \
  --cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add SYS_CHROOT \
  --memory 256m --pids-limit 100 gliff-demo
docker logs gliff-demo        # prints the host key fingerprint
```

Check from anywhere, with any key:

```sh
ssh -p 2222 demo@HOST true; echo $?        # 0, and no output
./gradlew :transport:probe --args="demo@HOST:2222 --accept-new --seconds 5"
```

The second line must report `hello: mirror DEMO-1` and about 30 pictures a
second.

The server has to **stay online**: Google uses it again at every update, not
only at the first submission. A session costs about 0.5 Mbit/s and next to no
CPU.

### What was checked, and what was not

Checked on 12 October 2026, on the development machine: `server.py` driven
directly by the probe, then behind a real OpenSSH `sshd` with this
`sshd_config` (other paths, another port, a local account), through the same
SSH library the app uses, with a key the server had never seen. A terminal
and a port forward were both refused.

**Not checked: the `Dockerfile`**, which was never built (no container engine
on that machine), and the app itself on a device against this server.

---

## The recording

`sample.h264` is eight seconds of a drawn desktop: an editor and a terminal
in which a test run scrolls by. It is made of pictures composed by the
`StoreShots` test and encoded by [`make-sample.sh`](./make-sample.sh)
(ffmpeg, x264). It is fictional and says nothing about anyone's machine.

To change the picture's size, change `WIDTH` and `HEIGHT` in `server.py` with
it.

---

## What to paste in the Play Console

*App access* → "All or some functionality is restricted" → add instructions.
There is no user name and no password to fill in: leave them empty, or put
`demo` and `none` if the form insists.

The instructions go in the listing's default language:
[`app-access-en.txt`](./app-access-en.txt), ready to paste once `HOST` is
replaced by the server's address.
