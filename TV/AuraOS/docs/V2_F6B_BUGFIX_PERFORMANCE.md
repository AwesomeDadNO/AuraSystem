# AuraOS V2 – F6b: TV bug fixes + performance

Status: **compiled and JVM-tested only (262 tests). Not built with Gradle, not installed or run on any device or emulator by me.** The owner tests personally; nothing below is verified on the Google TV Streamer. No performance improvement is claimed: there are no before/after measurements, only reasoning about work removed.

## 1. UltraMax code entry
**Root cause (code-confirmed):** the code was typed on a custom DPAD grid (`CodePad` in `overlay/SheetHost.kt`) with no `TextField` and therefore no system IME; on a TV, characters could only be added one key at a time and there was no paste. **Unconfirmed:** the "numeric keyboard" the owner saw is most likely the Administrator PIN pad (digits 0–9), which `adminFlow.ensure` shows *before* the code sheet; the code grid itself did contain letters. Either way the entry is replaced.

**Fix:** `CodePad` is now a `BasicTextField` using the Android TV system keyboard: `KeyboardType.Ascii`, `Characters` capitalisation, autocorrect off, IME action Done. Input goes through `settings/CodeInput.normalize` (uppercase; spaces, dashes, line breaks dropped; non A–Z/0–9 removed; capped at 8), so paste works. Messages: "N of 8 characters.", "Only letters A–Z and digits 0–9 are allowed.", "Code complete. Choose Save." Save is enabled only for exactly 8 valid characters; Cancel closes. OK/Enter on the focused field opens the keyboard; Done hides it and moves focus to Save; Back closes the keyboard first, then the sheet. The code is never shown after saving, never logged; Keystore AES-GCM storage and Admin PIN authorisation are unchanged. The Administrator PIN pad stays numeric.
Risk: IME focus transitions and the dialog resizing with the keyboard cannot be verified off-device.

## 2. Details page clipped / not scrollable
**Root cause (code-confirmed):** `TitleDetailsHost` laid the page out as a non-scrolling `Column` filling the dialog: hero text (~450 dp of title, meta, overview, buttons, status) + tabs, with a `weight(1f)` content box for everything else. On a 1080-high canvas that left roughly 400 dp for seasons, episode cards (~290 dp), cast and recommendations, which were clipped with no vertical scroll. **Unconfirmed:** whether the physical Streamer additionally renders the dialog at a larger density/font scale than the 1920×1080 canvas assumes.

**Fix:** the page is one `LazyColumn` with stable keys: `hero` (fixed 500 dp item, not the viewport) → `tabs` → content sections (`DetailsSection`: cast, crew, advisory, seasons, related) → credit line. Focused controls scroll into view through the lazy list's focus handling; horizontal rows (episodes, cast, posters) stay `LazyRow`. The season list is a plain `Column` (a lazy column nested in the page list would have unbounded height). Hero text block keeps its fixed height, so focus-driven episode updates do not shift layout. Fonts and sizes are not reduced. Focus restoration, episode Play/Watched, orange focus and Back stack are unchanged. Episode position is remembered per season (`EpisodeMemory`).
Risk: DPAD focus search across the hero → tabs → rows → episode buttons has only been reasoned about, not exercised.

## 3. Performance changes (unmeasured)
- `KeystoreUltraCodeStore.isSet` now checks only that the ciphertext exists. Before, it called `load()`: with **no** stored code that returned immediately (no Keystore access, and no key generation on cold start); only when a code **is** stored did `MainActivity.onCreate` and Settings composition do synchronous AndroidKeyStore access + decryption on the main thread. Cost on the Streamer is unknown until measured. Trade-off: an undecryptable blob reads as "set/unverified" until the first resolve/verify fails closed.
- Details: one watched-set snapshot per change instead of a SharedPreferences read + set copy per episode per recomposition (introduced in F6, removed here); episode cards receive stable callbacks so a focus change recomposes the section but not every visible card; episode focus state lives in a holder read only by the hero and the seasons section, so the host does not recompose.
- Hero: scroll-to-top on hero action focus only when the page is not already at the top.
- Already in place from earlier steps: image loader (4 parallel, memory 32 MB / disk 64 MB, sized TMDB variants, cancel on leaving composition), cached season/episode data, debounced episode credits, no I/O on the main thread besides small SharedPreferences reads at startup.
- **Not done / not investigated without a profiler:** remaining startup work (SharedPreferences first reads, profile store, composition of the shell), recomposition scope of `AuraShell`, shadow/gradient cost, Baseline Profile. The supplied jank numbers (94% janky, P90 300 ms) include startup and image decode; they cannot be attributed to a single cause from the code alone.

## 3b. Image loader (Gemini/ChatGPT review items 3 and 4)
- **Separate concurrency:** disk reads and downloads used one 4-permit semaphore, so slow transfers could queue cached images. `images/TieredFetch` now gates disk reads (3 permits) and downloads (4) separately; decoding has its own 2-permit gate. Request de-duplication (`Dedup`), cancellation (permits released on cancel, tested), the 32 MB memory / 64 MB disk bounds and `writeDisk` atomic rename are unchanged. Unit-tested with a blocked network gate: a disk hit completes while all network permits are held.
- **Decode size:** decode now reads bounds first and sub-samples (`DecodeSizing`, power-of-two, never below the width of the requested TMDB variant, plus a 2.4 MP cap). TMDB variants are already sized, so the expected gain is small and is mostly protection against oversized files; it is not a demonstrated bottleneck.
- SHA-1 filename hashing per request is a micro-optimisation candidate only; left as is.

## 3c. Confirmed vs suspected bottlenecks
| Item | Status |
|---|---|
| Details page clipped/non-scrolling layout | **Confirmed by code** (fixed) |
| UltraMax custom grid instead of IME | **Confirmed by code** (fixed) |
| Disk reads and downloads share one semaphore | **Confirmed by code** (fixed; effect on frame time unmeasured) |
| Full-size decode without subsampling | **Confirmed by code**; practical effect likely small (variants are pre-sized) |
| Synchronous Keystore decrypt at startup | **Confirmed only when a code is stored**; duration unmeasured |
| Per-episode SharedPreferences set copies in details (my F6 code) | **Confirmed by code** (fixed) |
| Episode-focus recomposition | **Suspected**, not assumed expensive: only hero/seasons sections read the focus state and cards now get stable callbacks; counters below will show actual numbers |
| SharedPreferences init, PackageManager, system services at startup | **Suspected**; no evidence of any specific duration |
| Overall startup (3.3–3.9 s) and 94% janky frames | **Cause unknown**; figures include startup and image decode |
| Shadows/gradients, AuraShell recomposition scope | **Suspected**, uninvestigated |

## 3d. Debug diagnostics (new, `diag/Diag.kt`)
Enabled only when `BuildConfig.DEBUG`; a disabled call is one volatile read. Timings open `android.os.Trace` sections (visible in a Perfetto/systrace the owner captures) and log `AuraDiag: <name>: <N> us` at debug level; a counter/timing summary is logged in `onStop`. Only fixed stage names are recorded: never codes, tokens, URLs or profile data.
- Startup: `startup.onCreate`, `startup.prefs.mode`, `startup.adminGate`, `startup.imageLoader`, `startup.keystore.isSet`, `startup.profiles.load`, `milestone.firstComposeFrame` (time since the first Diag use in the process).
- Catalogue: `tmdb.catalogue.ready` (per page load).
- Artwork: counters `img.mem.hit`, `img.disk.hit`, `img.net.miss`; timings `img.disk.read`, `img.net.download`, `img.decode`.
- Details: counters `details.host.recompose`, `details.hero.recompose`, `details.seasons.recompose`, `details.episodeCard.recompose`, `details.focus.episode` (compare card recompositions with focus changes).
Filter: `adb logcat -s AuraDiag` (owner-run, debug build only).

## 4. Owner manual verification checklist (the owner runs these; resetting stats before each scenario)
Per scenario run `adb shell dumpsys gfxinfo no.aura.os.tv.v2demo reset`, perform only that scenario, then `adb shell dumpsys gfxinfo no.aura.os.tv.v2demo` and note Total frames / Janky frames / 50th / 90th / 95th / 99th percentile. Do not compare a cumulative run with a scenario run.
1. **Cold start:** force-stop, `adb shell am start -W -n no.aura.os.tv.v2demo/no.aura.os.v2.MainActivity` (TotalTime/WaitTime). Repeat 3×, report all values.
2. **Warm resume:** Home button, wait 10 s, reopen; `am start -W` again (only report a number if logcat/`am` shows it).
3. **First content visible:** time to first hero/shelf visible (stopwatch or screen recording), and when the hero artwork appears.
4. **Steady-state DPAD:** after step 3 settles, reset gfxinfo, move through 3 shelves left/right for ~20 s, read stats.
5. **Details scrolling:** open a series, reset, DPAD down to seasons → episodes → cast → recommendations, read stats.
6. **Episode focus updates:** reset, move across 10 episode cards, read stats; note whether the hero text updates without layout jumps.
7. **Player:** play a source, reset, use pause/seek/audio/subtitle menus for ~20 s, read stats.
8. Memory: `adb shell dumpsys meminfo no.aura.os.tv.v2demo` after scenarios 4 and 5 (Total PSS).
Functional checks: (a) Settings → Connections → UltraMax → Enter code: PIN pad (numeric) first, then the keyboard sheet; type/paste an 8-character code (lowercase and with dashes should normalise), Save, Verify. (b) Movie and series details: hero, then DOWN reaches tabs, cast/crew or seasons/episodes/episode cast/related, with the focused item always visible; Back restores the focus.
