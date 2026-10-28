# Sofa Spirit — asset provenance

Created for this project on 2026-10-28 using the built-in OpenAI `image_gen` tool through the `imagegen` skill. Both originals are **1254×1254 RGBA PNG**, with genuine alpha (face 0..254; hand 0..255). Original files are in `android/app/src/main/assets/fx/`. Runtime decoding uses sample size 2; two decoded sprites together occupy 3,145,032 bytes on the tested projector. The right hand is a mirrored mesh of the same original hand, not a third downloaded image.

| File | Origin / use |
|---|---|
| `spirit-face.png` | Newly generated spectral face under a translucent veil; face layer |
| `spirit-hand.png` | Newly generated five-digit left hand; left layer and mirrored right layer |
| `manifest.json` | Project-authored scene metadata, duration and fixed asset allowlist |

No frame, music, person, brand or graphic from the private reference video was incorporated. No external stock art, game ROM, font file or third-party FX asset pack was added. There is no separate stock-asset license file to redistribute. This record establishes provenance; visual acceptance is still the owner's decision. Existing application dependency notices continue to apply; no new rendering library was introduced.

## Exact generation prompts

Face — built-in generation, `transparent_background=true`:

> Use case: stylized-concept. Asset type: original RGBA sprite for a projection illusion called Sofa Spirit. A single ghostly human face, frontal, eyes softly closed, mouth subtly open, sculpturally realistic believable face with cheekbones, forehead and chin emerging through thin spectral silk. Cold ivory luminous highlights and translucent soft shadows, restrained eerie elegant art, no gore. Isolate ONLY the face and a little spectral veil tapering softly around its silhouette, no shoulders, no hands, no scene or furniture, no solid rectangle, no text or watermark. Face fills 75% of a square frame, generous transparent margins, genuinely transparent background with soft alpha falloff. Lighting designed to read when additively projected onto dark gray upholstery. High quality detailed original art, no borrowed characters.

Hand — built-in generation, `transparent_background=true`:

> Use case: stylized-concept. Asset type: original RGBA sprite for Sofa Spirit projection illusion. A SINGLE anatomically correct LEFT human hand, palm facing the viewer, all five digits clearly readable (one thumb on viewer right, four distinct fingers), fingers spread with gentle natural bends, convincing joints and palm creases, graceful eerie hand pressing outward against an invisible membrane, small wrist tapered to wisps. Cool ivory translucent spectral skin, soft sculptural highlights and nuanced gray shadows, matching a realistic ghost face under spectral silk. Isolate only the entire hand and small wrist; no arms, no second hand, no face, no furniture, no scene. Square composition with generous 15% transparent margin on ALL sides, hand centered, fingertips all inside frame. Genuine transparent background and soft alpha falloff at wrist; sharp readable silhouette at fingers. High quality realistic original art. No text, no watermark, no circles, no cartoon line hand.

Generation output was copied into the project unchanged. Mirroring, global alpha, mesh movement and clipping happen in the Android renderer. Hashes are recorded in `RUNTIME_SNAPSHOT.json`.
