# widgets-native-map

`NativeSurfaceFactory` for the `sdui.map` kind — a worked example of how to add a
platform-native widget to sdui-kmp without touching `:protocol` or `:runtime`.

## What it does

| target | implementation |
| --- | --- |
| Android | Google Maps Compose (`com.google.maps.android:maps-compose`) wrapping `GoogleMap` + `Marker`. |
| iOS | `MKMapView` via Compose Multiplatform's `UIKitView` interop. |
| Desktop (JVM) | Material 3 `Card` listing marker titles — Compose for Desktop has no first-class map. |
| Wasm/JS | Same Material 3 placeholder as desktop. |

All four targets satisfy the `NativeSurfaceFactory` contract; the `kind` is `"sdui.map"`
and `handledVersions` is `V1..V1` for now. Unknown kinds, unsupported versions, or any
decode failure all flow through the standard `NativeSurface.fallback` path documented in
[ADR 0005](../docs/adr/0005-native-surface-fallback.md).

## Wire-format config

`NativeSurface.config` is a `JsonObject`; this module decodes it as `MapSurfaceConfig`:

```json
{
  "center_lat": 37.7749,
  "center_lng": -122.4194,
  "zoom": 13,
  "markers": [
    {"id": "pickup", "lat": 37.78, "lng": -122.41, "title": "Pickup"}
  ]
}
```

`zoom` defaults to 13 if omitted; `markers` defaults to empty.

The factory also reads two `bindings` (Android only today): `center_lat` / `center_lng` /
`zoom` are looked up in the runtime `StateStore` and override the config defaults on every
recomposition. `events["markerTapped"]` is dispatched through `LocalActionDispatcher` when a
marker is tapped, after stamping `markerTapped.id` into the local state store.

## Wiring it up

```kotlin
val nativeRegistry = NativeSurfaceRegistry.build(clientVersion = SchemaVersion.V1) {
    register(MapSurfaceFactory.instance(requireApiKey = true))
}

CompositionLocalProvider(LocalNativeSurfaceRegistry provides nativeRegistry) {
    SduiHost(...)
}
```

## Google Maps API key

This module ships a placeholder key (`REPLACE_WITH_YOUR_KEY`) in its
`AndroidManifest.xml`. Hosts must override the `<meta-data>` element in their own
manifest:

```xml
<application>
    <meta-data
        android:name="com.google.android.geo.API_KEY"
        android:value="${MAPS_API_KEY}" />
</application>
```

When `requireApiKey = true` and the meta-data is the placeholder string, the factory
renders the same Material 3 placeholder used on Desktop / Wasm — so a host that registers
the factory but has not yet signed up for a Google Cloud key never crashes. Hosts with a
real key can pass `requireApiKey = false` to bypass the runtime check entirely.

**Never commit a real API key to the repo.** Use Gradle's `manifestPlaceholders` mechanism
or a Secrets Gradle Plugin to inject the key from CI / a local `secrets.properties`.

## Testing

- `commonTest`: `MapSurfaceConfigTest` — wire-format round-trip + decode failure paths.
- `androidUnitTest`: `MapSurfaceFactoryRobotTest` — registry contract smoke test.

Compose UI tests are deferred; they require either Robolectric or a real emulator and the
project does not yet run an instrumentation tier in CI.
