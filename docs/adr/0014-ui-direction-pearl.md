# 0014. UI direction: Pearl

Status: accepted (2026-11-18). Builds on ADR 0010 and replaces its palette and chrome; its number hierarchy and
owned/missing rules stay.

## Context

After the rename to Lumifoil (#15), three new directions were studied in `design/mockups/lumifoil/` (#86): Pearl,
Afterglow and Archive. Fabian preferred Pearl, and a two-provider council approved it after two review rounds. The
study is a scaled 360 px browser mockup with synthetic data. This ADR records the visual rules for the native app;
it does not say which screens already follow them (see "Implementation status").

## Decision

**Pearl** is the app's visual direction. It is the light, calm successor to Index:

- **Surfaces**: cool porcelain. Page `#FAFBFC`, raised `#EEF2F4`, ink `#26353C`, dividers `#D9E1E5`. Secondary text
  is `#5E6A72`, a little darker than the study's `#657078`, so it reaches 4.5:1 on raised surfaces too.
- **Actions**: sea-glass `#356C6C` with white text, one solid button per screen; other actions are outlined or text
  buttons. Selection (navigation pill, selected chips) uses the pale selection tint `#DFEDEB` with sea-glass on it.
- **Night**: a companion dark scheme designed on its own terms, not an inversion: deep blue-green slate surfaces
  (`#111718` up to `#2A3435`), light sea-glass `#8CCBC2`, muted error and warning containers. Card art stays the
  brightest thing on screen. It follows the system setting.
- **Camera**: the camera area keeps its own dark roles in both themes, since it always shows a photo.
- **Type**: Schibsted Grotesk throughout, in `sp`, so it follows the system font size. Headings use weight 650 with
  tight tracking, body text is 15 sp, and nothing is smaller than 12 sp. The study's 360 px canvas and 12 px
  captions are reference geometry, not production sizes.
- **Collector numbers stay the strongest type** (from ADR 0010): `006/165` at 64 sp bold on scan, review and detail,
  the total muted and lighter, tabular figures. Prices use proportional figures, since Schibsted's tabular set also
  widens `.` and `,`.
- **Owned and missing**: owned slots are **solid** (sea-glass, white number), missing slots are **hollow** (an
  outline dark enough for 3:1, number in secondary text). Shape carries the meaning, not colour alone; every slot
  also says "owned" or "missing" to TalkBack. This follows the council correction in the study. Energy colours no
  longer fill checklist slots, and #97 dropped them from the collection screens altogether: the card art already
  shows the type, and a second colour code next to solid/hollow would blur the owned/missing signal.
- **Shapes**: corners 5 dp (slots), 7 dp (chips, card images), 10 dp (buttons, fields), 14 dp (featured cover),
  22 dp (sheets). **Spacing** in steps of 2, 4, 8, 12, 16, 24 and 32 dp, a 20 dp screen gutter, 48 dp touch targets.
- **Icons**: one outline set on a 24 dp grid, 1.7 stroke, round caps. No filled or third-party icon sets.
- **Brand**: the wordmark is lower-case "lumifoil" next to a neutral faceted mark (a cut prism in one line weight).
  No Poké Ball, no card back. The launcher icon and the app identity are unchanged.
- **Foil**: one still, thin horizontal band of four pale stops, only on the **featured set cover**. No gradients,
  shimmer or animation on buttons, chips, bars or backgrounds.
- **Focus and states**: a 2 dp focus ring in its own blue, separate from sea-glass, so focus never looks like
  selection. Disabled controls use Material's dimmed roles. Errors and warnings show text in a tinted box and are
  announced; colour is never the only signal.

### Header shortcut: scan, not manual add

The header shortcut opens **scanning** and is labelled with the scan icon and the word "Scan". It is never a bare
plus. Why:

- Scanning is how cards get into the app; the study's plus also opened the scanner.
- Manual add needs a chosen card first, so it belongs on the card detail and search screens, not in a header.
- A plus next to "New collection" could mean three different things. A word cannot.

The existing "New" button on the Collections home stays (spoken as "New collection"); it creates a collection, not a
card.

### Navigation

The four destinations, their order (Scan, Collections, Search, Settings), the start screen and Back behaviour stay
as they are. The study's order puts Collections first; changing the order or start screen is a behaviour change and
is left out.

## Implementation status (2026-11-18)

- Done in #96: colour roles for both schemes, type, shapes, spacing, icons, wordmark and mark, app bars, navigation
  bar, buttons, chips, focus ring, notices, rows, completion bar, count badge, the foil cover component, and the
  scan shortcut on the Collections home. A debug-only gallery (Settings → Design system gallery) shows every piece.
  A unit test checks the contrast of the main role pairs in both schemes.
- Not done yet: the screens themselves still use their Index layouts on the new roles. #97 (collections, sets,
  featured cover), #98 (scanner and review), #99 (card detail, search, prices) and #100 (settings and supporting
  flows) adapt them. #101 validates the whole app on devices, with TalkBack.

## Alternatives considered

- **Afterglow**: a dark gallery with chartreuse actions. Strong at night, but needs a separate light design for
  daylight use.
- **Archive**: warm paper and serif headings. Most tactile, but serif type limits small labels.
- **Keep Index**: the blue palette no longer fits the Lumifoil name and its foil idea.
