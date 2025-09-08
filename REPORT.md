### FieldViewer Development Report

### Table of Contents
- [Title & Abstract](#title--abstract)
- [Objectives & Scope](#objectives--scope)
- [System Requirements & Environment](#system-requirements--environment)
- [Design & Architecture](#design--architecture)
- [Implementation Steps](#implementation-steps)
- [Algorithms & Math](#algorithms--math)
- [Testing & Validation](#testing--validation)
- [Accessibility & UX Considerations](#accessibility--ux-considerations)
- [Performance & Limitations](#performance--limitations)
- [Risks & Mitigations](#risks--mitigations)
- [Project Management](#project-management)
- [Conclusion](#conclusion)
- [Appendices](#appendices)

### Title & Abstract
I built FieldViewer, an Android app that measures real‑world areas in AR. I detect horizontal planes, let the user place points at the center crosshair, and compute live perimeter and area from the ordered anchors. I use a simple 2D projection and the Shoelace formula for area, which performed well in practice; I also evaluated triangulation for noisy conditions. The flow is minimal (Home → AR Measure → Results), and I optionally visualize the polygon on Google Maps for review and sharing.

### Objectives & Scope
- I implemented an AR measurement tool that can:
  - Place points (anchors) on detected planes via center‑screen hit tests.
  - Continuously compute and display perimeter (m) and area (m²).
  - Support Undo and Finish.
  - Optionally open a Results screen to review metrics and display the polygon on Google Maps, and export/share KML/JSON.

### System Requirements & Environment
- Android 7.0 (API 24) → latest; `compileSdk=34`, `targetSdk=34`.
- Device must support Google Play Services for AR (ARCore).
- IDE: Android Studio (JDK 11+).
- Build: Gradle 8.13, Android Gradle Plugin 8.12.0.
- Language: Java (source/target compatibility set to Java 11).
- Key libraries: ARCore 1.42.0, Sceneform UX 1.17.1, AndroidX, Material, Google Maps (optional).

### Design & Architecture
- Activities:
  - `MainActivity` (Home): a simple entry with a looping, low‑opacity background video and buttons to start measuring or view saved items.
  - `ARMeasureActivity` (AR Measure): hosts Sceneform UX `ArFragment`, overlays the crosshair, and manages anchors/metrics.
  - `ResultActivity` (Results): optional map view with polygon overlay and export/share actions.
- AR fragment choice: I chose Sceneform UX `ArFragment` because it simplifies camera/session management and common UX affordances while still allowing direct access to ARCore `Frame`/`hitTest` for precise control.
- UI layers: I draw a center crosshair and two translucent bars (top metrics; bottom actions: +Point, Undo, Finish) over the `ArFragment`.
- Data model: When I tap +Point, I create an ARCore `Anchor` and keep anchors in order. For geometry, I project 3D positions to a local 2D ground plane using the first anchor as origin, then compute perimeter and area.

### Implementation Steps
1) Project skeleton (Empty Views Activity, Java)
   - I created a new Android project in Java and added activities/layouts for Home, AR Measure, and Results.
2) Permissions & Manifest entries
   - I added `CAMERA` and optional location permissions, declared AR features, and added ARCore & Maps metadata in `AndroidManifest.xml`.
3) ARCore readiness
   - I implemented the standard `ArCoreApk` availability check and install/update flow. I guard all AR operations behind a readiness flag and permissions.
4) AR layout overlays
   - I overlaid a center crosshair and translucent top/bottom bars for metrics and controls. Buttons wire to handlers for add/undo/finish.
5) Center‑screen hitTest, anchors, markers, Undo
   - On +Point, I take the center of the `ArSceneView`, perform `frame.hitTest(cx, cy)`, filter for upward‑facing horizontal planes, and create an anchor. I render a small sphere `Node` as a visual marker and update metrics. Undo detaches the last anchor and removes its node.
6) Geometry layer (2D projection; perimeter; Shoelace for area)
   - I project anchor positions to 2D using local (x,z) relative to the first anchor. Perimeter is the sum of edge lengths; area uses the Shoelace formula over the ordered vertices. I chose Shoelace by default due to simplicity and performance; I evaluated triangulation (fan decomposition) as a more stable alternative when point order may be noisy, but for ordered taps Shoelace is accurate and fast.
7) Finish flow → `ResultActivity`
   - I package the 2D coordinates and computed metrics as intent extras and start the Results screen. I also capture a coarse origin location and heading to optionally georeference the polygon for maps.
8) (Optional) Google Maps polygon
   - In Results, I project local meters to `LatLng` around the captured origin (with heading correction), render a polygon, and provide export/share to KML and quick‑open in Maps/Earth. I chose local 2D + origin heading over full geo‑anchoring to keep the pipeline simple.
9) (Optional) Polishing
   - I added click debounce, simple plane‑type filtering, basic error toasts, and UI enablement states.

### Algorithms & Math
- Perimeter: for vertices \(p_i=(x_i,y_i)\), \(\text{Perimeter}=\sum_i \lVert p_{i+1}-p_i \rVert\) with indices modulo \(n\).
- Shoelace (area): \(A=\frac{1}{2}\left|\sum_{i=0}^{n-1} (x_i y_{i+1} - x_{i+1} y_i)\right|\).
- Projection strategy: I use the first anchor pose as the origin and project each anchor pose \((tx,tz)\) to 2D as \((x,z)\), effectively working on the world XZ ground plane. This is robust for horizontal planes and keeps units in meters.
- Numerical stability: For small polygons with well‑ordered taps, Shoelace is stable. If anchors are noisy or self‑intersections occur, a triangulation/fan approach or pre‑processing (e.g., smoothing, reordering, convex hull) may be preferred.

### Testing & Validation
- Devices: I validated on an ARCore‑supported physical device and an ARCore emulator image to confirm session startup, plane detection, and rendering.
- Scenarios:
  - Minimum of 3 points to enable Finish; verified Undo removes last anchor and updates metrics.
  - Repeated +Point taps with debounce to avoid duplicates.
  - No plane hit: I display a toast prompting the user to scan a flat surface.
  - Black preview: permission and ARCore install/update checks prevent entry until ready.
  - Metrics formatting: I format area in m² and compute acres as `area / 4046.8564224`.

### Accessibility & UX Considerations
- I used large, high‑contrast touch targets and readable metrics overlays. I show toasts for common error states (AR not ready, no plane, too few points). The center crosshair aids precise placement.

### Performance & Limitations
- Known constraints: AR plane detection requires good lighting and sufficient texture; long sessions can drift slightly. Self‑intersecting polygons will produce areas per the mathematical formula, which may not match intent.
- Future improvements: snap‑to‑grid or angle snapping, simple smoothing of anchors, a confidence/error estimate, optional triangulation fallback, and better handling of sloped planes.

### Risks & Mitigations
- Permissions denial: I request at runtime and exit gracefully with guidance.
- Unsupported devices: I check `ArCoreApk` availability and abort early with a clear message.
- Dependency conflicts: I aligned Sceneform UX with ARCore and kept Maps optional behind a key in `google_maps_api.xml`.

### Project Management
- Timeline (milestones):
  - Week 1: Project setup, activities/layouts, background video on Home.
  - Week 2: ARCore readiness flow and Sceneform integration.
  - Week 3: Hit testing, anchors, markers, Undo, live metrics.
  - Week 4: Results screen, optional Maps polygon, export/share.
  - Week 5: Polishing, error handling, formatting, documentation.
- Versioning: initial `1.0` with semantic versioning intended for future updates.
- Tools: Android Studio, Git/GitHub; Gradle builds; device + emulator testing.
- Branching: trunk‑based with feature branches for larger changes (e.g., Maps integration).

### Conclusion
I achieved a reliable AR area measurement app with a clean flow and responsive UI. The Shoelace‑based geometry on a local 2D projection provided accurate and performant measurements for typical use. Future work will focus on stability under noisy conditions (e.g., triangulation), richer exports, and usability refinements.

### Appendices
- Screenshots/GIFs: see `docs/screen-ar.png`, `docs/screen-results.png`, `docs/screen-maps.png`.
- Code excerpts (raycast and geometry):

```java
// Center-screen hitTest and anchor creation (excerpt from ARMeasureActivity)
int w = arFragment.getArSceneView().getWidth();
int h = arFragment.getArSceneView().getHeight();
float cx = w / 2f, cy = h / 2f;
com.google.ar.core.Frame frame = arFragment.getArSceneView().getArFrame();
java.util.List<com.google.ar.core.HitResult> hits = frame.hitTest(cx, cy);
for (com.google.ar.core.HitResult hit : hits) {
    com.google.ar.core.Trackable trackable = hit.getTrackable();
    if (trackable instanceof com.google.ar.core.Plane) {
        com.google.ar.core.Plane plane = (com.google.ar.core.Plane) trackable;
        if (plane.getType() != com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
        if (!plane.isPoseInPolygon(hit.getHitPose())) continue;
        com.google.ar.core.Anchor anchor = hit.createAnchor();
        anchors.add(anchor);
        // ... add marker node and update metrics
        break;
    }
}
```

```java
// 2D projection and Shoelace area (excerpt from ARMeasureActivity)
private java.util.List<P2> projectAnchorsTo2D() {
    java.util.List<P2> pts = new java.util.ArrayList<>();
    if (anchors.isEmpty()) return pts;
    com.google.ar.core.Pose origin = anchors.get(0).getPose();
    float ox = origin.tx(), oz = origin.tz();
    for (com.google.ar.core.Anchor a : anchors) {
        com.google.ar.core.Pose p = a.getPose();
        pts.add(new P2(p.tx() - ox, p.tz() - oz));
    }
    return pts;
}

private static double areaShoelaceSqMeters(java.util.List<P2> pts){
    if (pts.size() < 3) return 0;
    double s = 0;
    for (int i = 0; i < pts.size(); i++){
        P2 a = pts.get(i), b = pts.get((i + 1) % pts.size());
        s += a.x * b.y - b.x * a.y;
    }
    return Math.abs(s) * 0.5;
}
```

- References: ARCore docs, Sceneform UX samples, Google Maps Android SDK.


