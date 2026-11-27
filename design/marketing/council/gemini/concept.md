# Foilark Flyer Concept — Gemini 4 Neon

## The Idea

This flyer rejects generic SaaS app brochures and instead presents Foilark as a tactile German collector's specimen sheet printed on warm archival stock (`#F3EFE6`) with jet-black editorial typography and signal-vermilion accents (`#E6411A`). Rather than a floating smartphone mockup, a full-bleed dark neoprene photographic plate showcases a completely blank silver-prismatic holographic card inside a crystal-clear toploader with a vermilion penny-sleeve pull-tab—framed by subtle optical scanner brackets to communicate on-device card recognition in a split second. Beneath it, Google Play's 12-tester closed-test hurdle is reframed as a 12-slot set-completion strip (`01–12`, filled vs. hollow slots echoing Foilark's own collection checklist) with a highlighted open slot inviting the reader to complete the set. A bold two-line German headline (*„Pokémon-Karten offline scannen.“*) paired with Cardmarket pricing and zero-ads/open-source callouts ensures full comprehension in under three seconds. The bottom action zone pairs three crisp numbered join steps with a high-contrast framed QR code and URL pill (`https://foilark.app/test`).

## Why It Catches a Collector

- **Insider collector detail:** Every serious TCG collector who trades, grades, or ships on Cardmarket recognizes the rigid toploader + penny sleeve + sticky pull-tab immediately—signaling that Foilark was built by people who understand physical card care, without touching any Pokémon IP.
- **Stand-out counter contrast:** Against glossy, visually crowded booster displays and neon tournament posters on a German LGS counter, matte warm-cream stock bisected by a luminous holographic foil plate creates instant physical stopping power.
- **Set-completion psychology:** Reframing Google Play's dry "12 testers for 14 days" requirement into a 12-slot collector checklist (`DEIN SLOT FEHLT →`) speaks the native language of set builders and makes joining feel purposeful and concrete.
- **Three-second value clarity:** Offline scanning, Cardmarket prices, no ads, no app account, and open source answer the exact pain points German collectors have with existing cloud/subscription scanner apps.

## Image Prompts Used

One bespoke studio photograph was generated using the built-in Antigravity `image-generator` tool (`assets/holo-card-hero.jpg`, 1264 × 848 px):

> Overhead-angled macro studio photograph: A single completely blank, borderless, ultra-reflective silver-metallic holographic trading card (standard 63×88 proportion, rounded corners) tucked inside a crystal-clear rigid plastic protective toploader and clear inner penny sleeve. A tiny bright vermilion-orange (#E63E1A) matte sticky pull-tab protrudes neatly from the top-right edge of the inner sleeve. Resting slightly fanned underneath it are two additional matte-off-white blank cards in clear sleeves. The cards lie tilted at a dynamic angle on a deep matte charcoal-black neoprene tabletop surface (#111418). Dramatic directional studio key light hits the blank holographic foil surface, creating a razor-sharp diagonal sweep of luminous prismatic rainbow light refraction (liquid silver, cyan, electric teal, subtle violet, and warm gold sheen) across the blank metallic card face, with realistic micro-specular glints on the clear plastic toploader edges and soft architectural shadows on the matte dark tabletop. Horizontal 3:2 landscape framing, centered-right hero card with generous clean dark matte space around the edges. Shot on 85mm macro lens, razor-sharp focus, subtle shallow depth of field. Strict negative constraints: no text, letters, numbers, logos, illustrations, characters, creatures, birds, wings, Poké Balls, yellow borders, symbols, or card-back patterns.

## Rebuild Command

```sh
node design/marketing/council/gemini/build.mjs
```
