# Parc Launcher

<img src="docs/media/launch/hero-launch.svg" alt="Parc, a calm home screen for Android: your apps sit on arcs, right under your thumb" width="100%">

A calm home screen for Android. Your apps sit on arcs that sweep up from the
bottom-right corner, under your thumb. Aim with the joystick, release to open.
There is no app grid and no feed. Parc is free on every channel, the same app,
with no locked features and no Play Billing; donations are the only money.

Parc is a personal project, shared as open source. It is designed for a phone
held in the right hand, in portrait, at about 384 x 853 dp.

## See it in motion

<table>
<tr>
<td width="50%"><img src="docs/media/launch/arc-orbit.svg" alt="Apps on arcs: aim from the hub, release over an app, it opens" width="100%"><br><b>Apps on arcs.</b><br>Aim from the hub, release over an app, it opens.</td>
<td width="50%"><img src="docs/media/launch/glass-closeup.svg" alt="Close-up of the frosted glass hubs" width="100%"><br><b>Glass that feels like glass.</b><br>Frosted hubs over your own picture, each with its own opacity.</td>
</tr>
<tr>
<td width="50%"><img src="docs/media/launch/skins-wipe.svg" alt="The nine skins wiping across the screen" width="100%"><br><b>Nine skins.</b><br>From Midnight to Phosphor, glass on or off.</td>
<td width="50%"><img src="docs/media/stats-hub.svg" alt="The stats hub pages, one swipe apart" width="100%"><br><b>The stats hub.</b><br>Today, Device, Notes and more, one swipe apart.</td>
</tr>
</table>

<img src="docs/media/how-it-works.svg" alt="Aim from the hub, release over an app, it opens" width="100%">

## What it does

This is the public build on the GitHub releases page. Everything below is in it.

### The home screen

- **Arcs and a joystick.** Pinned apps sit on the inner arcs, folders on the
  outer ones. Drag from the hub to aim, release to open. Pinned slots stay where
  you put them.
- **Folders you can edit.** Create, rename, reorder, add and remove apps, and
  drag apps in or out on the arcs. A folder can be locked with your screen lock
  or fingerprint.
- **Edit mode and undo.** Hold the hub to edit. Drag an app and the neighbours
  slide aside; turn an empty arc to reach another spot. Every layout change can
  be undone, and Lock layout stops moves until you switch it off.
- **Your hand and your menu.** Left hand mirrors Home, its drags and its search
  button. Long-press an app for Remove from arc, App info and Uninstall; a
  removed or uninstalled app leaves a quiet "+" spot instead of shifting others.
- **All apps.** Tap the Home hub to show all visible apps on arcs; tap again to
  return Home. An A to Z list is also available from search.
- **Hidden apps and folders.** Hide an app from arcs, search, All apps, folders,
  Messages and Media. Hide a folder to hide both its slot and its apps. Unhide
  them from Home > Hidden & locked in Settings; their positions are kept.
- **Skins.** Nine skins: Midnight (default), Dusk, Moss, Glass, Graphite, Cyber,
  Field Atlas Light, Field Atlas Dark and Phosphor. A wallpaper skin follows the
  system wallpaper colours, and the skin editor lets you make and keep your own.
- **Notification badges.** Unread dots on icons, if you turn them on.
- **Safe mode.** If Parc crashes repeatedly, it starts as a plain app list, so
  you are never stranded.

### Search

Pull down from the home screen, or use the handle. Search finds apps, folders and
Settings pages, plus optional contacts, calendar events and media file names when
you allow them. Pick a mode under the search box:

- **Apps** (the default). Before typing it shows most used and recent apps.
  Results carry the app icon and are grouped into Apps, Notes, Files and
  Actions. Latin typing finds Cyrillic names and back, and an app can have a
  second name you set. Long-press an app row for Add to arc, Add to folder, App
  info, Uninstall, Hide or Set alias.
- **Translate**: needs your own DeepL key.
- **Note**: type and save a quick note. A "Saved to Notes" card stays for a few
  seconds with an Undo.
- **Calculate**: sums and percentages.
- **Files**: find a book by name.

### The stats hub

A panel above the arcs with pages you swipe between. A new install shows Today,
Device and Notes; every other page is off until you switch it on in Settings.
Scroll or tap the page names; hold one for Hide page, Size, Move left/right and
Page settings. Hold and drag a name to reorder. A page can keep its own Compact,
Standard or Tall size.

- **Today.** The clock, alarms and Focus line, then one line from each page that
  has something to say: next event, running timers and reminders, steps, sun,
  nearby countdown, screen time compared with your own average, and a Continue
  reading row for the last book.
- **Device.** Battery, charging and screen-on time; Wi-Fi and mobile totals with
  top apps; storage, RAM, uptime, temperatures and thermal state, combined from
  the old System, Power and Network pages.
- **Controls.** Volume, torch, Do Not Disturb and the quick toggles for Wi-Fi,
  Bluetooth, mobile data, airplane mode, auto-rotate, battery saver and screen
  timeout. Android does not let apps switch those last ones directly, so Parc
  opens their system screens.
- **Apps.** Screen time and biggest apps for Today, 7 days, 30 days, this year or
  all time, from Parc's own daily record, since Android forgets old usage. Tap
  the period label to switch.
- **Notes.** Plain-text notes with headings, bold, italic, strike, bullet,
  numbered and checkbox lines, in a View mode and an Edit mode. Notes can have a
  title, a pin, and a reminder: pick a time and Parc notifies you, or start a
  timer from the note. The Notes page has a search field; text can be shared in
  as a new note, and deleting shows Undo.
- **Steps.** Today's steps from the phone's own step counter, rough distance, a
  7-day row and an optional daily goal. It asks for the physical-activity
  permission only when you tap Allow.
- **Rhythm.** The day as a 24-hour ring: when the screen was on, first pickup,
  pickups, and the longest phone-free stretch. Look at earlier days too.
- **Sky.** Sunrise, sunset, golden hour, day length and the moon phase, all
  calculated on the phone. The place comes from the Weather page, or from a
  latitude and longitude you type.
- **Year.** One dot per day of the year, shaded by steps, screen time, data used
  or pickups, with countdowns to dates you add.
- **Records.** Your own personal bests from the daily record.
- **Commonplace.** One searchable list of kept passages: a bundled set of
  public-domain quotes, your own quotes (typed or pasted), text shared into Parc
  from another app, and every book highlight. Tap a highlight to open its book.
- **Messages.** The messages are the page. Live shows notifications you can
  read, reply to or dismiss; History keeps WhatsApp, Telegram and Instagram text
  and links on the phone, searchable. Reply is visible, and a message that cannot
  be answered here offers Open app. Needs notification access.
- **Focus.** Modes that hide distracting apps, with optional pauses, quiet
  badges and a Do Not Disturb rule. Their messages are hidden too, unless you
  choose Always show messages for that mode. Permanent hide and Lock still take
  priority. A chip shows the mode in use.
- **Library.** Your chosen books on shelves, the last one marked Continue. Shelves
  can sort themselves by file type, folder or name, and comics read page by page.
- **Timers, Agenda, Media.** Timers, calendar events and the playing track.
- **Weather.** Seven days, an hour-by-hour view for each
  day, up to six saved places and a place search.

### Library and reader

- **Find books.** Choose the folders Parc may look in, pick the file types,
  search by name and tick the files to show on the shelf. Parc remembers what it
  found, so the screen opens fast and refreshes in the background; reopen it any
  time and use Scan again. Find books can offer optional all-files access; without
  it, use Android's folder and document pickers. A "+" tile after the last book opens Find books.
- **Shelves.** All, Reading, Finished and your own shelves across the top. A
  shelf can have rules (file type, folder, name contains) and sort itself; a book
  can sit on several shelves, and a finished book can be marked in one tap.
- **The reader.** EPUB, PDF, plain text and comic (CBZ), full screen, at the
  place you left off. Text size, spacing, font, page light, night mode and
  brightness are yours; volume keys can turn pages. Search inside a book and
  jump; bookmark pages; highlight a passage and send it to Commonplace. Select a
  word to Define it through a dictionary app or Translate it. The text reader can read aloud with the phone's own voice, with
  play/pause, speed and stop-at-chapter-end. PDFs have pages and pinch or
  double-tap zoom, and comics swipe and zoom. A file opened from another app
  reads in Parc without joining the library.

### Settings, backup and the rest

- **Settings in six groups.** Appearance, Home layout, Hub pages, Gestures &
  search, Notifications & focus, and Data & help. At the top, a search box finds
  each setting, and six tiles (Skin, Wallpaper, Arcs, Glass, Left hand, Lock
  layout) jump to the most used ones; the last three switch in place.
- **Backup and restore.** Save your layout, settings, notes, shelves and quotes
  to a file you choose, and restore it later. It also keeps your day records,
  chosen book list, reading places, bookmarks and highlights; message and
  translation histories stay out. The export screen says "Encrypted" or "Not
  encrypted". A backup without a passphrase leaves your DeepL key out.
- **Back up every week.** Pick a folder and Parc writes the same backup there
  about once a week, while the phone is charging and idle. It keeps the newest
  four files and needs no account or internet. The weekly backup is always
  encrypted, so turning it on asks for a passphrase first. If the passphrase is
  missing, Parc writes nothing and says "Weekly backup paused: add a
  passphrase". Nobody can recover a forgotten passphrase.
- **Update check.** See "Updates" below.
- **Report a problem.** Settings > Data & help > This build. It shows a short
  report you can edit, then opens a pre-filled new issue on GitHub in your
  browser (you need a free GitHub account), or copies the text. Only the Parc
  version, Android version and phone model are added, plus the last crash note
  if you turn that on. Parc sends nothing itself.
- **What's new.** After an update Parc shows short release notes in plain words.

Everything above is free, with no locked features and no Play Billing.
Donations are the only money; there is no paid version, and the build stays
open source.

### Something hidden

Parc keeps a small secret in the hub. Nothing in Settings mentions it, and it is earned, not bought.

- A quiet day can open it. So can a dial turned three times one way and once back.
- A long walk opens a second door. So does a restless finger on the moon.

### Screenshots

All of these use Parc's built-in sample data (invented titles, places and messages), so none of it is real. Both hubs are set to the Glass effect at 35 percent opacity over a picture, each in its own colour. Taken on an earlier version, before Settings was redesigned.

<table>
<tr>
<td align="center"><img src="docs/media/screenshots/home-glass-giraffe.png" alt="Home, glass over a giraffe photo" width="240"><br>Sand and slate</td>
<td align="center"><img src="docs/media/screenshots/home-glass-discs.png" alt="Home, glass over red discs" width="240"><br>Rust and graphite</td>
<td align="center"><img src="docs/media/screenshots/home-glass-candles.png" alt="Home, glass over candles" width="240"><br>Teal and rust</td>
<td align="center"><img src="docs/media/screenshots/home-glass-waves.png" alt="Home, glass over gold waves" width="240"><br>Plum and sand</td>
</tr>
</table>

<table>
<tr>
<td align="center"><img src="docs/media/screenshots/hub-today.png" alt="Today, with the clock" width="240"><br>Today</td>
<td align="center"><img src="docs/media/screenshots/hub-device.png" alt="Device: power, network, storage" width="240"><br>Device</td>
<td align="center"><img src="docs/media/screenshots/hub-controls.png" alt="Controls: sound and quick toggles" width="240"><br>Controls</td>
<td align="center"><img src="docs/media/screenshots/hub-screentime.png" alt="Apps, screen time today" width="240"><br>Apps, today</td>
</tr>
<tr>
<td align="center"><img src="docs/media/screenshots/hub-screentime-week.png" alt="Apps, screen time over 7 days" width="240"><br>Apps, 7 days</td>
<td align="center"><img src="docs/media/screenshots/hub-steps.png" alt="Steps" width="240"><br>Steps</td>
<td align="center"><img src="docs/media/screenshots/hub-rhythm.png" alt="Rhythm" width="240"><br>Rhythm</td>
<td align="center"><img src="docs/media/screenshots/hub-sky.png" alt="Sky" width="240"><br>Sky</td>
</tr>
<tr>
<td align="center"><img src="docs/media/screenshots/hub-year.png" alt="Year" width="240"><br>Year</td>
<td align="center"><img src="docs/media/screenshots/hub-records.png" alt="Records" width="240"><br>Records</td>
<td align="center"><img src="docs/media/screenshots/hub-commonplace.png" alt="Commonplace, kept passages" width="240"><br>Commonplace</td>
<td align="center"><img src="docs/media/screenshots/hub-messages.png" alt="Messages" width="240"><br>Messages</td>
</tr>
<tr>
<td align="center"><img src="docs/media/screenshots/hub-weather.png" alt="Weather" width="240"><br>Weather</td>
<td align="center"><img src="docs/media/screenshots/library-shelves.png" alt="Library shelves" width="240"><br>Library shelves</td>
<td align="center"><img src="docs/media/screenshots/library-find-books.png" alt="Find books" width="240"><br>Find books</td>
<td align="center"><img src="docs/media/screenshots/reader-highlight.png" alt="Reader with a highlight" width="240"><br>Reader, highlight</td>
</tr>
<tr>
<td align="center"><img src="docs/media/screenshots/reader-search.png" alt="Search inside a book" width="240"><br>Reader, find in book</td>
<td align="center"><img src="docs/media/screenshots/reader-comics.png" alt="Comic (CBZ) reader" width="240"><br>Comics reader</td>
<td align="center"><img src="docs/media/screenshots/edit-mode.png" alt="Edit mode" width="240"><br>Edit mode</td>
<td align="center"><img src="docs/media/screenshots/home-left-handed.png" alt="Home, left-handed mode" width="240"><br>Left-handed Home</td>
</tr>
<tr>
<td align="center"><img src="docs/media/screenshots/search-calculate.png" alt="Search, with a sum" width="240"><br>Search, with a sum</td>
<td align="center"><img src="docs/media/screenshots/search-note.png" alt="Search, Note mode" width="240"><br>Search, Note</td>
<td align="center"><img src="docs/media/screenshots/search-files.png" alt="Search, Files mode" width="240"><br>Search, Files</td>
</tr>
</table>

Wallpapers in the screenshots: photos by Bharath Kumar, Micke Lindström, Pebri Ramadhan Mas and Plasmanica Generator on Unsplash. The Files, comics and reader-search shots use files invented for testing.

## Privacy

The public GitHub release is the **family internet build**. It uses the internet
for three things only: Weather, Translate (your own DeepL key) and a once-a-day
check for a newer Parc. It never uses it for accounts, ads, analytics or
tracking. The `play` build leaves all three out and has no `INTERNET`
permission; the build fails if that permission or any network class appears in
its compiled code. Actions such as replying or opening a web search can hand data
to another app at your request.

Weather uses Open-Meteo over https, with no account, key or location permission.
A city search sends the name you type. Forecasts send only rounded coordinates
(about 1 km) and fixed field names. You can keep up to six places and their last
forecasts for offline use. Refresh normally runs every 30 minutes per place
while Weather is on screen; a failed first fetch can retry after five minutes.
Nothing is fetched in the background. Saved places are in Parc backups;
cached forecasts are not. Translate sends text only in Translate mode, after a
short pause or Enter. The key is in Parc backups only when the backup is
encrypted with a passphrase; saved translations stay out of backups.
There are no Parc accounts, ads, analytics, tracking or crash uploads.

Notes, quotes, daily records, your chosen book list, shelves and their rules,
reading places, bookmarks and highlights stay on the phone. The book list Find
books remembers, the list of files already checked for automatic shelf adding,
and the folders you chose to scan, are on the phone only and are not in backups.
Notification access, usage access, contacts, calendar and media file names are
optional and read on the phone only. After notification access is granted and
Messages is on, messages from WhatsApp, Telegram and
Instagram are stored on this phone by default: text and links only, no images.
Nothing is deleted automatically; you can clear every message in Messages
settings, or delete one message or conversation in History. The stored history
is not in backups, and notification access can be off while History stays
readable. Replies are handed to the messaging app itself, not to a Parc server.
Android backup is disabled, so Parc's own data is not copied to Google backup.
A backup or diagnostics file leaves Parc only when you pick a destination
yourself. Translation history stays on the phone only and is not included in a
backup.

`PRIVACY.md` has the full list of what is read, why, what is stored, every
permission, and how the update check works.

## Updates

Parc can tell you when a newer version is on GitHub. It is switched on by
default and you can turn it off in Settings > Data & help > This build >
**Check for updates**.

- About once a day Parc makes one request to `api.github.com` for the latest
  release. GitHub sees your IP address; the request carries no account or
  identifier. If you turn the switch off, Parc asks only when you tap **Check
  now**.
- A newer version shows a quiet "Update available" row on the Today page and at
  the top of Settings. Nothing is downloaded until you tap **Update**.
- Parc then downloads the APK and its checksum file from the project's GitHub
  release, checks the SHA-256, and hands the file to Android's installer. Android
  asks you to confirm, and only accepts an update signed with the same key as
  the Parc you have. Parc never installs anything silently.
- The `play` build has no update check.

## Requirements

- Android 13 (API 33) or newer. The app targets API 36.
- A portrait phone. There is no tablet or landscape layout.
- To build: JDK 21, Android SDK platform 36, and Gradle through the included
  wrapper (8.11.1).

## Install and update

1. Open the latest release on the GitHub releases page and download the APK.
2. Open it. Android asks once to allow installs from your browser or files app.
3. Set Parc as your default home app in Android's default apps settings.

The first install of 1.6.0 is manual. From 1.6.0 on, Parc can offer updates
itself (see "Updates" above), and you only tap Update.

**Coming from 1.1.1?** The signing key changed, and Android refuses to update an
app over a copy signed with a different key. Uninstall 1.1.1 first, then install
1.6.0. If you want to keep your layout, export a Parc backup before you
uninstall and restore it after.

[Obtainium](https://github.com/ImranR98/Obtainium) can also install and update
Parc from the release page, if you prefer it. The same rule applies: an update
must be signed with the same key as the installed copy.

## Build from source

Install JDK 21 and the Android SDK, then point Gradle at the SDK with the
`ANDROID_HOME` environment variable or a `local.properties` file containing
`sdk.dir=/path/to/android-sdk`.

```sh
git clone https://github.com/masterdroid-arch/Parc-Launcher
cd Parc-Launcher
./gradlew assembleFamilyDebug
```

The debug APK is written to:

```
app/build/outputs/apk/family/debug/app-family-debug.apk
```

Install it on a connected phone or emulator:

```sh
adb install -r app/build/outputs/apk/family/debug/app-family-debug.apk
```

Run the unit tests with:

```sh
./gradlew testDebugUnitTest
```

`family` is the public release: Weather, Translate, the update check and
`INTERNET`. A `play` flavour also exists for a possible store version; it removes
several permissions from its manifest and has no internet access. Both use the
package name `com.brightblu.parc`.

A release build is unsigned on purpose, because the distribution key is kept
out of the source tree. `./gradlew :app:copyFamilyReleaseApk` writes
`Parc-<version>-family-unsigned.apk`. Sign it with your own key before
installing or sharing it; `scripts/create-release-key.sh` and
`scripts/sign-release.sh` do that in a terminal. A build signed with a different
key will not update over an install signed with another key; uninstall the old
copy first, and keep a Parc backup if you want to restore your layout.

## Licence

Parc is free software under the GNU General Public License, version 3. You may
use, study, share and change it under that licence. It comes with no warranty.
See `LICENSE` for the full text and `NOTICE` for the third-party components.

The GPL covers the code. The name "Parc" and the app icon are not part of the
licence; see `TRADEMARK.md` before publishing a modified build.

Contributions are welcome. Read `CONTRIBUTING.md` first; a signed entry in
`CLA-SIGNATURES.md`, under the terms of `CLA.md`, is required before a change is
merged.

## Support

Parc is free, with no ads, no accounts and no tracking, and it will stay that
way. If it helps you, you can leave a tip on Ko-fi:

https://ko-fi.com/parcapp

Nothing is locked behind it. The same link is in Settings > Data & help > This
build (Support Parc on Ko-fi). Tapping it leaves Parc and opens Ko-fi in your
browser; Parc shares nothing.

## Contact

Found a problem? Use Report a problem in Settings, or open an issue:

https://github.com/masterdroid-arch/Parc-Launcher/issues
