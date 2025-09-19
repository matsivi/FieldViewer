package com.example.fieldviewer;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import java.util.ArrayList;

/**
 * Results activity that displays measurement data and provides map visualization
 * Features:
 * - Google Maps integration with satellite view
 * - Interactive polygon with draggable vertices and handles
 * - Export functionality (KML, JSON)
 * - Polygon manipulation (move, rotate, flip)
 * - Real-time area and perimeter calculations
 */
public class ResultActivity extends AppCompatActivity implements OnMapReadyCallback {

    // Data received from AR measurement
    private ArrayList<double[]> poly2d;  // 2D polygon coordinates from AR
    private Double originLat, originLng;  // GPS origin for georeferencing
    private Double headingRad;  // Device heading at first point for map alignment
    private java.util.List<LatLng> latLngs;  // Converted to map coordinates

    // Map and polygon visualization components
    private GoogleMap map;
    private Polygon polygon;  // The visual polygon on the map
    private final java.util.List<Marker> vertexMarkers = new java.util.ArrayList<>();  // Draggable vertex markers
    private Marker polygonHandleMarker;  // Orange center handle for moving entire polygon
    private LatLng lastHandleDragLatLng;  // Previous position for move calculations

    // Rotation functionality
    private Marker rotationHandleMarker;  // Blue handle for rotating polygon
    private java.util.List<LatLng> rotateStartLatLngs;  // Original positions before rotation
    private double rotateStartAngleRad;  // Starting angle for rotation
    private double rotationHandleRadiusMeters = 10.0; // Default 10 m radius for rotation handle, not precise

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_result);
        
        // Handle system UI insets for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize UI views
        TextView tvAreaFinal = findViewById(R.id.tvAreaFinal);
        TextView tvAcresFinal = findViewById(R.id.tvPerimeterFinal);
        Button btnNew = findViewById(R.id.btnNew);
        Button btnOpenInMaps = findViewById(R.id.btnOpenInMaps);
        Button btnShare = findViewById(R.id.btnShare);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnFlipEW = findViewById(R.id.btnFlipEW);
        Button btnFlipNS = findViewById(R.id.btnFlipNS);
        Button btnSavedSmall = findViewById(R.id.btnSavedSmall);
        android.widget.EditText etFileName = findViewById(R.id.etFileName);

        // Extract data passed from AR measurement activity
        poly2d = (ArrayList<double[]>) getIntent().getSerializableExtra("poly2d");
        double area = getIntent().getDoubleExtra("area", 0.0);
        // Stremma: 1 stremma = 1000 m² (Greek land measurement unit)
        double stremma = area / 1000.0;
        originLat = (Double) getIntent().getSerializableExtra("originLat");
        originLng = (Double) getIntent().getSerializableExtra("originLng");
        headingRad = (Double) getIntent().getDoubleExtra("headingRad", Double.NaN);

        // Display initial metrics
        if (tvAreaFinal != null) tvAreaFinal.setText(String.format(java.util.Locale.US, "Area: %.2f m²", area));
        if (tvAcresFinal != null) tvAcresFinal.setText(String.format(java.util.Locale.US, "Stremma: %.2f στρ", stremma));

        // Set up button click handlers
        if (btnNew != null) {
            btnNew.setText("+");
            btnNew.setAllCaps(false);
            btnNew.setTransformationMethod(null);
            btnNew.setOnClickListener(v -> finish());
        }
        if (btnSavedSmall != null) {
            btnSavedSmall.setOnClickListener(v -> {
                android.content.Intent i = new android.content.Intent(ResultActivity.this, SavedMeasurementsActivity.class);
                startActivity(i);
            });
        }
        if (btnOpenInMaps != null) {
			btnOpenInMaps.setOnClickListener(v -> openInGoogleEarthOrFallback());
		}
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareKml(etFileName != null ? etFileName.getText().toString() : null));
        }
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> saveAsJson(etFileName != null ? etFileName.getText().toString() : null));
        }
        if (btnFlipEW != null) {
            btnFlipEW.setOnClickListener(v -> flipPolygon(true));
        }
        if (btnFlipNS != null) {
            btnFlipNS.setOnClickListener(v -> flipPolygon(false));
        }

        // Initialize Google Map
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mapFragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.map = googleMap;
        // Set Satellite basemap for better field visualization
        googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
        
        // Ensure gestures are enabled for interaction
        try {
            com.google.android.gms.maps.UiSettings ui = googleMap.getUiSettings();
            ui.setAllGesturesEnabled(true);
            ui.setScrollGesturesEnabledDuringRotateOrZoom(true);
            ui.setMapToolbarEnabled(false);
            ui.setCompassEnabled(true);
            ui.setZoomControlsEnabled(true);
        } catch (Exception ignored) {}
        if (poly2d == null || poly2d.isEmpty()) return;

        // Enable My Location layer if permissions granted
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                googleMap.setMyLocationEnabled(true);
            } catch (SecurityException ignored) {}
        }

        // Project local meters (x,z) to lat/lng degrees around origin if available
        latLngs = new java.util.ArrayList<>();
        if (originLat != null && originLng != null) {
            // Apply true-north-corrected heading so AR local X/Z aligns to East/North
            double theta = (headingRad != null && !headingRad.isNaN()) ? headingRad : 0.0;
            double cosT = Math.cos(theta);
            double sinT = Math.sin(theta);
            // meters per degree at origin
            double metersPerDegLat = 111320.0;
            double metersPerDegLng = metersPerDegLat * Math.cos(Math.toRadians(originLat));
            for (double[] p : poly2d) {
                double x = p[0]; // local East-ish (AR X)
                double y = p[1]; // local North-ish (AR Z)
                // Rotate by device heading captured at first point
                double e = x * cosT - y * sinT; // meters East
                double n = x * sinT + y * cosT; // meters North
                double dLat = n / metersPerDegLat;
                double dLng = e / metersPerDegLng;
                latLngs.add(new LatLng(originLat + dLat, originLng + dLng));
            }
        } else {
            // Fallback demo: around (0,0) if no GPS origin available
            double scale = 1e-5;
            for (double[] p : poly2d) {
                latLngs.add(new LatLng(0 + p[1] * scale, 0 + p[0] * scale));
            }
        }

        // Create the visual polygon on the map
        PolygonOptions polyOpts = new PolygonOptions()
                .addAll(latLngs)
                .strokeWidth(4f)
                .strokeColor(0xFF00BCD4)  // Cyan border
                .fillColor(0x3300BCD4);   // Semi-transparent cyan fill
        polygon = googleMap.addPolygon(polyOpts);
        polygon.setClickable(true);

        // Create draggable markers for refine-on-map
        for (int i = 0; i < latLngs.size(); i++) {
            Marker m = googleMap.addMarker(new MarkerOptions()
                    .position(latLngs.get(i))
                    .draggable(true)
                    .zIndex(10f));
            if (m != null) {
                m.setDraggable(true);
                m.setTag(i);
                vertexMarkers.add(m);
            }
        }

        // Add a draggable centroid handle to move the entire polygon, sometimes bugs out and moves out of the polygon
        LatLng centroid = computeCentroid(latLngs);
        polygonHandleMarker = googleMap.addMarker(new MarkerOptions()
                .position(centroid)
                .draggable(true)
                .zIndex(20f)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                .title("Drag shape"));
        if (polygonHandleMarker != null) polygonHandleMarker.setTag("handle");

        // Add a rotation handle offset from centroid 
        LatLng rotPos = offsetByMeters(centroid, rotationHandleRadiusMeters, 0.0);
        rotationHandleMarker = googleMap.addMarker(new MarkerOptions()
                .position(rotPos)
                .draggable(true)
                .zIndex(20f)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .title("Rotate shape"));
        if (rotationHandleMarker != null) rotationHandleMarker.setTag("rotate");

        // Set up marker drag listeners for interactive polygon manipulation
        googleMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(Marker marker) {
                Object tag = marker.getTag();
                if ("handle".equals(tag)) {
                    lastHandleDragLatLng = marker.getPosition();
                } else if ("rotate".equals(tag)) {
                    // Capture starting angle and original points for rotation
                    LatLng center = computeCentroid(latLngs);
                    rotateStartAngleRad = angleRad(center, marker.getPosition());
                    rotateStartLatLngs = new java.util.ArrayList<>(latLngs);
                    rotationHandleRadiusMeters = distanceMeters(center, marker.getPosition());
                } else {
                    updateFromVertexMarker(marker);
                }
            }
            @Override public void onMarkerDrag(Marker marker) {
                Object tag = marker.getTag();
                if ("handle".equals(tag)) {
                    dragWholePolygon(marker);
                } else if ("rotate".equals(tag)) {
                    rotateWholePolygon(marker);
                } else {
                    updateFromVertexMarker(marker);
                }
            }
            @Override public void onMarkerDragEnd(Marker marker) {
                Object tag = marker.getTag();
                if ("handle".equals(tag)) {
                    dragWholePolygon(marker);
                    // Reposition rotation handle relative to new centroid
                    updateRotationHandlePosition();
                } else if ("rotate".equals(tag)) {
                    // Snap rotation handle back to fixed radius around centroid at current angle
                    updateRotationHandlePosition();
                } else {
                    updateFromVertexMarker(marker);
                }
            }
            
            /**
             * Updates polygon when individual vertex markers are dragged
             */
            private void updateFromVertexMarker(Marker marker) {
                Object tag = marker.getTag();
                if (!(tag instanceof Integer)) return;
                int idx = (Integer) tag;
                if (idx < 0 || idx >= latLngs.size()) return;
                latLngs.set(idx, marker.getPosition());
                if (polygon != null) polygon.setPoints(latLngs);
                updateHandlePosition();
                updateAreaAndAcresUI();
            }
            
            /**
             * Moves the entire polygon when the orange center handle is dragged
             */
            private void dragWholePolygon(Marker marker) {
                if (lastHandleDragLatLng == null) {
                    lastHandleDragLatLng = marker.getPosition();
                    return;
                }
                LatLng cur = marker.getPosition();
                double dLat = cur.latitude - lastHandleDragLatLng.latitude;
                double dLng = cur.longitude - lastHandleDragLatLng.longitude;
                // Shift all vertices
                for (int i = 0; i < latLngs.size(); i++) {
                    LatLng p = latLngs.get(i);
                    LatLng np = new LatLng(p.latitude + dLat, p.longitude + dLng);
                    latLngs.set(i, np);
                    // Move corresponding marker
                    if (i < vertexMarkers.size()) {
                        Marker vm = vertexMarkers.get(i);
                        if (vm != null) vm.setPosition(np);
                    }
                }
                if (polygon != null) polygon.setPoints(latLngs);
                lastHandleDragLatLng = cur;
                updateAreaAndAcresUI();
            }
            
            /**
             * Rotates the entire polygon when the blue rotation handle is dragged
             */
            private void rotateWholePolygon(Marker marker) {
                if (rotateStartLatLngs == null || rotateStartLatLngs.size() != latLngs.size()) return;
                LatLng center = computeCentroid(rotateStartLatLngs);
                double curAngle = angleRad(center, marker.getPosition());
                double delta = curAngle - rotateStartAngleRad;
                // Rotate each vertex around center using local meters (ENU)
                for (int i = 0; i < rotateStartLatLngs.size(); i++) {
                    LatLng p0 = rotateStartLatLngs.get(i);
                    double[] en = toLocalMeters(center, p0);
                    double e = en[0], n = en[1];
                    double er = e * Math.cos(delta) - n * Math.sin(delta);
                    double nr = e * Math.sin(delta) + n * Math.cos(delta);
                    LatLng p1 = fromLocalMeters(center, er, nr);
                    latLngs.set(i, p1);
                    if (i < vertexMarkers.size()) {
                        Marker vm = vertexMarkers.get(i);
                        if (vm != null) vm.setPosition(p1);
                    }
                }
                if (polygon != null) polygon.setPoints(latLngs);
                updateAreaAndAcresUI();
            }
        });

        // Disable adding new points on tap; allow moving existing markers only
        googleMap.setOnMapClickListener(latLng -> {});

        googleMap.setOnMarkerClickListener(marker -> true); // consume click to avoid info window interfering

        // Focus camera on polygon
        LatLng focus = latLngs.get(0);
        CameraPosition pos = new CameraPosition.Builder()
                .target(focus)
                .zoom(20f)
                .build();
        googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));

        // Update metrics using map geometry
        updateAreaAndAcresUI();
    }

    /**
     * Updates the area and stremma display based on current polygon geometry
     * Recalculates metrics whenever polygon is modified
     */
    private void updateAreaAndAcresUI() {
        if (latLngs == null || latLngs.size() < 3) return;
        double areaSqMeters = computeAreaSqMetersFromLatLngs(latLngs);
        double stremma = areaSqMeters / 1000.0;
        TextView tvAreaFinal = findViewById(R.id.tvAreaFinal);
        TextView tvAcresFinal = findViewById(R.id.tvPerimeterFinal);
        if (tvAreaFinal != null) tvAreaFinal.setText(String.format(java.util.Locale.US, "Area: %.2f m²", areaSqMeters));
        if (tvAcresFinal != null) tvAcresFinal.setText(String.format(java.util.Locale.US, "Stremma: %.2f στρ", stremma));
    }

    /**
     * Calculates area from lat/lng coordinates using Shoelace formula
     * Converts to local meters at the origin latitude for accurate calculation
     */
    private double computeAreaSqMetersFromLatLngs(java.util.List<LatLng> pts) {
        if (pts == null || pts.size() < 3 || originLat == null || originLng == null) return 0.0;
        double metersPerDegLat = 111320.0;
        double metersPerDegLng = metersPerDegLat * Math.cos(Math.toRadians(originLat));
        double sum = 0.0;
        for (int i = 0; i < pts.size(); i++) {
            LatLng a = pts.get(i);
            LatLng b = pts.get((i + 1) % pts.size());
            double ax = (a.longitude - originLng) * metersPerDegLng;
            double ay = (a.latitude - originLat) * metersPerDegLat;
            double bx = (b.longitude - originLng) * metersPerDegLng;
            double by = (b.latitude - originLat) * metersPerDegLat;
            sum += ax * by - bx * ay;
        }
        return Math.abs(sum) * 0.5;
    }

    /**
     * Calculates the centroid (center point) of the polygon
     * Used for positioning handles and rotation center
     */
    private LatLng computeCentroid(java.util.List<LatLng> pts) {
        double cx = 0, cy = 0;
        int n = pts.size();
        for (LatLng p : pts) { cx += p.latitude; cy += p.longitude; }
        return new LatLng(cx / n, cy / n);
    }

    /**
     * Updates the position of the orange center handle to match polygon centroi
     * Also updates rotation handle position
     */
    private void updateHandlePosition() {
        if (polygonHandleMarker == null || latLngs == null || latLngs.isEmpty()) return;
        polygonHandleMarker.setPosition(computeCentroid(latLngs));
        updateRotationHandlePosition();
    }

    /**
     * Updates the rotation handle position to maintain fixed radius from centroid
     */
    private void updateRotationHandlePosition() {
        if (rotationHandleMarker == null || latLngs == null || latLngs.isEmpty()) return;
        LatLng c = computeCentroid(latLngs);
        LatLng pos = offsetByMeters(c, rotationHandleRadiusMeters, 0.0);
        try { rotationHandleMarker.setPosition(pos); } catch (Exception ignored) {}
    }

    /**
     * Flips the polygon across either East/West or North/South axis
     */
    private void flipPolygon(boolean eastWest) {
        if (latLngs == null || latLngs.isEmpty()) return;
        LatLng c = computeCentroid(latLngs);
        for (int i = 0; i < latLngs.size(); i++) {
            LatLng p = latLngs.get(i);
            double[] en = toLocalMeters(c, p);
            double e = en[0], n = en[1];
            if (eastWest) e = -e; else n = -n; // mirror across N/S or E/W axis
            LatLng np = fromLocalMeters(c, e, n);
            latLngs.set(i, np);
            if (i < vertexMarkers.size()) {
                Marker vm = vertexMarkers.get(i);
                if (vm != null) vm.setPosition(np);
            }
        }
        if (polygon != null) polygon.setPoints(latLngs);
        updateHandlePosition();
        updateAreaAndAcresUI();
    }

    /**
     * Exports polygon as KML and opens in Google Earth
     * Falls back to other apps if Google Earth not available
     */
    private void exportKmlAndOpen() {
        try {
            if (latLngs == null || latLngs.size() < 3) return;
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
            sb.append("  <Document>\n");
            sb.append("    <name>FieldViewer Polygon</name>\n");
            sb.append("    <Placemark>\n");
            sb.append("      <name>Measured Area</name>\n");
            sb.append("      <Style><LineStyle><color>ff00bcd4</color><width>4</width></LineStyle><PolyStyle><color>3300bcd4</color></PolyStyle></Style>\n");
            sb.append("      <Polygon><outerBoundaryIs><LinearRing><coordinates>\n");
            for (int i = 0; i < latLngs.size(); i++) {
                LatLng p = latLngs.get(i);
                sb.append(p.longitude).append(',').append(p.latitude).append(',').append("0\n");
            }

            LatLng first = latLngs.get(0);
            sb.append(first.longitude).append(',').append(first.latitude).append(',').append("0\n");
            sb.append("      </coordinates></LinearRing></outerBoundaryIs></Polygon>\n");
            sb.append("    </Placemark>\n");
            sb.append("  </Document>\n");
            sb.append("</kml>\n");

            java.io.File outDir = new java.io.File(getCacheDir(), "exports");
            if (!outDir.exists()) outDir.mkdirs();
            java.io.File kml = new java.io.File(outDir, "fieldviewer_polygon.kml");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(kml)) {
                fos.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            androidx.core.content.FileProvider fp = null;
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    kml
            );

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.google-earth.kml+xml");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(intent, "Open polygon in"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Unable to open in Earth", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Exports KML with custom filename and opens in external app
     */
    private void exportKmlAndOpen(String desiredFileName) {
        try {
            android.net.Uri uri = exportKmlAndGetUri(desiredFileName);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.google-earth.kml+xml");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(intent, "Open polygon in"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Unable to open KML", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Gets URI for KML export without opening
     */
    private android.net.Uri exportKmlAndGetUri() throws Exception {
        return exportKmlAndGetUri(null);
    }

    /**
     * Sanitizes filename for safe file system usage
     * Removes invalid characters and ensures valid filename
     */
    private String sanitizeFileName(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;
        // Replace invalid filename characters
        return trimmed.replaceAll("[^a-zA-Z0-9-_]+", "_");
    }

    /**
     * Saves measurement data as JSON file
     * Includes coordinates, metrics, timestamps, and metadata
     */
    private void saveAsJson(String desiredFileName) {
        try {
            if (latLngs == null || latLngs.size() < 3) {
                android.widget.Toast.makeText(this, "Nothing to save", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            String safe = sanitizeFileName(desiredFileName);
            String fileName = (safe != null ? safe : "fieldviewer_measurement") + ".json";

            long nowMs = System.currentTimeMillis();
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("name", safe != null ? safe : "FieldViewer Measurement");
            root.put("timestamp", nowMs);
            // Add readable timestamp variants for clarity
            String iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                    .format(new java.util.Date(nowMs));
            String localPretty = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(nowMs));
            root.put("timestamp_iso", iso);
            root.put("timestamp_local", localPretty);
            root.put("area_m2", computeAreaSqMetersFromLatLngs(latLngs));
            root.put("stremma", computeAreaSqMetersFromLatLngs(latLngs) / 1000.0);
            if (originLat != null && originLng != null) {
                root.put("originLat", originLat);
                root.put("originLng", originLng);
            }
            if (headingRad != null && !headingRad.isNaN()) {
                root.put("headingRad", headingRad);
            }

            org.json.JSONArray coords = new org.json.JSONArray();
            for (com.google.android.gms.maps.model.LatLng p : latLngs) {
                org.json.JSONArray pair = new org.json.JSONArray();
                pair.put(p.longitude);
                pair.put(p.latitude);
                coords.put(pair);
            }
            root.put("coordinates_lonlat", coords);

            java.io.File outDir = new java.io.File(getExternalFilesDir(null), "measurements");
            if (!outDir.exists()) outDir.mkdirs();
            java.io.File jsonFile = new java.io.File(outDir, fileName);
            try (java.io.FileWriter fw = new java.io.FileWriter(jsonFile)) {
                fw.write(root.toString(2));
            }

            android.widget.Toast.makeText(this, "Saved: " + jsonFile.getAbsolutePath(), android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Save failed", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Exports KML with custom filename and returns URI
     */
    private android.net.Uri exportKmlAndGetUri(String desiredFileName) throws Exception {
        if (latLngs == null || latLngs.size() < 3) throw new Exception("No polygon");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
        sb.append("  <Document>\n");
        String safe = sanitizeFileName(desiredFileName);
        String title = (safe != null ? safe : "FieldViewer Polygon");
        sb.append("    <name>").append(title).append("</name>\n");
        sb.append("    <Placemark>\n");
        sb.append("      <name>").append(title).append("</name>\n");
        sb.append("      <Style><LineStyle><color>ff00bcd4</color><width>4</width></LineStyle><PolyStyle><color>3300bcd4</color></PolyStyle></Style>\n");
        sb.append("      <Polygon><outerBoundaryIs><LinearRing><coordinates>\n");
        for (int i = 0; i < latLngs.size(); i++) {
            com.google.android.gms.maps.model.LatLng p = latLngs.get(i);
            sb.append(p.longitude).append(',').append(p.latitude).append(',').append("0\n");
        }
        com.google.android.gms.maps.model.LatLng first = latLngs.get(0);
        sb.append(first.longitude).append(',').append(first.latitude).append(',').append("0\n");
        sb.append("      </coordinates></LinearRing></outerBoundaryIs></Polygon>\n");
        sb.append("    </Placemark>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>\n");

        java.io.File outDir = new java.io.File(getCacheDir(), "exports");
        if (!outDir.exists()) outDir.mkdirs();
        String fileName = (safe != null ? safe : "fieldviewer_polygon") + ".kml";
        java.io.File kml = new java.io.File(outDir, fileName);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(kml)) {
            fos.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                kml
        );
    }

    /**
     * Opens polygon in Google Earth with planned fallback options
     * Tries Google Earth first, then Maps, then generic KML viewer
     */
    private void openInGoogleEarthOrFallback() {
        String desiredName = null;
        try {
            try {
                android.widget.EditText et = findViewById(R.id.etFileName);
                if (et != null) desiredName = et.getText().toString();
            } catch (Exception ignored) {}
            android.net.Uri kmlUri = exportKmlAndGetUri(desiredName);
            android.content.Intent earth = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            earth.setDataAndType(kmlUri, "application/vnd.google-earth.kml+xml");
            earth.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            earth.setPackage("com.google.earth");
            startActivity(earth);
        } catch (android.content.ActivityNotFoundException e) {
            try {
                startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=com.google.earth"))
                );
            } catch (Exception ignored) {
                if (!openInGoogleMapsPolyline()) {
                    exportKmlAndOpen(desiredName);
                }
            }
        } catch (Exception e) {
            if (!openInGoogleMapsPolyline()) {
                exportKmlAndOpen(desiredName);
            }
        }
    }

    /**
     * Shares KML file via Android share intent
     */
    private void shareKml(String desiredFileName) {
        try {
            android.net.Uri kmlUri = exportKmlAndGetUri(desiredFileName);
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("application/vnd.google-earth.kml+xml");
            send.putExtra(android.content.Intent.EXTRA_STREAM, kmlUri);
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(send, "Share measurement"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Share failed", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens polygon in Google Maps as a polyline
     * Alternative to KML export for quick viewing
     */
    private boolean openInGoogleMapsPolyline() {
        try {
            if (latLngs == null || latLngs.size() < 2) return false;
            // Ensure closed ring for visual polygon path
            java.util.ArrayList<LatLng> path = new java.util.ArrayList<>(latLngs);
            if (!latLngs.get(0).equals(latLngs.get(latLngs.size() - 1))) {
                path.add(latLngs.get(0));
            }
            String encoded = encodePolyline(path);
            String encodedEscaped = java.net.URLEncoder.encode(encoded, "UTF-8");
            LatLng c = computeCentroid(latLngs);
            String url = "https://www.google.com/maps/dir/?api=1" +
                    "&map_action=map" +
                    "&center=" + c.latitude + "," + c.longitude +
                    "&zoom=18" +
                    "&path=weight:5%7Ccolor:0x00BCD4FF%7Cenc:" + encodedEscaped;

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.setPackage("com.google.android.apps.maps"); // force Google Maps
            startActivity(intent);
            return true;
        } catch (Exception e) {
            try {
                // Fallback: open without forcing package
                LatLng c = computeCentroid(latLngs);
                java.util.ArrayList<LatLng> path = new java.util.ArrayList<>(latLngs);
                if (!latLngs.get(0).equals(latLngs.get(latLngs.size() - 1))) path.add(latLngs.get(0));
                String encoded = encodePolyline(path);
                String encodedEscaped = java.net.URLEncoder.encode(encoded, "UTF-8");
                String url = "https://www.google.com/maps/dir/?api=1&map_action=map&center=" + c.latitude + "," + c.longitude +
                        "&zoom=18&path=weight:5%7Ccolor:0x00BCD4FF%7Cenc:" + encodedEscaped;
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                startActivity(intent);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    /**
     * Encodes polygon coordinates for Google Maps URL
     * Uses Google's polyline encoding algorithm
     */
    private String encodePolyline(java.util.List<LatLng> points) {
        long lastLat = 0;
        long lastLng = 0;
        StringBuilder result = new StringBuilder();
        for (LatLng point : points) {
            long lat = Math.round(point.latitude * 1e5);
            long lng = Math.round(point.longitude * 1e5);
            long dLat = lat - lastLat;
            long dLng = lng - lastLng;
            encodeSignedNumber(dLat, result);
            encodeSignedNumber(dLng, result);
            lastLat = lat;
            lastLng = lng;
        }
        return result.toString();
    }

    /**
     * Encodes signed numbers for polyline encoding
     */
    private void encodeSignedNumber(long num, StringBuilder result) {
        long sgn_num = num << 1;
        if (num < 0) {
            sgn_num = ~sgn_num;
        }
        encodeUnsignedNumber(sgn_num, result);
    }

    /**
     * Encodes unsigned numbers for polyline encoding
     */
    private void encodeUnsignedNumber(long num, StringBuilder result) {
        while (num >= 0x20) {
            long nextValue = (0x20 | (num & 0x1f)) + 63;
            result.append((char) (nextValue));
            num >>= 5;
        }
        num += 63;
        result.append((char) (num));
    }
    
    /**
     * Converts lat/lng to local East/North meters relative to origin
     * Used for precise geometric calculations
     */
    private double[] toLocalMeters(LatLng origin, LatLng p) {
        double metersPerDegLat = 111320.0;
        double metersPerDegLng = metersPerDegLat * Math.cos(Math.toRadians(origin.latitude));
        double e = (p.longitude - origin.longitude) * metersPerDegLng;
        double n = (p.latitude - origin.latitude) * metersPerDegLat;
        return new double[]{e, n};
    }

    /**
     * Converts local East/North meters back to lat/lng
     * Inverse of toLocalMeters
     */
    private LatLng fromLocalMeters(LatLng origin, double e, double n) {
        double metersPerDegLat = 111320.0;
        double metersPerDegLng = metersPerDegLat * Math.cos(Math.toRadians(origin.latitude));
        double dLat = n / metersPerDegLat;
        double dLng = e / metersPerDegLng;
        return new LatLng(origin.latitude + dLat, origin.longitude + dLng);
    }

    /**
     * Offsets a point by given East/North meters
     * Convenience method for positioning handles
     */
    private LatLng offsetByMeters(LatLng origin, double eastMeters, double northMeters) {
        return fromLocalMeters(origin, eastMeters, northMeters);
    }

    /**
     * Calculates angle from center to point in radians
     * Used for rotation calculations
     */
    private double angleRad(LatLng center, LatLng p) {
        double[] en = toLocalMeters(center, p);
        return Math.atan2(en[1], en[0]); // atan2(north, east)
    }

    /**
     * Calculates distance between two points in meters
     * Uses local meter conversion for accuracy
     */
    private double distanceMeters(LatLng a, LatLng b) {
        double[] en = toLocalMeters(a, b);
        return Math.hypot(en[0], en[1]);
    }
}
