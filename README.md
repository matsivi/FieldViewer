### FieldViewer (AFieldVieweR)

Measure real‑world areas with AR. Tap to drop points, see live perimeter and area, review results, and optionally visualize polygons on Google Maps.

![Android API](https://img.shields.io/badge/Android-API%2024%2B-brightgreen.svg) ![ARCore](https://img.shields.io/badge/ARCore-1.50.0-blueviolet.svg) ![License](https://img.shields.io/badge/License-MIT-informational.svg)

### Overview
FieldViewer lets you place AR anchors on detected planes to outline a polygon and instantly compute its perimeter and area. The default area calculation uses the Shoelace formula on a 2D projection of the placed 3D points, with an option to switch to a more stable triangulation‑based evaluation if your environment is noisy. Minimal flow: Home → AR Measure → Results.

### Key Features
- **ARCore plane detection**
- **Center crosshair + tap/+Point to add anchors**
- **Live perimeter (m) and area (m²) updates**
- **Undo** and **Finish** actions
- **(Optional)** Results screen & Google Maps polygon preview

### Requirements
- **Android**: 7.0 (API 24) → latest
- **Device**: Google Play Services for AR (ARCore) installed
- **Java**: 8+ (project uses Java 11 source/target)
- **Gradle/AGP**: Gradle 8.13, Android Gradle Plugin 8.12.0

### Getting Started
1) Clone
```bash
git clone https://github.com/your-org/FieldViewer.git
cd FieldViewer
```
2) Open in Android Studio (JDK 11+). Let it sync Gradle.
3) (Optional) Enable Google Maps polygon preview: set your key in `app/src/main/res/values/google_maps_api.xml` under `string name="google_maps_key"`.
4) Build & Run
- Select a physical ARCore‑supported device (recommended) or an ARCore emulator image.
- Choose Build Variant: `debug` or `release`.
- Run the `app` configuration.

### Permissions
- **CAMERA** (required)
- **ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION** (optional, for maps)

### Tech Stack
- **ARCore**: 1.50.0 (project currently uses 1.42.0; upgradeable)
- **Sceneform UX**: 1.17.1
- **AndroidX + Material Components**

### Project Structure
```text
app/
  src/main/java/com/example/fieldviewer/
    MainActivity.java                 # Home → navigate to AR
    ARMeasureActivity.java            # AR session, points, metrics
    ResultActivity.java               # (Optional) summary & share
    SavedMeasurementsActivity.java    # (Optional) list
    SavedMeasurementDetailActivity.java
  src/main/res/layout/
    activity_main.xml
    activity_ar_measure.xml
    activity_result.xml
    activity_saved_measurements.xml
    activity_saved_measurement_detail.xml
  src/main/AndroidManifest.xml
  assets/background_loop.mp4          # optional background video
```

### How it Works
1) A center crosshair performs `hitTest` each tap to find a plane.
2) On hit, an AR anchor is created and stored in order.
3) Anchor positions are projected to 2D using (x, z) on the plane.
4) **Perimeter** = sum of edge distances; **Area** = Shoelace over the ordered 2D vertices.
5) For noisy data, consider triangulation/fan decomposition for improved stability.

### Screenshots / GIFs
- AR measure: `docs/screen-ar.png`
- Results: `docs/screen-results.png`
- Maps polygon: `docs/screen-maps.png`

### Troubleshooting
- **Black camera preview**: Ensure camera permission is granted; verify Google Play Services for AR is installed and up to date; close other apps with camera overlays.
- **Inconvertible types / `ArFragment` errors**: Use `com.google.ar.sceneform.ux.ArFragment` and align `sceneform-ux` dependency with imports and your layout `<fragment>` tag. Clean/rebuild if classpath changed.

### Roadmap / TODO
- Export measurements (GeoJSON/KML)
- Cloud save/sync
- Multi‑polygon sessions
- In‑app accuracy tips and calibration

### License
MIT — See `LICENSE`.

### Acknowledgements
- Google **ARCore**
- **Sceneform** UX


