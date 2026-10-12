# "App content" — answers

## Content rating (IARC questionnaire)

| Question | Answer |
|---|---|
| Category | Utility, productivity, communication or other (not a game) |
| Violence, sexuality, language, drugs, gambling | No: the app brings no content of its own |
| User-generated content | No |
| Communication between users | No |
| Sharing of location | No: no location permission is declared |
| Digital purchases | No |
| Advertising | No |
| Unrestricted internet access | **No**: the app has no browser and opens no link. It shows the screen of the user's own computer, as any remote desktop client does |

Expected rating: **Everyone / PEGI 3**.

Store category: **Tools**.

## Target audience and children

| Question | Answer |
|---|---|
| Target age groups | 18 and over |
| Does the app appeal to children? | No: a client for a self-administered Linux desktop |
| Families Policy | Not applicable |

## Ads

None. Answer **"No, my app does not contain ads"**.

## App access

Everything in the app sits behind a connection to a machine, and that machine
is **the user's**: a Linux computer running Hyprland and `gliff-server`,
which has authorised the SSH key of that very device. A reviewer has none of
this, and there is no login and password to hand over: each installation
creates its own key.

The folder provides a **demonstration server** instead:
[`demo-server/`](./demo-server/README.md). It is an SSH server that accepts
any key and plays a recorded desktop through the real protocol, without the
app being modified. **It is written and tested, but not deployed**: it needs
a public host that stays online, since Google uses it again at every update.

Answer "All or some functionality is restricted", and paste
[`demo-server/app-access-en.txt`](./demo-server/app-access-en.txt) as the
instructions, with the server's address in place of `HOST`.

Never leave a dead address in this field: a reviewer stuck on "Connection
lost" rejects the listing.

## Government, finance, health

No to all.

## Data safety

See [`data-safety.md`](./data-safety.md).

## Permission declarations with manual review

**None.** The only permission declared is `INTERNET`.

Two things to watch on later versions:

- a **foreground service** (to keep a session through the background) needs
  a declared type and a justification in the Play Console;
- Android's **local network protection** (`ACCESS_LOCAL_NETWORK`), enforced
  for apps targeting the release after API 36, will be needed to reach a
  machine on the same Wi-Fi.

## Privacy policy

Meant to be published on GitHub Pages by
[`pages.yml`](../.github/workflows/pages.yml), once Pages is enabled on the
repository:

```
https://c4software.github.io/gliff-android/privacy-en.html
https://c4software.github.io/gliff-android/privacy-fr.html
```
