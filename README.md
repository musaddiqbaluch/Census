# Census

**Know how your apps are distributed.**

Census is an open-source Android utility that counts user-installed apps and shows how they are distributed across their installer or app-store sources.

It focuses on the overall picture rather than displaying individual apps.

## Features

- **Total User Apps** — See the number of detected user-installed apps.
- **Installer Breakdown** — See how many apps are associated with each installer or app store.
- **Distribution Bar** — Quickly compare installer groups visually.
- **Unknown Sources** — Apps without a reliable installer association are grouped as **Unknown**.
- **Material UI** — Clean, modern Android interface.
- **Haptic Feedback** — Optional haptic feedback for supported interactions.
- **Update Checking** — Check for Census updates over the Internet.

## How It Works

Census uses Android's `PackageManager` to inspect installed applications and Android's installer information APIs to determine their associated installer.

System and updated-system applications are excluded from the main count by default.

Installer information is read using:

- `InstallSourceInfo` on Android 11+
- `getInstallerPackageName()` on Android 10

If Android does not provide reliable installer information, Census reports the source as **Unknown** rather than making assumptions.

## Permissions

| Permission | Purpose |
|---|---|
| `QUERY_ALL_PACKAGES` | Required to inspect the installed-app inventory on Android 11+ |
| `INTERNET` | Used for update checking |
| `VIBRATE` | Used for optional haptic feedback |

`QUERY_ALL_PACKAGES` is required because broad package visibility is part of Census's core functionality.

## Privacy

Census is designed to keep its core analysis local.

- No account is required.
- No cloud service is required for app analysis.
- Installed-app information is processed on the device.
- The source code is publicly available for inspection.

## Compatibility

- **Minimum:** Android 10 (API 29)
- **Tested:** Android 10–16
- **Target SDK:** Android 16 (API 36)

## Development

Census was built using **Sketchware Pro**, with the help of **AI** for research, development, debugging, and implementation.

## Project Status

Census is currently in its **first public release**. Installer detection and compatibility may vary depending on the Android version and device manufacturer.