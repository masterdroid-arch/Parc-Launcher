# Privacy policy: Parc Launcher

Last updated: 2026-10-26

Parc is a home screen for Android. This policy describes the build in this
repository. It is written to be checked against the source, not to make legal
promises the code does not keep.

## The short version

- Parc has **no internet permission**. It cannot send data anywhere.
  The one exception is the separate, optional **weather build**
  (`Parc-<version>-personal-weather.apk`); see "Weather build" below. The normal
  builds never contain its network code.
- There are **no accounts, no advertising, no analytics and no crash upload**.
- Everything Parc reads (apps, notifications, contacts, calendar, media names,
  usage) is read **on the phone, for you**.
- Notification text is kept **in memory only** and is never written to disk.
- Parc's own data is **not copied to Google backup**. Android backup is disabled.
- Backup and diagnostics files leave Parc only when **you** choose where to
  save or share them.

Parc has no server, so there is no account to delete and no server-side data to
remove. Uninstalling Parc or clearing its storage removes everything it saved on
the phone.

## What Parc reads, and why

The permissions below are optional. Parc works without them, with a smaller
feature set.

| Access | What is read | Why | Where it stays |
|---|---|---|---|
| Installed apps (through the Android launcher API) | App names, packages, icons, shortcuts | Show the arcs, folders, search and the All apps list; launch apps | On the phone |
| Notification access (special access, off by default) | App package, notification title and text, and a small one-bit preview image | Unread dots, the long-press preview and the Messages page | Memory only |
| Usage access (special access, off by default) | App launch events, screen time, data use, app storage | Screen time, data figures, storage figures, and ranking apps you launch outside Parc | On the phone |
| Contacts (runtime permission, off by default) | Contact names and phone numbers, only to match your search text | Contact search results | Memory only |
| Calendar (runtime permission, off by default) | Calendar names, event titles and times | The Agenda page and calendar results in search | Memory only |
| Photos, videos and audio (runtime permissions, off by default) | File names, types, dates and folder paths | File results in search and the media picker | Memory only |
| Do Not Disturb access (special access, off by default) | Whether Parc may create its own interruption rule | An optional Focus mode can silence interruptions while it is active | On the phone |
| Screen lock or biometric (normal permission) | The system's own unlock result | Open a folder you chose to lock | The result stays with Android; Parc only learns yes or no |
| Notifications (runtime permission) | Nothing; it lets Parc post a notification | Tell you when a timer finishes | On the phone |
| Wi-Fi state (normal permission) | Signal strength and link speed | The Network page | Memory only |

The `personal` build also declares a permission related to uninstalling an app.
Parc only opens Android's own uninstall screen for an app you selected; it never
removes an app silently. The `play` flavour removes that permission and several
others from its manifest.

Parc does not read location, SMS, call logs, the phone state, the microphone,
the camera, your body sensors, or your files generally. It reads a media
library entry by name, or a file you hand it through the system picker. It does
not use `QUERY_ALL_PACKAGES`; it asks the system only for launchable apps, a
fixed list of Settings pages, and installed icon packs.

## Quick toggles page

The torch is switched through Android's torch control; Parc does not open or
read the camera. Do Not Disturb is switched only with the Do Not Disturb access
you may already have given Focus. Wi-Fi, Bluetooth, airplane mode, auto-rotate,
battery saver and screen timeout are read as on/off or a time, on the phone, to
label the tiles; nothing is stored.

## Weather build

This section applies only to `Parc-<version>-personal-weather.apk`. The normal
`personal` and `play` builds have no Weather page and no internet permission.

- **Who receives data:** Open-Meteo (open-meteo.com), over https, at
  `geocoding-api.open-meteo.com` and `api.open-meteo.com`. No other host.
  Open-Meteo needs no account or key. Its own terms and privacy policy apply to
  what its servers receive, including your IP address, as with any web request.
- **City search:** when you type a city in the Weather page options and tap
  Search, the name you typed is sent once to find matching places.
- **Forecast:** after that, each request contains only the city's latitude and
  longitude, rounded to two decimals (about 1 km), and fixed field names. No
  device identifier, account, contact, app list or other data is sent.
- **When:** at most every 30 minutes, and only while the Weather page is on
  screen with Parc in front. Never in the background.
- **On the phone:** the city name, its region text, latitude, longitude, your
  unit choice and the last forecast answer with its time. "Forget city" in the
  page options removes them. They are not part of Parc backups.
- **No location permission.** Parc never reads your position.

## Messages page

When you grant notification access and choose which apps may contribute, Parc
can show their messages in the stats hub.

- Message text, titles, conversation names and preview images are held **in
  memory only**.
- Nothing is written to a database, a file, a log or a backup.
- The content is dropped when the notification listener disconnects, when you
  turn the Messages page off, or when the Parc process restarts.
- The optional dismissed-message history is also memory-only, is bounded, and is
  cleared when you turn it off.
- Reply actions send your reply through Android's notification action for that
  app. Parc does not send anything itself.

Your choices (which apps may contribute, whether history is kept, and your quick
reply texts) are saved as local settings. Those settings contain no message
content.

## What Parc stores on the phone

- Your layout: arcs, pins, folders, hidden apps and folder locks.
- Settings: skin, gesture choices, focus modes, hub pages and similar.
- Your notes, quick replies, and the allow-list of message apps.
- A launch log used for recents and ranking.
- An optional wallpaper image you picked, if the wallpaper skin is used.

This data stays in Parc's private app storage. Android backup is disabled with
`allowBackup="false"`, so it is not uploaded to Google's backup service.

## Backups and diagnostics

Parc can export a backup, and can export diagnostics, through Android's document
picker. The file is written where you choose. Once it exists, that file is
yours to keep or delete; Parc does not upload it and cannot reach it again unless
you import it.

Diagnostics contain Parc's own log and facts about the phone. Parc's log helper
redacts URLs, addresses, quoted text, package names and long numbers before a
line is stored. Callers are also written not to pass message text, contact names
or app names.

## When data leaves Parc

Only because you asked:

- Opening an app, a contact card, the dialer, a Settings page or a web search.
- Sending a reply through another app's notification action.
- Saving or sharing a backup or diagnostics file.

In those cases the other app or service handles the data under its own policy.
For example, a web search opens your browser with the text you typed.

## Children

Parc is a general-purpose launcher and is not directed at children. It collects
nothing, so it collects nothing from children either.

## Changes

If a future version changes what Parc reads or stores, this document will be
updated before that version is published. The date at the top is the date of the
last check against the source.

## Contact

https://github.com/masterdroid-arch/Parc-Launcher/issues
