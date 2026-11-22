# Privacy policy: Parc Launcher

Last updated: 2026-11-17

Parc is a home screen for Android. This policy describes the build in this
repository. It is written to be checked against the source, not to make legal
promises the code does not keep.

## The short version

- Parc is **free on every channel**, the same app, with no locked features and no
  Play Billing. Donations are the only money; see "Free and donations" below.
- The public GitHub release is the **family internet build**. It uses the
  internet only for Weather and Translate (your own DeepL key), and never for
  accounts, ads, analytics or tracking. The `personal` and `personalWeather`
  builds also declare the `INTERNET` permission, for the private auto update
  and the language data packs described below. The `play` build leaves Weather,
  Translate, updates and packs out and does not declare the `INTERNET`
  permission. Replies and web searches can hand data to another app when you ask.
- There are **no accounts, no advertising, no analytics, no tracking and no
  crash upload**.
- Everything Parc reads (apps, notifications, contacts, calendar, media names,
  usage) is read **on the phone, for you**.
- Messages from WhatsApp, Telegram and Instagram are **stored on this phone** by default: text and
  links only, no images, never in backups. Live notification previews stay in memory.
- Parc's own data is **not copied to Google backup**. Android backup is disabled.
- Backup and diagnostics files leave Parc only when **you** choose where to
  save or share them.

Parc has no server, so there is no account to delete and no server-side data to
remove. Uninstalling Parc or clearing its storage removes everything it saved on
the phone.

## Free and donations

Parc is not sold on any channel. There is no Play Billing and no locked feature.
The only money is optional donations, through a link in About. If the owner has
not set a link, that row is hidden. Opening a donation link leaves Parc only when
you tap it; the browser or donation service then handles the request under its
own policy. Parc stores no payment or donation data.

## What Parc reads, and why

The permissions below are optional. Parc works without them, with a smaller
feature set.

| Access | What is read | Why | Where it stays |
|---|---|---|---|
| Installed apps (through the Android launcher API) | App names, packages, icons, shortcuts | Show the arcs, folders, search and the All apps list; launch apps | On the phone |
| Notification access (special access, off by default) | App package, notification title and text, and a small one-bit preview image | Unread dots, the long-press preview and the Messages page | Live preview in memory; the Messages history is stored on the phone (text and links only, no images, not in backups) |
| Usage access (special access, off by default) | App launch events, screen time, data use, app storage | Screen time, data figures, storage figures, and ranking apps you launch outside Parc | On the phone |
| Contacts (runtime permission, off by default) | Contact names and phone numbers, only to match your search text | Contact search results | Memory only |
| Calendar (runtime permission, off by default) | Calendar names, event titles and times | The Agenda page and calendar results in search | Memory only |
| Photos, videos and audio (runtime permissions, off by default) | File names, types, dates and folder paths | File results in search and the media picker | Memory only |
| Do Not Disturb access (special access, off by default) | Whether Parc may create its own interruption rule | An optional Focus mode can silence interruptions while it is active | On the phone |
| Screen lock or biometric (normal permission) | The system's own unlock result | Open a folder you chose to lock | The result stays with Android; Parc only learns yes or no |
| Notifications (runtime permission) | Nothing; it lets Parc post a notification | Tell you when a timer finishes or a note reminder is due, and keep the read-aloud controls in the shade | On the phone |
| Physical activity (runtime permission, off by default) | The phone's step counter | Steps and daily records | On the phone |
| All-files access (special access, optional in normal and weather builds; absent in Play) | Names, paths and metadata of supported books on shared storage; selected book contents | Find books, Files search and the reader | On the phone; the system document picker works without this access |
| Wi-Fi state (normal permission) | Signal strength and link speed | The Device page | Memory only |

The `personal` build also declares a permission related to uninstalling an app.
Parc only opens Android's own uninstall screen for an app you selected; it never
removes an app silently. The `play` flavour removes that permission and several
others from its manifest.

Parc does not read location, SMS, call logs, the phone state, the microphone,
the camera or health sensors. The Steps page reads the step counter only with
your permission. Book access is explained below; media search reads file names. It does
not use `QUERY_ALL_PACKAGES`; it asks the system only for launchable apps, a
fixed list of Settings pages, and installed icon packs.

## Home layout

Parc stores on the phone the arrangement of apps and folders on the arcs, which apps
are hidden, folder lock flags, the **Lock layout** switch, and whether Home is
mirrored for the left hand. The arrangement and those switches are in Parc
backups; nothing about the layout leaves the phone otherwise.

## Search

Search keeps your last searches and the optional source switches on the phone.
You can give an app a second name (an alias) from its search long-press menu;
the alias is stored on the phone and travels in a Parc backup. Notes that match a
typed search are shown beside the app results; their text is read on the phone
and is never sent anywhere.

## Library and reader

Find books in the normal and weather builds offers all-files access after an
explanation. It is optional, requested from Find books, and never granted by
Parc itself. Play uses the system document picker; refusing all-files access
also falls back to that picker. Scanning starts when you ask, never automatically.
With access, Files search can find supported books by name on shared storage.

Find books lets you choose the folders Parc may scan. Only those folders are
read. Parc remembers the list of files it found there, so the screen opens
quickly and refreshes in the background, and it remembers the folders you chose
(and, for a folder picked with Android's picker, the access Android granted).
This list and the chosen folders stay on the phone and are not in backups.

Only books you choose join the library. Parc reads their metadata and content
on the phone, caches covers, and stores the list, file locations and reading
positions. A PDF, EPUB or text file opened from another app does not join the
library unless you add it; its reading place is remembered for 30 days. A comic
(CBZ) joins only when you add it from the library. Reader text size, spacing,
font, page colour, night mode, volume-key choice and read-aloud speed are saved,
along with whether reading stops at the end of a chapter. Reading aloud uses the
phone's own text-to-speech voice; with a voice
installed it works offline, and Parc neither records nor sends the book text
anywhere. Bookmarks and highlights
you make are saved with the book's reading data: for a highlight, the position,
the highlighted text and any note you attach. Add to Commonplace copies a highlight
into your kept passages only when you confirm it. Remote book resources are
not fetched. Removing a book from the library does not delete its file.

You can make your own shelves, rename them, change their order and delete them.
A book can be on several shelves. A shelf can have rules (file type, folder or a
name that contains a word), and books that match land on it automatically. Parc
stores the shelf names and order, those rules, which books are on each shelf,
whether a book is marked finished or unread, and whether the reader reached its
last page. It also keeps the list of files already checked for automatic shelf
adding; that list stays on the phone and is not in backups.
Deleting a shelf removes only the shelf and its places on it, never a book or
its file. The book list, reading positions for library books, bookmarks and
highlights (including their text and notes), your shelves, shelf rules, finished
marks and reader choices are in Parc backups; book files, cover images, the
remembered scan list, the automatic-add list, chosen folders and Android
file-access grants are not.

## Controls page

The torch is switched through Android's torch control; Parc does not open or
read the camera. Do Not Disturb is switched only with the Do Not Disturb access
you may already have given Focus. Wi-Fi, Bluetooth, airplane mode, auto-rotate,
battery saver and screen timeout are read as on/off or a time, on the phone, to
label the tiles; nothing is stored.

## Weather build

This section applies to the `personalWeather` and `family` builds. The public
GitHub release is the `family` build; it has the same internet features. The
`play` build has no Weather page and does not declare the `INTERNET` permission.
The `personal` build has no Weather page but does declare `INTERNET`, for the
auto update and the language packs. Weather and Translate are the online
features of the `personalWeather` and `family` builds; there are no accounts,
ads, analytics or tracking.

- **Who receives data:** Open-Meteo (open-meteo.com), over https, at
  `geocoding-api.open-meteo.com` and `api.open-meteo.com` for Weather.
  Translate uses the DeepL hosts listed below.
  Open-Meteo needs no account or key. Its own terms and privacy policy apply to
  what its servers receive, including your IP address, as with any web request.
- **City search:** when you type a city in the Weather page options and tap
  Search, the name you typed is sent once to find matching places.
- **Forecast:** after that, each request contains only the city's latitude and
  longitude, rounded to two decimals (about 1 km), and fixed field names. No
  device identifier, account, contact, app list or other data is sent.
- **When:** normally every 30 minutes per place, only while the Weather page
  is on screen with Parc in front. A failed first fetch can retry after five
  minutes. Never in the background.
- **On the phone:** up to six saved places with names, region text and rounded
  coordinates, your unit choice, and each place's last forecast with its time.
  Manage or remove places in Weather options. Saved places are in Parc backups;
  cached forecasts are not.
- **No location permission.** Parc never reads your position.

- **Translate (search mode, weather build only):** in Translate mode, and only
  there, Parc sends the text you type in search, or the text you select in the
  reader when you tap Translate, the target language (English or Portuguese of
  Portugal) and your own DeepL API key to DeepL, over https, at
  `api-free.deepl.com` (keys ending `:fx`) or `api.deepl.com`. It sends after a
  short pause in typing or when you press Enter, or at once when you tap
  Translate in the reader; never for less than two characters, and nothing typed
  or selected in any other mode. No device identifier or other data is sent.
  DeepL's free plan may store the texts it receives; DeepL's own terms and
  privacy policy apply. Parc encrypts your pasted key on the phone with Android
  Keystore. Manual and weekly backups include it, readable unless you set a
  backup passphrase. Restore encrypts it again with the destination phone’s
  Keystore. Logs and diagnostics exclude it. Your last 20 translations stay
  on the phone. You can clear them on the key screen.

## Updates and language packs (personal builds)

The `personal` and `personalWeather` builds can check a private GitHub repo
(`masterdroid-arch/parc-builds`) for a newer personal build, and can download
two language data packs (a Portuguese voice pack and an English–Portuguese
dictionary) for the Palavra learning feature.

- **Who receives data:** GitHub, over https (`api.github.com` and GitHub's
  download hosts). GitHub's own terms and privacy policy apply to what its
  servers receive, including your IP address, as with any web request. The repo
  is private and read-only; Parc only reads release metadata and downloads the
  APK and pack files.
- **The token:** checking for updates and downloading packs needs a read-only
  GitHub token you paste once in Settings. The token is stored only on this
  phone, encrypted with an Android Keystore key, and is never part of a backup,
  a log or a diagnostic file. It gives access to private builds and is easy to recreate
  after a reinstall. Removing it in Settings deletes it.
- **When:** the update check runs only when you tap **Check for updates**, or,
  if you turn on **Check automatically**, at most once a day when Parc starts.
  It never sends anything else about the phone, your apps or your use.
- **Installing an update:** when a newer version exists, tapping the update
  downloads the APK to Parc's own cache, checks its SHA-256 and that its signing
  certificate matches the installed app, then hands the file to Android's
  installer. You still tap **Install**; Parc cannot install silently.
- **Data packs:** the voice pack and dictionary are stored in Parc's own files
  (`packs/`), outside backups, and are re-downloaded when a new version appears.
  Their download has a **Wi-Fi only** switch (on by default). Removing a pack
  deletes its files.
- **No pack, no problem:** without a pack, Palavra falls back to the phone's own
  text-to-speech voice and offers a hand-off for dictionary lookups.

## Messages page

When you grant notification access and choose which apps may contribute, Parc can show their
messages in the stats hub. Only WhatsApp, Telegram and Instagram are recorded in its history.

- Messages from WhatsApp, Telegram and Instagram are **stored on this phone by default**, as soon
  as accepted notifications arrive, while Messages and recording are on. Parc
  cannot recover chats that were never delivered as notifications or whose text
  Android or the messaging app withheld.
- What is stored: the message text and links, plus the app, conversation name, sender and time.
  Images are never stored. An image with a caption keeps the caption and its links; an image with no
  text is skipped.
- There is **no automatic deletion** and no size cap. Messages stay until you delete them. Use
  `Clear all messages` in Messages settings, or long-press one message or a conversation in History
  to clear it.
- The stored history is **not in backups**: it is not in any backup file, Parc's or Android's. It
  lives in its own database, `comms-history.db`, in Parc's private app storage.
- Notification access can be turned off and the History segment still shows the stored messages.
  Live cards and new recording stop while access is off.
- Live notification text, titles, conversation names and the one-bit preview image are held **in
  memory only**, and only while the listener is connected. That live copy is dropped when the
  listener disconnects or the Parc process restarts.
- Turning Record history off pauses new writes without deleting stored messages.
  Turning Messages off or removing an app from the live allow-list also pauses
  recording for it and keeps existing history. Hide, Focus and Lock filter what
  Parc displays; they do not change the Android notification shade.
- Reply actions send your reply through the messaging app's Android notification action. Parc does
  not send anything itself.

Your choices (which apps may contribute, whether history is kept, and your quick reply texts) are
saved as local settings. Those settings contain no message content.

## What Parc stores on the phone

- Your layout: arcs, pins, folders, hidden apps and folder locks.
- Settings: skin, gesture choices, focus modes, hub pages and similar.
- Which of the short gesture hints have already been shown (a few fixed names). This stays on the phone and is not in backups.
- Which newly installed apps have already had their one-time "Add to an arc?" prompt. This stays on the phone and is not in backups.
- Your notes (with their titles and any reminder time), quotes, quick replies,
  timers, and the allow-list of message apps.
- Daily records of screen time, data use and steps, per-app monthly totals, and
  local statistics used by the hub pages.
- Your chosen book list, chosen scan folders, the remembered list of files found,
  the list of files already checked for automatic shelf adding, file locations,
  reading places, bookmarks, highlights, reader choices, shelves, shelf rules,
  finished marks and cached covers.
- The Messages history: text and links from WhatsApp, Telegram and Instagram, in its own
  `comms-history.db`. It is not part of any backup.
- A launch log used for recents and ranking.
- An optional wallpaper image you picked, if the wallpaper skin is used.
- The weekly backup switch, the folder you picked for it and the folder's Android grant, the
  last backup time and the last failure line. This belongs to one phone and is not in backups.
- In the personal and weather builds: the read-only update token (encrypted,
  not in backups) and the downloaded language packs (outside backups and
  re-downloadable).

This data stays in Parc's private app storage. Android backup is disabled with
`allowBackup="false"`, so it is not uploaded to Google's backup service.

## Backups and diagnostics

Parc can export a backup, and can export diagnostics, through Android's document
picker. The file is written where you choose. Once it exists, that file is
yours to keep or delete; Parc does not upload it and cannot reach it again unless
you import it.

Backups are readable files by default. If you turn on **Encrypt backups**, manual
and weekly backups are protected with your passphrase. Nobody can recover it:
forget it and the backup cannot be opened. A backup contains layout, settings,
notes, quotes, daily records, the chosen book list and its reading positions,
bookmarks and highlights, shelves and their rules, finished marks, reader
choices, saved Weather places in the weather build, the search aliases you set,
and an optional wallpaper image. It contains neither book files nor cached
covers or forecasts.
Anyone with an unencrypted backup can read the saved DeepL key. Translation history stays
on the phone. The Messages history stays in its own `comms-history.db` and is never copied into a backup or a diagnostics
file. Restoring a backup does not restore message history.

If you turn on **Back up every week** and pick a folder, Parc writes the same
backup file there once a week while the phone is charging and idle. It keeps the
newest four dated files and needs no internet and no account. The switch, folder,
last time and last failure stay on the phone and are not themselves copied into a
backup. With encryption on, the passphrase is stored on this phone encrypted with
an Android Keystore key, separately from backups. It is never logged, included in
diagnostics or copied into a backup. After uninstalling Parc, you must remember
the passphrase. If the stored passphrase is missing or cannot be opened, the weekly
job writes nothing and asks you to enter it again. Old readable backups still restore.

Diagnostics contain Parc's own log and facts about the phone. Parc's log helper
redacts URLs, addresses, quoted text, package names and long numbers before a
line is stored. Callers are also written not to pass message text, contact names
or app names.

## When data leaves Parc

Only because you asked:

- Opening an app, a contact card, the dialer, a Settings page or a web search.
- Tapping Define on selected book text in the reader: the selected word is handed
  to the dictionary or other app you choose; Parc itself sends nothing.
- Sending a reply through another app's notification action.
- Saving or sharing a backup or diagnostics file.
- Searching for a Weather place or viewing its forecast, or typing in Translate
  in the internet build, as described above.
- Checking for updates or downloading a language pack in the personal or
  weather build, as described above.
- Opening the donation page from Support Parc in About: the row opens ko-fi.com in the browser only when you tap it.

In those cases the other app or service handles the data under its own policy.
For example, a web search opens your browser with the text you typed.

## Children

Parc is a general-purpose launcher and is not directed at children. It has no
server-side collection; the on-phone storage described above applies to all users.

## Changes

If a future version changes what Parc reads or stores, this document will be
updated before that version is published. The date at the top is the date of the
last check against the source.

## Contact

https://github.com/masterdroid-arch/Parc-Launcher/issues
