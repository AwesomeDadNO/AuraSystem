# AURA AMP v1.3.7 Alpha — Queues, Cast & UI Fixes

## Changelog
- Album and playlist Play/Shuffle controls are icon-only; playlist edit uses a pencil-on-page icon; playlist/album download uses a round icon with gray/not-downloaded and fully orange/complete state.
- Favorites and Downloads folders lose the outer button border and use orange gradient circular icons with white artwork.
- Album/playlist download progress fills orange as tracks finish. The batch queue popup has an orange **Done** button. When opened, completed queue status remains visible until explicitly dismissed; otherwise the indicator disappears after completion.
- "Play next" is now separate from "Add to end of queue" and inserts after the current song, updates the native queue and preserves the normal queue.
- **Android Auto queue:** Native Media3 bridge now accepts up to 50 upcoming media items in one request; Aura Amp resolves and supplies up to 25 following songs, rather than trimming the queue to the current song plus one.
- **Cast startup:** native Google Cast sender is initialized after the local WebView UI becomes visible; pending song metadata is retained and applied when the Cast SDK is ready. Startup SDK errors are logged instead of shown as premature casting failures.
- **Norway charts:** primary Apple Music Norway feed retains its source label and has a second key-free iTunes Norway Top Songs (sales) feed as fallback. The app accurately labels which chart source is being shown; sources may be unavailable by country.
- Android version **1.3.7-alpha**.

## Sonos Wi-Fi integration research
Sonos speakers expose LAN UPnP/SOAP control on many models via port 1400, with optional authentication/security settings. This is an unsupported vendor protocol, not a user-safe production SDK. A native Sonos output selector would need speaker discovery, user approval, secure LAN permissions, playback-link eligibility, receiver status reconciliation and tests across hardware. The existing build does **not** claim native Sonos streaming; Chromecast and Bluetooth remain separate routes. References: https://support.sonos.com/en-ie/article/adjust-connection-security-settings , https://sonos.svrooij.io/services/av-transport .

## Scope / test notes
- Aura Amp Android only; no Aura Box or shared release-workflow changes.
- GitHub Actions covers source regression checks and Android compilation. Receiver, Car, LAN and source-account playback must still be device-tested.
- For complete Android Auto queues, upcoming cloud tracks must expose receiver/Media3-playable URLs. Unavailable streams cannot be included in a native playable queue.
