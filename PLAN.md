# Rokid-GMaps Plan

## Goal

Build a reworked `Rokid-Maps` fork that keeps the original `phone + glasses + shared` architecture, keeps the `OSM/osmdroid` renderer as the default base, and adds optional Google-powered search and routing for a personal MVP.

Longer term, this project should become the clean base for a bigger fusion with transit/subway and other Rokid apps.

## Current State

Status as of 2026-03-19:

- done: forked `Rokid-Maps` into `Rokid-GMaps`
- done: renamed app branding to `Rokid GMaps`
- done: kept original `osmdroid` map renderer
- done: added provider settings in the phone app
- done: added Google text search provider
- done: added Google route provider
- done: added OSM / OSRM fallback providers
- done: added route mode setting with `Drive` / `Walk` / `Transit`
- done: added nearby search and quick nearby category actions
- done: added route preview / recap flow on phone
- done: added route preview mode on glasses HUD
- done: added lightweight map background to glasses preview
- done: added initial transit provider/data-model scaffolding
- done: added first Google transit MVP through route mode selection
- done: added dedicated `TransitRouteProvider` integration for Google transit preview
- done: added richer transit leg summaries in the phone preview flow
- done: added multiple Google transit options with selection on phone
- done: kept selected transit option in session for preview and navigation launch
- done: reworked the phone home UI with quick route-mode buttons and one-tap HUD layout buttons
- done: collapsed provider/network/cache controls into an advanced settings panel
- done: reworked the glasses transit recap into a clearer plan-style view with active leg + upcoming changes
- done: build passes for `phone` and `glasses`
- done: confirmed `Routes API` works with the current Google key
- done: confirmed Google search works only when `Places API (New)` is enabled

Current outputs:

- `phone/build/outputs/apk/debug/phone-debug.apk`
- `glasses/build/outputs/apk/debug/glasses-debug.apk`

## Current MVP Boundaries

What exists now:

- phone app search using `OSM` or `Google`
- nearby search using current location
- quick nearby categories in the phone UI
- route calculation using `OSRM` or `Google`
- route mode selection using `Drive` / `Walk` / `Transit`
- first Google transit MVP
- route preview before launch
- glasses route overview / recap mode with lightweight map background
- glasses HUD streaming from the phone app
- provider selection and Google API key input in settings

What does not exist yet:

- richer subway/transit rendering with transfers, stations, line names and timing
- nearby category search explicitly mapped to Google place types
- polished provider UX
- backend or proxy for securing Google API usage
- merged `Clawsses` or super-app shell

## Priority Order

### P0. Stabilize The Google MVP

Goal: make the current Google-enabled fork reliable enough for repeated personal use.

- add clearer provider status in the phone UI
- show current active provider in search and navigation screens
- make Google search failures and fallback behavior obvious
- verify search -> select result -> route -> glasses HUD flow on real devices
- verify behavior with Wi-Fi off, bad key, disabled API, quota exceeded

### P1. Add Proper Travel Modes

Goal: stop treating Google routing as a single generic mode.

- keep refining route mode setting: `drive`, `walk`, `transit`
- wire Google Routes requests to the selected mode
- check HUD instruction quality for walking and transit modes
- keep OSRM fallback coherent for non-transit modes only

Important note:

- `Drive`, `Walk`, and a first `Transit` mode now exist in the MVP
- transit still needs its own dedicated provider/model instead of living only inside generic route parsing

### P2. Add Transit / Subway Foundation

Goal: prepare the actual `Rokid-Maps + Subway` fusion.

- define a `TransitRouteProvider` abstraction
- add a transit result model: legs, transfers, stations, line names, durations
- evaluate the cleanest source for MVP transit:
  - Google transit via Routes when available
  - existing `Subway` logic where reusable
- decide how transit steps should be displayed in the phone app
- decide how simplified transit steps should be streamed to the glasses HUD

### P3. Add Nearby And Category Search

Goal: restore quick navigation behavior beyond free-text search.

- expand category coverage beyond the first MVP buttons
- improve nearby result quality and ranking for OSM fallback
- map category chips more explicitly to Google supported place types
- keep fallback behavior when one provider fails

### P4. Clean Provider Architecture

Goal: keep the project extensible before more features land.

- separate `MapRenderer` from `PlaceProvider` and `RouteProvider` more explicitly
- move provider settings into a dedicated settings block or screen
- centralize provider validation and diagnostics
- avoid scattering provider checks across activities and services

### P5. Improve Glasses UX

Goal: make the glasses output useful for more than simple car-style routing.

- review HUD wording and maneuver mapping for Google routes
- improve long instruction readability on glasses
- add better transfer/arrival visuals for future transit mode
- validate mini-map and full HUD modes with Google-generated steps
- add a dedicated transit instruction view on glasses, separate from the default map view
- move glasses gesture handling to real Rokid hardware paths:
  - `KEYCODE_ENTER` for tap / confirm
  - `onGenericMotionEvent` for temple-pad motion
  - fallback key mapping for firmware that emits swipe as `DPAD_UP/DOWN`
- support slide gestures to switch between views:
  - default map / route view
  - transit recap / line-change view
  - later additional views if needed
- keep single tap for layout/zone switching
- reserve long press for a future action menu instead of overloading tap/slide interactions

### P5.1 Target HUD Interaction Model

Goal: make the glasses UI behave like a real navigation shell instead of a single static HUD.

- `single tap`: keep current layout/size toggle behavior
- `slide left/right` or `slide up/down`: switch between navigation views
- `default view`: map-first HUD
- `transit view`: large recap panel showing what to take, transfers, and arrival stop
- `long press` later: open a compact action menu for navigation actions and future app-level commands

Design direction:

- for transit, copy the spirit of `Rokid_Subway`: bigger, clearer legs and line changes
- do not force all information into the map view
- use separate views instead of trying to overlay too much on one screen

### P6. Prepare Super-App Direction

Goal: keep this repo usable later as one module inside a larger Rokid hub.

- keep this repo focused on maps/navigation first
- avoid mixing chat/AI/app-launcher concerns too early
- once maps + transit are stable, extract reusable shared pieces for future fusion

## Immediate Next Tasks

These are the next concrete tasks to execute:

1. Enrich transit instructions with cleaner station / line / transfer summaries on glasses as well as phone.
2. Validate the full preview -> launch -> glasses flow for `Transit` mode on real routes.
3. Improve in-app provider diagnostics and fallback messaging.
4. Add a compact transit plan header on glasses showing lines and transfer count.
5. Rework the transit presentation layer on glasses to match more of the `Rokid_Subway` visual hierarchy.

## Constraints And Assumptions

- personal MVP first, not Play Store release
- storing the Google API key on-device is acceptable for now
- `Places API (New)` and `Routes API` must both be enabled in Google Cloud
- default renderer remains `OSM/osmdroid` unless there is a strong reason to replace it
- glasses should stay lightweight; the phone app remains the main compute/network side

## Definition Of Done For The Next Milestone

The next milestone is complete when:

- Google search and Google walking route both work from the phone UI
- nearby/category search works with at least one provider
- route preview works on phone and glasses before launch
- Google transit preview and launch flow work end-to-end
- glasses can switch between map view and transit recap view during navigation
- provider status and common failures are understandable in-app
- the glasses HUD can display a full Google-backed route session without manual recovery
