# Pearl validation on the emulator (#101)

Emulator part of #101, on talos (Android 17 emulator, `google_apis/x86_64`), English pack and debug sample data.
Build: branch `android/pearl-validation-101`, debug APK installed with `adb install -r`. Viewports: 1080×2400 at
420 dpi (411 dp wide, the override talos had) and the native 720×1600 at 320 dpi (360 dp, compact phone). Each
sheet shows four screens scaled to 360 px. Captures were taken after the screen had loaded.

Real-camera states, TalkBack speech and the S25 are not covered here; see "Open for the phone" below.

## Results

| Check | Where | Result |
| --- | --- | --- |
| Collections home, multi-set browse, language chips | 411 dp, light + dark, 1.0/1.3/2.0x | OK. `light-1`, `dark-1` |
| Set checklist, Compact and Cards | 411 dp + 360 dp, both themes, 1.0/1.3/2.0x | OK. "002 · Miss…" fixed: "Missing" is now its own line under the number (1.3x and 2.0x show it in full). `font13-dark`, `font20-dark` |
| Missing-card bar | 411 dp, both themes | Fixed: Back closed the whole set screen; now it closes the bar first. `dark-back-and-focus` |
| Card detail, prices, copies | 411 dp, both themes, 1.0/1.3/2.0x | OK. `light-2`, `dark-2` |
| Copy sheet (edit copy) | 411 dp, both themes, 1.0/1.3/2.0x | OK. With the keyboard open, Save/Cancel stay above it. Back: keyboard, then sheet. Edits are dropped without asking. |
| Binder (collection detail), edit-collection sheet | 411 dp + 360 dp, both themes | Fixed: at 1.3x the details were cut ("German, bough…"), at 2.0x the name too ("Glurak…"). Details now wrap, name gets two lines (`font13-dark` shows the binder before the fix, `font20-light` after). At 2.0x the middle column is very narrow: #121. The value sort puts €7.87 before $0.67; that is #118. |
| Scanner: permission, live, paused, lab | 411 dp, light + dark | OK on the emulator's virtual scene. Allow camera → system dialog → live. Real matches need the phone. `light-1`, `light-4` |
| Review queue: empty, item with two matches | 411 dp + 360 dp, both themes, 1.0/1.3/2.0x | OK. Quantity, Skip and Confirm stay above the navigation bar. I added the debug sample scans on talos for this. `light-4`, `dark-3` |
| Search, results, card from search | 411 dp, both themes, 1.0/1.3/2.0x | OK. Results stay above the keyboard; the field has a Clear button. |
| Settings, gallery, My contributions, consent | 411 dp + 360 dp, both themes | Fixed: the licenses line used the larger placeholder type, now it matches the hints. `light-3` |
| Dialogs: Add languages, Remove English | 411 dp, light | OK (both cancelled, nothing changed). `light-4` |
| Selected tab on nested screens | all routes | Fixed: Scan → Review, Lab and Settings → Contribute, My contributions, Design system had no tab selected. Scan and Settings are now nested graphs like Collections and Search. Collections → set, binder, card and Search → card were already right. Checked on the device for each route (`selected="true"` in `uiautomator dump`). |
| Navigation labels at large font | 360 dp, 1.3x and 2.0x | Fixed: "Collecti…" at 1.3x and "Collec…", "Settin…" at 2.0x. Labels now shrink just enough to fit, never below 12 sp. `font13-360dp-nav-before-after` |
| Header wordmark at large font | 360 dp, 2.0x | Fixed: "foilark" broke into "foilar" and "k". It now stays on one line and shrinks, down to its 24 sp default. `font20-360dp-header-before-after` |
| Touch targets (48 dp) | `uiautomator dump` of every capture above | OK. The only nodes under 48 dp were rows cut off at the screen edge, and the sheet drag handle (its touch area is 48 dp). |
| Text contrast on screen | pixels from 12 screens, both themes | OK. Lowest real text: 4.62:1 (light, muted text on the selected match), 4.68:1 (dark). Primary on selection tint 4.97:1 light / 5.67:1 dark. The faint number printed on the grey art of a missing card is 3.2 to 3.8:1; it repeats the label below it and is not read out. |
| Contrast of controls | pixel runs | Missing-slot outline 3.67:1 light / 4.55:1 dark, field outline 3.67:1, switch track 3.04:1 / 3.21:1. Outlined buttons and chips have a 1.3 to 1.5:1 border; their text labels them, so WCAG doesn't need the border. |
| Owned vs missing | Compact and Cards, both themes | OK. Solid vs hollow, plus the word "Missing" and "owned/missing" in each slot's description. |
| System Back | every screen above | OK: Lab, Review → Scan; set → home; card → set; search card → results; sheets and dialogs close; Settings tab → Scan; Scan → leaves the app. Fixed the missing-card bar (see above). |
| Status bar, gesture bar, keyboard insets | all captures | OK. Dark icons on light, light on dark; nothing sits under the status bar or the gesture bar; sheets and Search handle the keyboard. |
| Keyboard focus (Tab, Enter) | Collections home, set screen, dark | Works and the focus ring is visible. Order goes header → navigation bar → content: #120. Enter on a focused chip applies it. `dark-back-and-focus` |
| TalkBack | talos (TalkBack 17 is installed) | It starts, but `adb input swipe` doesn't move its focus, so order and speech could not be checked here. Labels from the dump look right (e.g. "002 Ivysaur, missing", "Review, 3 scans waiting", "151, 7 of 165 cards, $17.25"). Left for the phone. |

Other notes, not changed:

- The set header says "7/165" (printed total) while the filter says "Missing 200" (all 207 slots, secret rares
  included). Correct, but it can read oddly.
- Top-level app bar titles start at 16 dp, the content at the 20 dp gutter.
- One "not responding" dialog on talos right after a reinstall; the trace showed the main thread waiting on the
  render thread (`HardwareRenderer.setStopped`) while the emulator stalled, not app code. talos stopped answering adb
  for about a minute at the same time.

Device settings changed for the checks and reset afterwards: night mode, font scale, display size/density (back to
the 1080×2400 / 420 override), TalkBack, stay-awake. Left on talos: camera permission for the debug app, three
debug sample scans in Review.

## Open for the phone (S25, Fabian present)

- Real-camera states from #98: a real match ("Added", big number, latest-card row, Change sheet), low confidence and
  Review, glare/dim/foil, multi-step Undo vs. Collections count and review badge, pause/resume, leaving and
  returning, camera errors.
- TalkBack: order, labels and state announcements on every screen, including the new scan states.
- Search speed with all seven packs (#99 comment).
- A look at the fixes from this PR on the phone at 1.3x (Cards view "Missing", nav labels, binder rows).
