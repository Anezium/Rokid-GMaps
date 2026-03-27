# Rokid-GMaps

<p align="center">
  <img src="Rokid_GMaps_logo.png" width="160" alt="Rokid-GMaps logo" />
</p>

> Based on [chartmann1590/Rokid-Maps](https://github.com/chartmann1590/Rokid-Maps) and [bcefghj/Rokid_Subway](https://github.com/bcefghj/Rokid_Subway) — full credit to both for the foundation.

Turn-by-turn navigation for Rokid AR glasses with optional Google provider support and transit mode. A phone app acts as the navigation brain; the glasses display a live AR HUD with a rotating map, maneuver instructions, and a transit recap view.

---

## What's new compared to the originals

### From Rokid-Maps

Rokid-Maps is hard-coded to OSM/Nominatim/OSRM — no API key, fully open-source routing. This fork keeps all of that as the default fallback and adds:

- **Pluggable provider system** — swap search and routing between OSM and Google at runtime via `ProviderRegistry`
- **Google Places API v1** support for search (optional, requires API key)
- **Google Routes API v2** support for routing (optional, same key)
- **Route modes** — Drive, Walk, Transit quick buttons on the phone UI
- **Nearby search** — 5 category shortcuts: Food, Coffee, Pharmacy, Gas, Metro
- **Route preview flow** — review the route on the phone before launching navigation
- **Transit as a first-class mode** — multiple route options displayed, selectable before launch
- **Glasses transit recap view** — dedicated HUD view showing active transit leg and upcoming transfers
- **Collapsed advanced settings panel** — cleaner default UI, provider/cache/network settings tucked away
- **`GlassesPrefs` singleton** — unified key management for BT device preferences (was split across files with mismatched keys)
- **Synchronized BT writes** — all socket writes go through a `synchronized(writer)` helper to prevent stream corruption under load
- **Real connection status** — "Connected/Selected/No glasses" based on actual BT socket state, not just saved prefs

### From Rokid_Subway

Rokid_Subway is a standalone voice-controlled subway app for glasses only (Amap API, Chinese ASR). This fork borrows the **concept** of transit as a dedicated mode and the data model approach for multi-leg routes — adapted for a phone+glasses architecture with typed search instead of voice.

---

## Architecture

```
Rokid-GMaps/
├── shared/     Bluetooth protocol — messages, codec, tile cache
├── phone/      Android phone app — search, routing, streaming, UI
└── glasses/    Android glasses app — AR HUD, map tiles, BT client
```

Phone and glasses communicate over **Bluetooth SPP** (serial port profile) using newline-delimited JSON messages. No cloud relay, no internet required between the two devices.

### Protocol messages

| Type | Direction | Description |
|------|-----------|-------------|
| `state` | phone → glasses | GPS position, bearing, speed, speed limit (1 Hz) |
| `route` | phone → glasses | Full waypoint list |
| `step` | phone → glasses | Current maneuver instruction |
| `steps_list` | phone → glasses | All steps with current index |
| `settings` | phone → glasses | Units, TTS, mini map, cache size |
| `nav_mode` | phone → glasses | Preview active flag |
| `notification` | phone → glasses | Forwarded phone notification |
| `wifi_creds` | phone → glasses | Hotspot credentials for tile downloads |
| `tile_req` | glasses → phone | Map tile proxy request |
| `tile_resp` | phone → glasses | Map tile data |
| `apk_start/chunk/end` | phone → glasses | OTA app update |

---

## Phone app

- Search via Nominatim (OSM, default) or Google Places API
- Routing via OSRM (default) or Google Routes API
- Route modes: Drive, Walk, Transit
- Nearby search with category shortcuts
- Route preview before navigation launch
- Transit option selection (Google Routes required)
- Notification forwarding to glasses
- WiFi credential sharing
- OTA APK push to glasses
- Configurable tile cache (50–500 MB)

## Glasses app

- Live rotating map — CartoDB Dark Matter tiles, green HUD overlay
- Route line, maneuver arrow, distance to next turn
- Three layout modes: Full, Corner, Mini
- Transit recap view for multi-leg routes
- Speed display with speed limit warning
- Turn alert overlay (triggers 200 m before turn)
- Status bar: BT, WiFi, battery, speed
- Touchpad gestures for mode switching

---

## Setup

### Requirements

- Android phone running API 28+
- Rokid AR glasses (Air, Max, or compatible)
- Android Studio Hedgehog or later
- Bluetooth pairing between the two devices done at OS level before launching

### Build

```bash
./gradlew assembleDebug
```

Outputs:
- `phone/build/outputs/apk/debug/phone-debug.apk`
- `glasses/build/outputs/apk/debug/glasses-debug.apk`

### Google API key (optional)

If you want Google search and routing, enter your key in the phone app under **Settings → Advanced Settings → Google API Key**. The key is stored locally in SharedPreferences and never leaves the device. Without it the app falls back to OSM/OSRM.

Enable the following APIs in Google Cloud Console:
- Places API
- Routes API

> **Note:** Transit mode requires Google Routes API. The OSM/OSRM fallback does not support transit.

### Rokid credentials

Rokid SDK credentials go in `local.properties` (already in `.gitignore`):

```properties
rokid.client.id=YOUR_CLIENT_ID
rokid.client.secret=YOUR_CLIENT_SECRET
rokid.access.key=YOUR_ACCESS_KEY
```

---

## Known limitations

- Transit is an MVP — no rich line-color rendering, no per-stop timing detail
- No voice input (unlike Rokid_Subway)
- Protocol has no version negotiation — phone and glasses apps should always be updated together
- `MainActivity` and `HudView` are large single files; refactoring is on the roadmap

---

## Credits

- [chartmann1590/Rokid-Maps](https://github.com/chartmann1590/Rokid-Maps) — original phone+glasses navigation architecture, Bluetooth protocol, all OSM/OSRM/Nominatim integration
- [bcefghj/Rokid_Subway](https://github.com/bcefghj/Rokid_Subway) — transit data model concepts and multi-leg route display approach
- Map tiles: [CartoDB Dark Matter](https://carto.com/basemaps/) (CC BY-SA)
- Geocoding: [Nominatim](https://nominatim.org/) / OpenStreetMap contributors
- Routing: [OSRM](http://project-osrm.org/)
