# 0015. Simulated foil light in the card viewer

Status: accepted (2026-11-19), for #167. Amends the **Foil** rule of ADR 0014 for the card viewer only; everything
else in ADR 0014 stays.

## Context

ADR 0014 allows foil in one place: a still, thin band on the featured set cover, with no gradients, shimmer or
animation anywhere else. Tilting a shiny card is the moment people film most in card content, and the full-screen card
viewer (#166) shows a single card on a dark surface with no other chrome. A council review on 2026-11-19 asked for a
tilt showcase there (#167), but only after this rule is amended, and with one condition: the effect must never read as
proof of a finish.

The app cannot see a finish. The official art from the CDN is the same image for every finish, and the effect would
be drawn by the app either way. The only honest source is what the user recorded on their own copy.

## Decision

- **Where**: simulated foil light is allowed **only inside the card viewer**, drawn over the card image and nowhere
  else. The viewer's chrome (close button, image name, the dark surface) stays static, as do buttons, chips, bars and
  backgrounds everywhere. `FoilEdge` and the featured set cover are unchanged.
- **Which cards**: only when the user **owns the shown print as Holo or Reverse Holo** (same card and language). Holo
  wins when both are owned. Normal and "Other" copies get no light, and neither do cards the user doesn't own.
- **Which image**: only the **official art**. The user's own photos stay as taken: light painted over a photo of a real
  copy would look like that copy's surface, which is exactly the kind of evidence it must not fake.
- **Clearly decorative**: the light is generic. Holo and Reverse Holo differ in strength and band width, not in a
  per-card mask, so it never claims where a card's foil sits. The viewer's bar says "Simulated holo light" (or
  "reverse holo") under the image name, so a screen recording carries the label. TalkBack skips the light itself.
- **How it moves**: the gyroscope drives a light band and a glare that moves at a different rate (slight parallax).
  The tilt is integrated from the rotation rate and eases back to rest, so the light reacts to movement and the phone
  can be held at any angle. Without a gyroscope, touch and hold the card, then drag. Sensors are registered only while
  the viewer is open and the app is resumed.
- **Reduced motion** (animations removed or animator scale 0): no sweep. The light stays still at rest, no sensor is
  registered, and a change to the setting applies while the viewer is open.

## Consequences

- The tilt math (integration, settling, display rotation, touch mapping, band and glare geometry) and the choice of
  finish are plain Kotlin with unit tests (`ui/components/FoilLight.kt`, `ui/collections/CardDetailImages.kt`).
- Gyro feel, 60 fps on a mid-range phone and battery with the viewer open have to be checked on a device.
- A future per-card foil mask (where the holo pattern really is) would be a new decision: it starts to describe the
  card, not just decorate it.

## Alternatives considered

- **Light on own photos too**: the most literal reading of "your holo copy", but it alters the user's evidence of a real
  copy. Rejected for the reason above.
- **Light on every holo-capable print**: would show a finish the user may not have and suggest the app knows it.
- **Guessed masks for holo (art window) and reverse holo (everything but the art)**: wrong for full-art and many older
  layouts, and it reads as a claim about the card.
- **Orientation from the rotation vector against a fixed baseline**: needs a baseline reset whenever the user shifts
  their grip; the eased rate integration does this on its own.
