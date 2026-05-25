# Rokid-GMaps

`Rokid-GMaps` is a reworked fork of `Rokid-Maps` for a phone-plus-glasses setup.

The current MVP keeps the original `Rokid-Maps` architecture and OSM renderer, but adds optional Google providers on the phone side:

- `Google Places API` for place search
- `Google Routes API` for navigation routes
- `OSM / Nominatim / OSRM` still available as fallback

## Current Layout

- `phone/`: Android phone app, main UI, search, route calculation, Bluetooth streaming
- `glasses/`: Rokid HUD app for navigation display
- `shared/`: shared protocol and models

## Current Provider Model

The renderer stays based on the original `Rokid-Maps` stack.

The phone app can now switch providers from its settings screen:

- search provider: `OSM` or `Google`
- route provider: `OSRM` or `Google`
- Google API key: entered directly in the phone app settings

Google is only used when enabled and when an API key is present.

## Google Setup

Enable billing and the following APIs in Google Cloud:

- `Places API`
- `Routes API`

Then open the phone app and set:

1. `Google API key`
2. `Use Google search`
3. `Use Google routes`

## Build

From the project root:

```powershell
.\gradlew.bat assembleDebug
```

Outputs:

- `phone/build/outputs/apk/debug/phone-debug.apk`
- `glasses/build/outputs/apk/debug/glasses-debug.apk`

## MVP Scope

Current goal:

- keep `Rokid-Maps` as the base
- add Google provider integration cleanly
- keep the system simple enough to evolve into a larger Rokid super-app later

Not done yet:

- transit/subway fusion
- polished provider UX
- backend/proxy for securing Google calls
- deeper merge with `Clawsses`
