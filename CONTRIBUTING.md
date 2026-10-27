# Contributing to Parc

Thanks for taking a look. Parc is a small, personal launcher, so the rules are
short and the review is direct.

## Before you start

- **Open an issue first** for anything larger than a typo or a one-line fix.
  That avoids two people building the same thing.
- **Do not send personal data.** No screenshots of your messages, contacts,
  calendar or installed apps. Use neutral examples in issues and tests.
- **Do not report a security problem in a public issue.** Write to
  https://github.com/masterdroid-arch/Parc-Launcher/issues with the details.

## What the project is

- Kotlin, Android, one home-screen app. No Java.
- Modules: `app` (wiring and screens), `core/model` (the contract),
  `core/data` (index, persistence, sensors), `feature/ring` (arcs and layout),
  `feature/search`, `feature/settings`.
- Compose for screens, one custom canvas for the arc field.
- JUnit 4 for unit tests. Pure logic needs tests.
- Toolchain: Kotlin 2.0.21, AGP 8.9.2, Gradle 8.11.1, JDK 21, minimum Android
  14 (API 34), compile and target SDK 36.

## Build and test

```sh
./gradlew assemblePersonalRelease
./gradlew testDebugUnitTest
```

The first command writes
`app/build/outputs/apk/personal/release/app-personal-release.apk`. The second
runs all JVM unit tests.

If your change touches a module, run that module's tests too, for example:

```sh
./gradlew :core:data:testDebugUnitTest
```

## What a good change looks like

- Small and focused. One problem per pull request.
- Matches the code around it: Kotlin official style, four spaces, 100-column
  soft limit, English names, package `com.brightblu.parc`.
- Immutable data classes, `StateFlow` for state, constructor injection. No new
  dependency-injection framework and no global state outside `app`.
- Colours and sizes come from the skin and metrics tokens, not from literals in
  a screen.
- No new Android permission, no `INTERNET`, no `QUERY_ALL_PACKAGES`, and no new
  library without agreeing it in the issue first. A change that needs a
  permission is usually the wrong change.
- No logging of app names, contact names or notification text.
- A test for every pure function you add or change. A bug fix gets a test that
  fails before the fix.
- The build and the tests pass. Say which commands you ran in the pull request.

## Commits and pull requests

- Write a short subject line that says what changed. Add a sentence of why in
  the body if it is not obvious.
- Rebase on the current main branch before asking for review.
- A pull request that adds a feature should say what it does, what it does not
  do, and how you tested it.

## Contributor licence agreement

Before a pull request can be merged, you need to agree to `CLA.md` and add one
row to `CLA-SIGNATURES.md` with your name, GitHub user, date and the CLA version.
The CLA lets the maintainer keep the project usable under the GPL and, if a paid
build ever exists, include your contribution in it. You keep the copyright to
your work.

Add the row in the same pull request. Do not put an email address in the table.

## Licence

By contributing, you agree that your contribution is licensed under the GNU
General Public License, version 3, and under the terms of the CLA.

## Review

The maintainer reviews for behaviour, tests, privacy and the rules above. A
change may be declined because it makes the launcher heavier, asks for a
permission, or does not fit the arc design. That is not a judgement of you.
