# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-03-27

Initial MVP release of `Rokid-GMaps`.

### Added

- Phone + glasses navigation architecture with a shared Bluetooth JSON protocol.
- Default open-source stack with Nominatim search, OSRM routing, and osmdroid-based map rendering.
- Optional Google provider support with Places API v1 search and Routes API v2 routing.
- Route mode selection for driving, walking, and transit.
- Route preview flow on phone before navigation launch.
- Nearby search shortcuts for common destination categories.
- Transit option selection on phone for Google-backed transit routes.
- Glasses HUD with rotating live map, maneuver guidance, speed display, and route overlays.
- Glasses transit recap view for active legs and upcoming transfers.
- Wi-Fi credential sharing, notification forwarding, and OTA APK push to the glasses app.
- Project README with architecture, setup steps, limitations, and upstream credits.

### Changed

- Rebranded the project as `Rokid-GMaps`.
- Unified launcher icon usage on both Android apps by switching manifests to `@mipmap/ic_launcher`.
- Replaced the previous vector launcher setup with packaged PNG mipmap launcher assets for phone and glasses.

### Notes

- `Transit` mode requires Google Routes API.
- Google search requires Places API (New) to be enabled for the configured API key.
