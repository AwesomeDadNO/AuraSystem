# AuraAmp player navigation design

## Intent

Make the expanded AuraAmp player feel like the supplied reference: the normal bottom navigation transforms into playback controls, while the player content rises into view. Minimizing reverses the transition and restores the normal navigation while the mini-player remains available.

## Chosen approach

Animate the existing bottom navigation area into one compact five-button transport row: shuffle, previous, play/pause, next, and repeat. Repeat cycles through off, repeat-all, and repeat-one. The player screen slides upward slowly as the transport row comes in; minimizing slides it down and restores the normal navigation.

Keep AuraPlay and queue as separate buttons on opposite sides of the player content, below the timeline: AuraPlay on the left and queue on the right. Keep the three-dot action in the player header. It opens the same track context sheet used elsewhere in AuraAmp, extended with artist actions such as follow/unfollow and opening the artist profile.

## Approaches considered

- **Morph the bottom navigation into the transport row (chosen):** uses the same screen edge for navigation and playback, matching the reference and leaving the cover area clear.
- **Keep navigation and add controls over the cover area:** rejected because it crowds the main player area and does not match the requested layout.

## Scope and constraints

- Applies to the AuraAmp phone UI in `Phone/AuraAmp/v2/app/src/main/assets/app.js` and its styles in `index.html`.
- Preserve existing playback, queue, AuraPlay, repeat, shuffle, artist-follow, and artist-profile behavior; connect the controls to those existing actions.
- Keep minimized-player behavior and restore normal navigation when the player collapses.
- No changes to AuraOS or Coretex are required for this UI work.
- No build or tests unless the user asks for them.
