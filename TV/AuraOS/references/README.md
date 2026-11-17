# Design references — AuraOS v2

Approved v2 mockups, committed under `references/v2/` (1672×941 PNG). They are **design references**: not code, not runtime screenshots, not authorisation to package the depicted artwork or any third-party logos. Titles, artists, teams and scores in them are fictional.

| File | Screen | Used by |
|---|---|---|
| `v2-01-design-system.png` | Design System & Guidelines v1.0 (palette, type, spacing, radii, components) | all |
| `v2-02-auramp-velvet-horizon.png` | AuraAmp (For You) | Phase B |
| `v2-03-aurabox-silver-realm-detail.png` | AuraBox title detail | Phase B/C |
| `v2-04-livetv-aurora-concert.png` | Live TV + TV Guide | Phase B |
| `v2-05-sports-center.png` | Sports Center | Phase B |
| `v2-06-tvshows-northern-crown.png` | TV Shows | Phase B |
| `v2-07-movies-last-voyage.png` | Movies | Phase B |
| `v2-08-home-silver-realm.png` | **AuraOS Home — primary acceptance reference for Demo Task 01** | Phase A |

## Missing reference assets
- **Settings (new minimalist design):** not supplied yet. Needed before Phase C.
- Services page (renamed from Subscriptions), full AuraAmp player/queue, AuraBox seasons/episodes/source selection/loading/player, album & playlist detail, sports match detail, EPG program detail: no mockups supplied.
- The nine orange-era references described in the legacy manifest were never committed and are **superseded** by the set above.

Do not substitute old screenshots or unrelated artwork and call them approved designs.

## Artwork status (Visual Implementation Directive, Stage 1)
The mockups are single flattened images: card art has titles and progress bars baked in, hero art has the navigation, text and buttons baked in.
**Clean, separate artwork cannot be extracted from them**, and the full screenshots must not be used as backgrounds.
Interim solution (honest placeholder, NOT final art): `tools/v2-art/gen_colour_fields.py` produces tiny colour-field drawables
(median-downsampled from the mockup regions, UI/text zones masked and inpainted, soft upscaled) into `tv/app/src/main/res/drawable-nodpi/`
(`art_hero_*`, `card_*`, `art_album_*`). They carry each image's colour distribution and composition, not its detail.
**Dedicated artwork production is requested** for: 5 hero backdrops (Silver Realm, Last Voyage, Northern Crown, Aurora Concert, Velvet Horizon),
the Silver Realm poster, ~60 card images (titles listed in the mockups), 9 album covers, cast portraits, team crests and sports/venue images, and licensed
service logos. Delivered as separate clean images (no text/UI), 16:9 backdrops >= 1920x1080 and cards >= 480x214.

## Brand assets
`references/brand/auraos-wordmark-source.png` (2172x724) and `auraos-banner-16x9-source.png` (1672x941) are the supplied approved AURAOS logo files (transparent PNG). Derived resources: `res/drawable-nodpi/auraos_wordmark.png`, `res/drawable-nodpi/banner.png`. Still missing: an approved square app icon.
