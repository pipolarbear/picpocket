<img src=".github/images/icon.svg" width="72" height="72" align="left" alt="PicPocket icon">

# PicPocket

[![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.02.00-4285F4?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![ML Kit](https://img.shields.io/badge/ML%20Kit-Document%20Scanner%20%2B%20OCR-4285F4?logo=google)](https://developers.google.com/ml-kit)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

PicPocket is a local-first document scanner and organizer for Android: capture multi-page documents with automatic edge detection and perspective correction, organize them with tags, full-text OCR search, and searchable PDFs, and keep them under your control — stored on your device and synced (optionally encrypted) to Google Drive, Nextcloud, or anything that exposes Android's Storage Access Framework. Workflows add advanced automation.

## Features

- **Auto-capture** — ML Kit Document Scanner API detects document boundaries and captures with perspective correction
- **Multi-page documents** — Scan multiple pages, preview, reorder, and export them as a single PDF
- **On-device OCR** — ML Kit Text Recognition extracts text offline; no data leaves the device
- **Searchable PDFs** — Invisible text layer embedded in generated PDFs so you can search document content
- **Image filters** — Grayscale, brightness, contrast, sharpen, and binarize per page
- **Drag-and-drop reorder** — Rearrange pages in edit mode
- **Full-screen viewer** — Swipe between pages, pinch-to-zoom, double-tap to reset
- **Search documents** — Regex search across document names and OCR content with debounce
- **Document tagging** — Color-coded tags with search-and-create tag selection; manage tags individually or batch on multiple documents via multi-select
- **Dark mode** — System/Light/Dark with 4 color palettes (Royal, Default, Ocean, Forest)
- **Page size selection** — A0–A6, Letter, Legal, Tabloid
- **SAF save location** — User picks where to save via Storage Access Framework; no storage permissions needed
- **Import PDFs** — Import existing PDFs into the library as documents
- **Page editing** — Append pages to a document, delete a single page, or rescan a page to replace its image
- **Collate pages** — Merge selected pages into one continuous image. All pages are selected by default; pick a layout — **Auto** (find the overlap and stitch, showing which direction it chose), **Horizontal**, or **Vertical** (place without aligning) — then use the full-screen editor to zoom the preview, drag to reorder the sources, match their sizes, drag a seam to line the join up, or reset back to auto. Choose whether to remove the source pages after merging (kept by default). The merged page inherits the sources' OCR text (or is OCR'd if they have none). Export with the "Match image" page size to keep a tall strip at its own size with no margins. Targets flat surfaces — curled or folded pages are not flattened.
- **Export & share** — Export to PDF (A0–A6, Letter, Legal, Tabloid; selectable quality); export with a searchable text layer when OCR is on; share or save via SAF
- **Sync anywhere** — Point PicPocket at any Storage Access Framework folder (Google Drive, Nextcloud, …) and sync across devices that share it, with conflict handling
- **Optional encryption** — Enable a passphrase and synced files are encrypted before they leave the device; change or disable it any time
- **Workflows** — Automate document actions (encrypt, zip, save to folder, send to an app, notify, delete) when events happen (created, pages added, renamed, tagged, …), with run history
- **Sorting** — Last seen (default), modified, created, size, or name, with a reverse toggle
- **App lock** — Optional biometric unlock for the app
- **Privacy by default** — Documents stay on your device; no account required; OCR runs fully on-device

## Sync & encryption

PicPocket keeps your documents on your device and, if you want, syncs them
through a folder you choose with Android's Storage Access Framework — **Google
Drive, Nextcloud, or any app that exposes a document provider**. Point your
devices at the same folder and they stay in sync; PicPocket reconciles changes
and tracks deletions so they propagate to the other devices.

Sync is optional. When you enable **encryption** with a passphrase, files are
encrypted before they are uploaded, so the provider only ever sees ciphertext,
and you can change or disable the passphrase later. (This is separate from the
per-file password a workflow's `encrypt` action can apply.)

To add another device, open **Sync → Share or scan setup** on the device that is
already synced and scan its QR code — or copy the setup code and paste it on the
other device. The setup code carries the folder and, when encryption is on, the
passphrase and its generation, so the new device joins in one step. The new
device still confirms the folder in the system picker to grant access, and you
must re-apply the setup after reinstalling the app (folder grants do not survive
a reinstall). Because the code contains your passphrase, treat it as sensitive.

## Workflows

Workflows automate document handling: **when** a document event happens, **if**
its conditions hold, **then** a tree of actions runs. Actions include encrypt
(with a passphrase), zip, save to a folder, send to an app, notify, and delete
the document; conditions cover tags, page count, document name, and OCR text.
You can also run a workflow on demand and review its history.

## Privacy

There is no PicPocket account and no analytics. Documents, their metadata, and
OCR results live on the device, and OCR is performed locally with ML Kit. Where
your data goes is your choice: keep it on-device, save copies with the Storage
Access Framework, or sync it to a provider you trust — encrypted if you want.

## Requirements

- Android 8.0 (API 26) or newer
- Google Play Services (for ML Kit)

## Quick Start

```bash
# Clone
git clone https://github.com/pipolarbear/picpocket.git
cd picpocket

# Create local.properties
echo "sdk.dir=\$HOME/Android/Sdk" > local.properties

# Build and install on connected device
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Building from source

The project uses the standard Android Gradle plugin. No special setup is required beyond an installed Android SDK.

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew testDebugUnitTest      # Run the JVM unit tests (Robolectric)
./gradlew assembleRelease        # Build release APK (requires signing config)
```

### Testing

All test tiers run through one runner, `scripts/test_runner.py`, which handles
dependencies (instrumented tests need the emulator booted; scenarios need the
storage stack) plus progress and ETA. If [`just`](https://github.com/casey/just)
is installed, the recipes in `.justfile` wrap it:

```bash
just unit-test                   # JVM unit tests
just android-test                # Instrumented (Compose) tests on the emulator
just test                        # Every tier in dependency order
python scripts/test_runner.py --help
```

Useful flags: `--group unit|instrumented|infra|saf|scenario`, `--name <pattern>`,
and `--failed` to rerun only what failed last time.

## Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/picpocket/app/
│   │   │   ├── data/
│   │   │   │   ├── local/       — Room database (tags, workflows, runs) + DAOs
│   │   │   │   ├── store/       — File-based document store (versioned metadata + page images)
│   │   │   │   ├── repository/  — DocumentRepository, WorkflowRepository
│   │   │   │   └── workflow/    — DocumentEvent bus + trigger registry
│   │   │   ├── domain/
│   │   │   │   ├── scan/        — Quality tiers, page encoding
│   │   │   │   ├── scanner/     — ML Kit Document Scanner wrapper
│   │   │   │   ├── ocr/         — ML Kit text recognition
│   │   │   │   ├── filter/      — Per-page image filters
│   │   │   │   ├── export/      — PDF generation, page sizes
│   │   │   │   ├── pdfimport/   — Import external PDFs
│   │   │   │   ├── storage/     — SAF folder access
│   │   │   │   └── workflow/    — Engine, actions, conditions, artifact crypto
│   │   │   ├── drive/sync/      — Sync engine (device registry, upload/download, mutex, worker)
│   │   │   ├── ui/
│   │   │   │   ├── screens/     — home, scanner, detail, viewer, tags, workflows, sync, settings, …
│   │   │   │   ├── components/  — Reusable composables (tag selector, share sheet)
│   │   │   │   └── theme/       — Material3 theming, palettes
│   │   │   ├── navigation/      — NavGraph
│   │   │   └── di/              — Hilt modules
│   │   └── res/
│   ├── test/                    — JVM unit tests (Robolectric)
│   └── androidTest/             — Instrumented Compose tests
scripts/test_runner.py           — Unified test runner (all tiers)
sync-tests/                      — On-device end-to-end sync scenarios (pytest)
build.gradle.kts                 — App-level Gradle config
settings.gradle.kts              — Project-level Gradle config
```

## Tech Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material3 |
| Architecture | MVVM with Hilt DI |
| Local storage | File-based document store (versioned metadata + page images) + Room (SQLite) for tags, workflows, and run history |
| Document scanning | ML Kit Document Scanner API |
| OCR | ML Kit Text Recognition v2 |
| PDF | Android `PdfDocument` API (export), `PdfRenderer` (import) |
| Sync | Storage Access Framework + a custom multi-device engine (WorkManager) |
| Encryption | AndroidX Security (`EncryptedSharedPreferences`) + BouncyCastle Argon2/AES-GCM |
| Serialization | kotlinx.serialization |
| Navigation | Jetpack Navigation Compose |
| Image loading | Coil |
| Page reorder | `sh.calvin.reorderable` |
| Testing | JUnit 4, Robolectric, Turbine, MockK, Compose UI Test |

## Support

If you find this app useful, consider supporting its development:

[![Ko-fi](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?logo=buymeacoffee&logoColor=black)](https://ko-fi.com/pipolarbear)

<details>
<summary><b>Cryptocurrency</b></summary>

| | Address | QR |
|---|---|---|
| <img src=".github/images/btc.svg" width="20"> **BTC** | `bc1qgyffnlhp2uz2uhpmhfrspc5qxpj3y9m4lwgga5` | <img src=".github/images/btc-qr.png" width="64"> |
| <img src=".github/images/eth.svg" width="20"> **ETH** | `0x581b4810873698505FDF3aAf0a39430bb0D7d655` | <img src=".github/images/eth-qr.png" width="64"> |
| <img src=".github/images/sol.svg" width="20"> **SOL** | `6awadeXmfc7JUMQL5SEgZXDE4yaFDgWkPNRySLDDmh7E` | <img src=".github/images/sol-qr.png" width="64"> |

</details>

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
