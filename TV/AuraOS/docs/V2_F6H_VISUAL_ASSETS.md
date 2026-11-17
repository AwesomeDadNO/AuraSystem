# F6h visual polish — 2026-11-12

Owner approved the logo contact sheet and 12 category motifs, then requested integration in the next private test. Static 184×276 2:3 posters remain unchanged (explicitly reconfirmed). No merge/release/install/device action.

## Artwork and runtime rendering

- `src/main/assets/app_logos/approved_atlas.png`: owner-approved generated custom logo artwork, background removed using the image tool. 36 transparent atlas cells, no painted app plates. Known packages render their region into a contained 16:9 slot; underlying top navigation and known-app All Apps tiles are transparent. No claim of official supplied artwork.
- One shared immutable bitmap decoded off the UI thread; no per-recomposition image processing/network scraping. Exact packages only; unknown packages use native fallback. Package match changes presentation only, not app installation, launch authorization or account entitlement.
- SF Anytime, VGTV and MGM+ have artwork cells but no verified package mapping; retain native fallback until a real package can be verified. Do not infer app/TV availability from having artwork. NRK Super mobile package identification does not imply TV compatibility.
- `src/main/assets/category_art`: 12 generated genre backgrounds, lossily WebP-compressed at quality87 without resizing/recomposing the approved source. Total ~2.1MB. Source1672×941 rendered with center crop into exact256×144 cards. Type/genre IDs map to art; translated labels never decide mapping. Unmapped genres receive a neutral glass card.
- Category images decode at sample4 (~418×235) off-main, with a six-entry memory cache. Category names, orange accent, corner clipping and focus stay in Compose. No baked-in text. Original generated files/prompt notes are retained in the owner's workspace output folder.

## Category pages

- Full-screen category page with shared hero, transparent navigation, Back, independent Trending / Popular / New / Top Rated shelves.
- Trending: actual weekly trending filtered to selected media type + genre. Popular: genre discovery by popularity. New: genre discovery sorted by primary release/first-air date, excluding future/invalid dates. Top Rated: vote average with at least100 votes.
- Certification filtering for child profiles follows the existing data-layer gate. Partial failures preserve successful rows and offer reachable retry controls. Empty filtered trending pages can explicitly find further pages. At most20 pages and60 titles per shelf; pagination is per row. State is retained while details are open.
- Hero opens the same existing details/playback flow; official Trailer hook retained. No invented catalogue titles or playback availability.

## Video and glass test

- Episode image owns its playback/return focus; orange Aura play badge is visual only, with one image DPAD target. Click selects then enters existing playback resolution. Source selection/quality preferences and future-episode denial remain enforced.
- Watched toggle and More info are the two equal-width actions below. Synopsis remains a dialog; watched state remains per profile.
- Glass is a small translucent sheen and fine white edge on video/category cards, with no expensive backdrop blur, new dependency or extra focus target.
- Shared and details hero art starts68 canvas units lower and uses top-center crop inside clipped bounds. Actual face framing remains owner TV acceptance, not proven by JVM checks.

## Sources and acceptance

TMDB: https://developer.themoviedb.org/reference/discover-movie , https://developer.themoviedb.org/reference/discover-tv , https://developer.themoviedb.org/reference/trending-movies .

Exact package verification uses provider Play listings, e.g. https://play.google.com/store/apps/details?id=no.altibox.tv , https://play.google.com/store/apps/details?id=no.get.play.tv , https://play.google.com/store/apps/details?id=no.rikstv.app , https://play.google.com/store/apps/details?id=allente.b2c.tvclient , https://play.google.com/store/apps/details?id=air.no.nrksuper.app . Existing repo mappings are preserved; no extra package-visibility permission.

Owner checks: logos against approved sheet on actual installed apps; category artwork/glass/readability; complete category page and all four lane semantics; paging/error retry/DPAD/Back/details restoration; child profile and cancellation; episode image playback/return, watched/info50:50 and future gate; hero heads/safe framing on4K/85-inch display. Live TV/Sports remain explicitly labeled old demo pages. F6f baseline preserved.
