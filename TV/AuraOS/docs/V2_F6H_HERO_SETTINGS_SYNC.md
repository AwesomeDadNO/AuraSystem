# Hero, Settings and Simkl update — 2026-11-13

This owner-requested revision supersedes earlier hero font and Settings composition rules. Existing F6f baseline and F6h constraints remain.

- Shared hero titles use bold white sans-serif with dark shadow. Measure up to two wrapped lines, reduce size within fixed bounds, retain a fixed-height title slot. Extreme titles truncate at minimum size. No description/action displacement.
- Global Search includes a Settings group from catalog labels/category/aliases only, never stored values or credentials. Selecting a hit opens its category and requests the actual row after scrolling. Coming-later entries stay clearly unavailable. Existing profile/admin checks apply.
- Settings uses a compact category column and grouped surface pane, category descriptions and clearer toggle state. Android TV settings is available from header and General using the public ACTION_SETTINGS intent; show unavailable feedback, retain child restriction, do not silently change system preferences.
- Favorites/Watchlist now have an always-composed entry focus anchor outside lazy content. Continue Watching keys distinguish multiple episodes of the same series. These fix concrete source hazards; the reported TV crash has no captured device stacktrace and still requires reproduction/acceptance.
- New successful Simkl grants enable automatic read-only additive Plan-to-Watch import, request a first network job, then approximately hourly. Connections offers a real toggle and status. Existing connected profiles keep their prior off state until enabled. This is not full history sync or remote list mutation.
- Android JobScheduler controls timing: network/idle/OEM conditions may delay work. No exact alarm, new dependency, boot permission or persistent reboot job. Startup/resume/profile changes reconcile scheduling. Active adult profile only. Jobs/manual sync serialize token rotation, revalidate profile and credential generation, and cannot restore disconnected/deleted credentials. Refreshed credentials survive a subsequent fetch failure. Disconnect clears locally before remote revoke.

## Acceptance still open

1. TV: long/short hero titles, 4K/font scaling, no control movement, shadow contrast.
2. Search Settings with English/Norwegian labels; jump to offscreen row, category navigation/Back and child/admin restrictions. Android TV settings open/return on the actual OEM.
3. Open Saved repeatedly with empty and populated lists, multiple episodes of one series, profile changes, scrolling and returning from playback. Capture stacktrace if any launcher exit persists.
4. Simkl new PIN connection -> enabled + first import; approximate hourly run when allowed; off/on/restart/profile switch/disconnect during import; offline retry/status; no cross-profile imports or remote changes.

Official scheduling constraint: [JobInfo.Builder.setPeriodic](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPeriodic(long)).

Local JVM/build evidence belongs in status/PR; no TV/provider acceptance, merge, release or install is implied.
