# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

The repo builds two Android apps that share a `:core` library module:

- **Edu Launcher** (`:launcher`, `id.kelasku.edulauncher`) is the classroom home screen (HOME category). It reads Papan Guru's boards and opens them through `core/BoardsContract`.
- **Papan Guru** (`:app`, `id.kelasku.papanguru`), described in the rest of this section.

**Papan Guru** is an offline Android smart-board app for interactive classroom displays. It runs in landscape and also appears in the TV (Leanback) launcher. Boards have multiple pages and are autosaved on the device. The app supports pens, shapes, text, selection, images, PDF import, and PDF/PNG export. The README and all user-facing text are in Indonesian. Keep new UI strings and content descriptions in Indonesian too.

`Panduan Teknis Penyiapan Server Kelas Interaktif.docx` is a planning document for a future sync backend. It describes Node.js 24, TypeScript, `ws` and MySQL behind Caddy, with `stroke.begin`/`stroke.points`/`stroke.end` messages. No backend code exists yet. Read the document with `textutil -convert txt -stdout "<file>"` before doing any sync or networking work. `docs/blueprint-kelas-siswa.md` is the draft design built on it: the class flow (worksheets each student fills privately, teacher monitoring, one-at-a-time presentations, teacher-approved joins), the WebSocket protocol, database schema, the web student app (`web-siswa/`) and the changes needed in this app. Its open decisions are listed in §14.

## Build & test

You need JDK 17 and Android SDK Platform 35 with Build Tools 35.0.0. On this machine the SDK is at `/Users/mac/Documents/sdk`. `local.properties` points there and is gitignored.

```sh
./gradlew :app:assembleDebug                 # → app/build/outputs/apk/debug/app-debug.apk (Papan Guru)
./gradlew :launcher:assembleDebug            # → launcher/build/outputs/apk/debug/launcher-debug.apk (Edu Launcher)
./gradlew :app:testDebugUnitTest             # JVM unit tests (app/src/test)
./gradlew :app:testDebugUnitTest --tests 'id.kelasku.papanguru.BoardModelTest.codecRoundTrip'
./gradlew :app:lintDebug                     # catches API-level problems (minSdk 23)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`artifacts/papan-guru-debug.apk` and `artifacts/edu-launcher-debug.apk` are prebuilt copies for installing on classroom devices (APKs are gitignored). README.md has the manual on-device checklist ("Uji di layar interaktif"). Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each release.

## Architecture

Neither app has third-party runtime dependencies: no AndroidX, Compose or XML layouts. Everything uses framework classes, and the UI is built in code through the helpers in `core/.../Ui.kt`. JUnit and `org.json` are test-only dependencies. `org.json` is needed because android.jar only has stubs of it on the JVM. `minSdk` is 23, so guard newer APIs with `Build.VERSION.SDK_INT` checks. Only debug builds allow cleartext traffic (the `cleartext` manifest placeholder), because the class server is plain `http://` during development. Release builds stay HTTPS-only.

Avoid `use {}` on `PdfRenderer` and its pages. They only implement `AutoCloseable` on newer releases, so close them explicitly.

- **`BoardModel.kt`** is pure Kotlin with no `android.*` imports, so it can be unit tested.
  - **Items are immutable.** `InkItem`, `ShapeItem`, `TextItem` and `ImageItem` never change; edits create new instances.
  - **History.** `BoardPage` keeps undo/redo as stacks of item-list snapshots (`commit` / `undo` / `redo` / `discardLast`). Every change goes through `page.commit(newList)`.
  - **Coordinates.** All coordinates are board coordinates. `BoardViewport` maps them to the screen with `screen = board * scale + (x, y)`. Stroke widths and text sizes are divided by zoom when created, so they look the same on screen at any zoom.
  - **Item ids.** `BoardDocument.newId()` hands out ids as `slot × 2^32 + counter` (`ItemIds`). The teacher is slot 0, and classroom students get slots from the sync server, so ids never collide across devices and stay exact in JavaScript. Items from other slots don't advance a document's counter.
  - **Page ids.** Each `BoardPage` has a stable `id` (`p_…`), saved in `board.json`. Boards saved before page ids existed get fresh ones on load. `copy()` (duplicating a page) gives a new id.
  - **Geometry.** `Geometry` holds the hit tests, the lasso point-in-polygon test, and partial erasing (`eraseInk` splits a stroke into pieces).
- **`BoardView.kt`** is the drawing surface and tool state machine.
  - **Gestures.** With one pointer it runs the current `Tool`. A second pointer cancels the unfinished action and switches to pinch/pan.
  - **Working copy.** Erase, move and scale preview their changes on a `working` copy of the item list. The result is committed when the pointer lifts.
  - **Rendering.** Committed content is drawn into a cached bitmap layer, keyed by page, revision, background and viewport. The live stroke is drawn on top of it. The layer is bypassed while navigating or while a working copy exists.
  - **Double-tap.** `DoubleTap` reports a finger double-tap, which opens the quick menu. The first tap may already have committed something (for example a dot). It is rolled back with `discardLast()` if `page.revision` hasn't changed since.
  - **Erasers.** A palm touch (large `touchMajor`) and the stylus eraser end both act as the eraser.
  - **Callbacks.** `onChanged(content)` drives toolbar refresh and autosave in the activity.
- **`BoardRenderer.kt`** handles backgrounds, patterns and items. It is shared by the view, thumbnails and export.
  - **Path caching.** Only the UI-thread renderer may set `cachePaths = true`, which stores built `Path`s in `item.renderCache`. Export renderers on worker threads must pass `false`.
  - **Images.** `ImageCache` decodes board images lazily into an LRU cache.
- **`BoardStore.kt`** stores each board under `filesDir/boards/<uuid>/`:
  - `board.json` (written by `BoardCodec`; points are a compact `"x,y,..."` string rounded to 0.1),
  - `meta.json` (read by the board list),
  - `thumb.png`,
  - `images/`.

  Saves run in order on one worker thread, shared by all `BoardStore` instances, from a `BoardSnapshot`, which is safe to hand off because items are immutable. Files are written to a `.tmp` file and then renamed. Loading deletes image files that no page references, since history isn't persisted. The codec only accepts image names that match `SAFE_FILE`.
- **`BoardFiles.kt`** handles blocking image and PDF import and export. Run it off the UI thread.
  - **PDF import.** Each PDF page is rendered to a JPEG and becomes a white page holding one `locked` `ImageItem`. Locked images can't be selected or erased.
  - **Export bounds.** Exports cover `exportBounds`, which is the home frame (the view at 100% zoom) grown to include all content.
- **`:core`** (`id.kelasku.core`) holds `Ui`, the icons both apps use, and `BoardsContract`. `gradle.properties` sets `android.nonTransitiveRClass=false`, so app code can use core drawables as plain `R.drawable.*`.
- **Launcher ↔ board app contract (`BoardsContract`).** Both apps declare the signature permission `id.kelasku.permission.BOARDS`. Declaring it in both means install order doesn't matter, but both APKs must share a signing key.
  - **`BoardsProvider`** (exported, guarded by the permission) lists boards (`content://<pkg>.boards/boards`) and serves thumbnails (`/thumb/<id>`). Before listing, it waits briefly for pending saves.
  - **`LauncherEntry`** is an exported activity-alias of `MainActivity`, guarded by the permission. `MainActivity` honours the `EXTRA_*` extras only when `intent.component` is this alias, so the normal launcher icon can't be used to inject them. `MainActivity` is `singleTask`, so requests reach a running board through `onNewIntent`. Extras are ignored on recreation.
  - **The launcher picks PDFs itself** and passes the URI with `FLAG_GRANT_READ_URI_PERMISSION`, so cancelling the picker leaves no empty board.
  - **Dev builds.** `-PdevInstall` gives both apps a `.dev` suffix. The dev launcher targets `id.kelasku.papanguru.dev` through the `board_package` resValue.
- **`launcher/.../HomeActivity.kt`** is Edu Launcher's single screen. It shows the clock, big Papan Guru tiles, recent boards from the provider (loaded off the UI thread), shortcut tiles, and an all-apps overlay. Loading labels and icons for about 200 apps takes seconds, so the app list is loaded in the background at start, cached, and reloaded on `PACKAGE_ADDED`/`REMOVED`/`CHANGED`. Back does nothing while it is the default home app.
  - **Icons.** Both apps use vector/adaptive launcher icons (`mipmap-anydpi-v26`, plain vector in `mipmap/` for API 23–25). Papan Guru's TV banners are PNGs in `app/.../drawable-xhdpi/`. `-PdevInstall` swaps in the orange `_dev` icon (and banner) through the `appIcon`/`appBanner` manifest placeholders.
- **`MainActivity.kt`** builds the header, the bottom dock (inside a `HorizontalScrollView`), the selection bar, and all dialogs and popups.
  - **Settings and autosave.** Tool settings live in SharedPreferences (`papan`). Autosave is debounced by 1.5 s and also runs in `onPause`.
  - **File pickers.** They use `startActivityForResult` with request codes `REQ_SAVE`, `REQ_IMAGE` and `REQ_PDF`. `pendingWrite` holds the writer for the next create-document result.
  - **System bars.** The activity runs in immersive mode. Call `ui.hideSystemBars()` again after dialogs and pickers close.
- **`Instruments.kt`** defines the ruler, set square and protractor. They live in **screen space**, so they keep their real-cm size when the board zooms; the scale comes from `xdpi`. They are owned and drawn by `BoardView`.
  - **Pen snapping.** A pen stroke that starts within `SNAP` dp of an edge locks to that edge for the whole stroke. Each point is projected with `Instrument.project`, then converted to board coordinates.
  - **Touch priority.** Instrument controls (close, rotate) come first, then edge snapping for pen tools, then dragging the instrument body, then the current tool.
- **Compass.** `Tool.COMPASS` is a gesture inside `BoardView`. The radius locks once the finger has swept about 10° around the center. The result is committed as an `InkItem` arc.
- **Multi-write.** `BoardView.multiWrite` keeps one `LiveStroke` per pointer id. Two-finger navigation is then only available with `Tool.PAN`.
- **`ClassroomTools.kt`** holds the screen-space widgets added to the board's `FrameLayout`. These are not board content and are not saved.
  - **Cards.** `FloatingCard` subclasses (timer, name picker, dice, calculator) are draggable by their title bar.
  - **Overlays.** `CurtainView` and `SpotlightView` are inserted at index 1, right above the board. They return `false` from `ACTION_DOWN` where touches should fall through to the board.
  - **Calculator logic.** `Calculator` is a pure state machine with unit tests.
  - **Name picker.** The student list is stored in SharedPreferences (`pickerNames`).
- **`CaptureProvider.kt`** is a read-only, non-exported provider (`${applicationId}.captures`) used for sharing area captures. It deletes captures older than 24 hours. Its filename regex must stay in sync with the `createTempFile("Papan-Guru-", ".png")` prefix and suffix.
