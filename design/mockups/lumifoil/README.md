# Lumifoil UI studies

Three exploratory directions for the new Lumifoil name. Open `index.html` directly;
no server, installation, camera access, or API credentials are needed. The font is
embedded from the app's existing Schibsted Grotesk asset. Card art loads from the
verified TCGdex CDN URLs; an internet connection is needed for those images.

These are design options, not a replacement for the accepted Index system in
ADR 0010. No Android source, app identity, backend, or existing design decision has
been changed.

| Direction | Composition and material | Brand expression | Tradeoff |
| --- | --- | --- | --- |
| Pearl | Porcelain surfaces, sea-glass actions, fanned card art | A single iridescent foil edge; faceted line mark | Closest to the existing light Index UI; foil must stay subtle |
| Afterglow | Deep pine surfaces, chartreuse actions, one spotlight card | The artwork looks illuminated against quiet surroundings | A production version needs a companion light mode |
| Archive | Warm paper, tobacco ink, compact catalog rows | Serif wordmark and headings; collector numbers as catalog references | Serif type is limited to larger text to protect scanability |

All directions use the same illustrative 1,248-card collection and prices. Cardmarket
prices are explicitly labeled as combining languages. Card images and collector
numbers are real; ownership, quantities, values, scan results and set completion
are synthetic demonstration data.

## Explore

- **Compare all three** shows collection screens side by side; mobile stacks them.
- Open a direction to see its collection and scanner together.
- Tap card art or a recent card for details; change finish or add a demo copy.
- Open sets for a sample checklist, or switch to custom binders.
- Search the four-card demo catalog by name or collector number. An unmatched
  search demonstrates the empty state.
- Simulate scans, pause/resume, undo, and clear the two-item review queue.
- The app navigation also includes preview settings.

The scanner is an explicitly labeled mock camera. All changes are in-memory and
reset on reload. There is no production data write or upload. Illustrative scanning
is manually triggered to keep the prototype predictable and avoid requesting the
camera.

## Implementation notes

The HTML has inline CSS/JavaScript and no framework dependency. Each mock phone
uses a 360 × 748 logical layout, scaled to the available width. Actual page layout
stacks at 560px; opening a direction shows two phones above that breakpoint and
one column below it. Within each phone, content scrolls independently while
navigation stays visible. Local search updates preserve keyboard focus. Controls
use native buttons, inputs, focus indicators and accessible state labels; motion
respects `prefers-reduced-motion`.

Artwork: TCGdex / The Pokémon Company. Schibsted Grotesk: the app's existing
SIL Open Font License, under `android/app/src/main/assets/licenses/schibsted-grotesk/`.

## Validation (2026-11-18)

The rendered prototype was checked at 728px and 390px widths. All three concepts
passed interaction checks for scanning, undo, pause/resume, clearing the review
queue, opening a 42-slot sample checklist, searching by name and number, empty
search results, selecting a finish, adding a copy, switching to binders and opening
settings. No JavaScript errors, failed images or horizontal page overflow were
observed. Images were also independently checked for HTTP 200 responses.

The static design detector flags the intentionally small mock-phone metadata and
Pearl's 4px horizontal foil edge. This is an exploratory browser prototype;
production Compose implementation still needs device testing, scalable type,
complete light/dark schemes, real camera states and contrast validation.

A separate visual review inspected the comparison and each direction at both
widths and returned **SHIP** for the exploratory deliverable, with no material
fixes required. It identified small metadata as a production follow-up and
confirmed the horizontal foil edge matches Pearl's intended visual identity.

## Pearl council corrections

After Fabian preferred Pearl and requested the council, its smallest captions and
navigation labels were raised to 12 logical pixels (secondary actions/data text
13px). The two-provider review then identified three bounded mockup corrections:

- Scan Undo now restores the preceding card, count and manually chosen finish
  together. It uses an in-memory addition history and is disabled when that
  history is exhausted; the seeded demo collection cannot be undone.
- Owned checklist slots use solid sea-glass with white numbers; missing slots
  remain hollow. Each slot also names its owned/missing state accessibly.
- The scanner displays an explicit finish selector, starting at `Normal (default)`;
  a selection is marked `(chosen)`. Recognition does not claim to detect finish
  or condition. Changing a finish is a local user choice for the displayed card.

### Native implementation handoff

| Area | Requirement |
| --- | --- |
| Set browsing | Keep one featured set cover and add compact access to the remaining sets, with completion state. |
| Scanner identity | Give the last-added collector number stronger hierarchy, consistent with Index. |
| Review queue | Use a visible count badge or tinted control when matches need review. |
| Header action | Decide and label scan versus manual add explicitly; the current plus shortcut opens scanning. |
| Accessibility | Validate scalable type, 48dp targets, TalkBack, and contrast on real devices; secondary text on raised surfaces is close to the minimum. |
| Themes and camera | Provide a complete companion dark scheme and real camera lifecycle states. |

These are requirements for a later Compose implementation, not changes made to
the Android app by this design-study review.

The two-provider council approved Pearl as the direction for native implementation
after two review rounds. The approval covers the design study. The final small
review-queue correction was then verified separately: undoing a confirmation
returns its item to the pending queue as well as restoring count/card/finish.
Focused browser checks passed at 728px and 390px, including fully visible scanner
actions, with no JavaScript errors or horizontal page overflow.
