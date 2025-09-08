package com.example.fieldviewer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.ArCoreApk.InstallStatus;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.sceneform.ux.ArFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Main AR measurement activity that handles:
 * - ARCore session management and plane detection
 * - User interaction for placing measurement points
 * - Real-time perimeter and area calculations
 * - GPS location capture for map georeferencing
 * - Sensor data for device orientation
 */
public class ARMeasureActivity extends AppCompatActivity {

    private static final String TAG = "ARMeasureActivity";
    private static final String CAMERA_PERMISSION = Manifest.permission.CAMERA;

    // UI Views - Overlay elements on top of AR camera view
    private TextView tvPoints, tvArea, tvPerimeter;
    private TextView crosshair;  // Center crosshair for precise point placement
    private Button btnAdd, btnUndo, btnFinish;

    // AR Components - Core AR functionality
    private com.google.ar.sceneform.ux.ArFragment arFragment;  // Main AR camera view
    private final java.util.List<com.google.ar.core.Anchor> anchors = new java.util.ArrayList<>();  // 3D anchors in world space
    private final java.util.List<com.google.ar.sceneform.Node> markers = new java.util.ArrayList<>();  // Visual markers for anchors

    // 2D helper point for local ground-plane math - simplifies area calculations
    private static class P2 { double x, y; P2(double x, double y){ this.x=x; this.y=y; } }

    // Data storage and state management
    private final List<Object> points = new ArrayList<>();
    private boolean arCoreReady = false;  // Flag to ensure ARCore is properly initialized
    private boolean userRequestedInstall = true; // Only true first time, prevents repeated install prompts
    // Removed tap debounce; allow rapid +Point adds

    // Location and orientation tracking for map georeferencing
    private FusedLocationProviderClient fusedLocationClient;  // High-accuracy GPS provider
    private Double originLat = null, originLng = null;  // GPS coordinates of first point
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private final float[] rotationMatrix = new float[9];  // Device orientation matrix
    private final float[] orientation = new float[3];  // Roll, pitch, yaw angles
    private float lastAzimuthRad = 0f; // Device heading (magnetic north) in radians
    private Float headingAtFirstAnchorRad = null; // Captured when first anchor placed for map alignment
    private boolean resultStarted = false;  // Prevents multiple result launches
    private boolean shouldResetAfterResult = false;  // Flag to reset state after returning from results
    private boolean autoAddFirstPoint = false; // Disabled per request; user starts manually after warmup
    private boolean attemptedAutoFirstAdd = false;
    private android.os.CountDownTimer warmupTimer;

    // Permission request launcher - handles camera permission flow
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "Camera permission granted");
                    proceedArCoreFlow();
                } else {
                    Log.d(TAG, "Camera permission denied");
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ar_measure);
        
        // Handle system UI insets for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize AR Fragment from layout XML
        arFragment = (com.google.ar.sceneform.ux.ArFragment) getSupportFragmentManager()
                .findFragmentById(R.id.ar_fragment_container);

        // Initialize location services for GPS tracking
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        // Warm up origin location early so Maps placement is ready even if user proceeds quickly
        tryCaptureOriginLocation();

        // Optionally auto-add the first point once a plane is detected and GPS is warmed
        if (arFragment != null && arFragment.getArSceneView() != null && arFragment.getArSceneView().getScene() != null) {
            arFragment.getArSceneView().getScene().addOnUpdateListener(frameTime -> {
                if (!autoAddFirstPoint || attemptedAutoFirstAdd) return;
                if (!arCoreReady || anchors.size() > 0) return;
                com.google.ar.core.Frame frame = arFragment.getArSceneView().getArFrame();
                if (frame == null) return;
                int w = arFragment.getArSceneView().getWidth();
                int h = arFragment.getArSceneView().getHeight();
                float cx = w / 2f, cy = h / 2f;
                java.util.List<com.google.ar.core.HitResult> hits = frame.hitTest(cx, cy);
                for (com.google.ar.core.HitResult hit : hits) {
                    com.google.ar.core.Trackable trackable = hit.getTrackable();
                    if (trackable instanceof com.google.ar.core.Plane) {
                        com.google.ar.core.Plane plane = (com.google.ar.core.Plane) trackable;
                        if (plane.getType() != com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
                        if (!plane.isPoseInPolygon(hit.getHitPose())) continue;
                        // We have a valid plane under the crosshair; try to capture GPS and add
                        attemptedAutoFirstAdd = true;
                        // Kick off a current location fetch (non-blocking)
                        if (originLat == null || originLng == null) {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                try {
                                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                            .addOnSuccessListener(location -> {
                                                if (location != null) { originLat = location.getLatitude(); originLng = location.getLongitude(); }
                                            });
                                } catch (Exception ignore) {}
                            }
                        }
                        addPoint();
                        break;
                    }
                }
            });
        }

        // Main screen handles GPS warmup; no AR warmup countdown here
        // Initialize sensors for device orientation tracking
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        // Initialize UI views and set up event listeners
        try {
            crosshair = findViewById(R.id.crosshair);
            tvPoints = findViewById(R.id.tvPoints);
            tvArea = findViewById(R.id.tvArea);
            tvPerimeter = findViewById(R.id.tvPerimeter);
            btnAdd = findViewById(R.id.btnAdd);
            btnUndo = findViewById(R.id.btnUndo);
            btnFinish = findViewById(R.id.btnFinish);

            // Wire up button click handlers
            if (btnAdd != null) btnAdd.setOnClickListener(v -> addPoint());
            if (btnUndo != null) btnUndo.setOnClickListener(v -> undoLastPoint());
            if (btnFinish != null) btnFinish.setOnClickListener(v -> finishMeasurement());

            updateUI();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views: " + e.getMessage());
            Toast.makeText(this, "Error initializing UI", Toast.LENGTH_LONG).show();
        }

        // Start permission and ARCore initialization flow
        checkCameraPermissionAndContinue();
    }

    /**
     * Attempts to capture the current GPS location as the origin point
     * This is used to georeference the AR measurements on the map
     */
    private void tryCaptureOriginLocation() {
        if (originLat != null && originLng != null) return;  // Already captured
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            originLat = location.getLatitude();
                            originLng = location.getLongitude();
                            Log.d(TAG, "Captured origin location: " + originLat + ", " + originLng);
                        }
                    });
        } else {
            Log.d(TAG, "Location permission not granted; skipping origin capture");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Let ArFragment handle its own lifecycle - nothing to do here
        if (sensorManager != null && rotationVectorSensor != null) {
            sensorManager.registerListener(sensorListener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI);
        }
        // Re-warm origin upon returning to AR
        tryCaptureOriginLocation();
        // GPS warmup handled on main screen; no AR countdown restart
        // Allow starting results again after returning from the result screen
        resultStarted = false;
        if (shouldResetAfterResult) {
            resetMeasurementState();
            shouldResetAfterResult = false;
        } else {
            // Refresh UI state (buttons enablement etc.)
            updateUIAndMetrics();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Let ArFragment handle its own lifecycle - nothing to do here
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorListener);
        }
    }

    /**
     * Sensor listener for device orientation tracking
     * Captures magnetic heading for map alignment
     */
    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
            try {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                // Remap so that the axes are aligned to the device natural orientation
                float[] remapped = new float[9];
                SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);
                SensorManager.getOrientation(remapped, orientation);
                // Azimuth is orientation[0] in radians, relative to magnetic north
                lastAzimuthRad = orientation[0];
            } catch (Exception ignore) {}
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            if (sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                    Toast.makeText(ARMeasureActivity.this, "Compass calibration needed. Move phone in a figure-8.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    /**
     * Checks camera permission and proceeds with ARCore initialization if granted
     */
    private void checkCameraPermissionAndContinue() {
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission already granted");
            proceedArCoreFlow();
        } else {
            Log.d(TAG, "Requesting camera permission");
            requestPermissionLauncher.launch(CAMERA_PERMISSION);
        }
    }

    /**
     * Handles ARCore availability check and installation flow
     * This is the standard ARCore initialization process
     */
    private void proceedArCoreFlow() {
        // 1) Check ARCore support/installation status
        Availability availability = ArCoreApk.getInstance().checkAvailability(this);
        Log.d(TAG, "ARCore availability: " + availability);

        if (availability == Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            Toast.makeText(this, "ARCore not supported", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // If installation/update is needed, request it in the onResume loop:
        if (availability == Availability.SUPPORTED_NOT_INSTALLED
                || availability == Availability.SUPPORTED_APK_TOO_OLD) {
            try {
                InstallStatus status = ArCoreApk.getInstance().requestInstall(this, userRequestedInstall);
                Log.d(TAG, "requestInstall status: " + status);
                // INSTALL_REQUESTED: wait for next onResume
                if (status == InstallStatus.INSTALL_REQUESTED) {
                    userRequestedInstall = false; // don't request again next time
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "ARCore install flow error", e);
                Toast.makeText(this, "ARCore install/update failed", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        // Reaching here: SUPPORTED_INSTALLED
        arCoreReady = true;
        Toast.makeText(this, "ARCore ready", Toast.LENGTH_SHORT).show();
        // ArFragment is inflated from XML (legacy <fragment> tag) and manages its own lifecycle
    }

    /**
     * Adds a new measurement point at the center crosshair location
     * Performs hit testing to find a horizontal plane and creates an AR anchor
     */
    private void addPoint() {
        // No cooldown/debounce on +Point; respond immediately
        if (arFragment == null || arFragment.getArSceneView() == null || arFragment.getArSceneView().getArFrame() == null) {
            android.widget.Toast.makeText(this, "AR not ready", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Get center screen coordinates for hit testing
        int w = arFragment.getArSceneView().getWidth();
        int h = arFragment.getArSceneView().getHeight();
        float cx = w / 2f, cy = h / 2f;

        // Perform hit test at center screen to find a plane
        com.google.ar.core.Frame frame = arFragment.getArSceneView().getArFrame();
        java.util.List<com.google.ar.core.HitResult> hits = frame.hitTest(cx, cy);
        for (com.google.ar.core.HitResult hit : hits) {
            com.google.ar.core.Trackable trackable = hit.getTrackable();
            if (trackable instanceof com.google.ar.core.Plane) {
                com.google.ar.core.Plane plane = (com.google.ar.core.Plane) trackable;
                // Only accept horizontal, upward-facing planes
                if (plane.getType() != com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING) continue;
                if (!plane.isPoseInPolygon(hit.getHitPose())) continue;

                // On first point, try to capture current GPS fix to anchor the map origin
                if (anchors.isEmpty()) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                    .addOnSuccessListener(location -> {
                                        if (location != null) {
                                            originLat = location.getLatitude();
                                            originLng = location.getLongitude();
                                        }
                                    });
                        } catch (Exception ignore) {}
                    } else {
                        tryCaptureOriginLocation();
                    }
                }

                // Create AR anchor at hit point
                com.google.ar.core.Anchor anchor = hit.createAnchor();
                anchors.add(anchor);

                // Capture origin GPS on first point
                if (anchors.size() == 1) {
                    // Capture heading at first point
                    headingAtFirstAnchorRad = lastAzimuthRad;
                    if (originLat == null || originLng == null) {
                        tryCaptureOriginLocation();
                    }
                }

                // Create visual marker (cyan sphere) at anchor location
                com.google.ar.sceneform.AnchorNode anchorNode =
                        new com.google.ar.sceneform.AnchorNode(anchor);
                anchorNode.setParent(arFragment.getArSceneView().getScene());

                com.google.ar.sceneform.rendering.MaterialFactory
                        .makeOpaqueWithColor(this,
                                new com.google.ar.sceneform.rendering.Color(android.graphics.Color.CYAN))
                        .thenAccept(material -> {
                            com.google.ar.sceneform.rendering.ModelRenderable sphere =
                                    com.google.ar.sceneform.rendering.ShapeFactory.makeSphere(
                                            0.02f, new com.google.ar.sceneform.math.Vector3(0f, 0f, 0f), material);
                            com.google.ar.sceneform.Node node = new com.google.ar.sceneform.Node();
                            node.setRenderable(sphere);
                            node.setParent(anchorNode);
                            markers.add(node);
                        });

                updateUIAndMetrics();
                return;
            }
        }
        android.widget.Toast.makeText(this, "Point not added—scan a flat surface", android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Removes the last placed point and its visual marker
     */
    private void undoLastPoint() {
        if (anchors.isEmpty()) return;
        int last = anchors.size() - 1;

        // Remove and detach the last anchor
        com.google.ar.core.Anchor a = anchors.remove(last);
        a.detach();

        // Remove the corresponding visual marker
        if (last < markers.size()) {
            com.google.ar.sceneform.Node n = markers.remove(last);
            if (n.getParent() != null) n.getParent().removeChild(n);
        }
        updateUIAndMetrics(); // will be expanded next
    }

    /**
     * Finalizes the measurement and launches the results screen
     * Validates minimum points and captures final GPS location if needed
     */
    private void finishMeasurement() {
        if (btnFinish != null) btnFinish.setEnabled(false);
        if (anchors.size() < 3) {
            android.widget.Toast.makeText(this, "Need at least 3 points", android.widget.Toast.LENGTH_SHORT).show();
            if (btnFinish != null) btnFinish.setEnabled(true);
            return;
        }
        
        // Convert 3D anchors to 2D coordinates for area calculation
        java.util.ArrayList<double[]> coords = new java.util.ArrayList<>();
        java.util.List<P2> poly = projectAnchorsTo2D();
        for (P2 p : poly) coords.add(new double[]{p.x, p.y});

        // Calculate perimeter and area
        double perim = perimeterMeters(poly);
        double area = areaShoelaceSqMeters(poly);

        if (originLat == null || originLng == null) {
            // Try to obtain a current high-accuracy location before proceeding
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                final com.google.android.gms.tasks.CancellationTokenSource cts = new com.google.android.gms.tasks.CancellationTokenSource();
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                        .addOnSuccessListener(location -> {
                            if (location != null) {
                                originLat = location.getLatitude();
                                originLng = location.getLongitude();
                            }
                            startResult(coords, perim, area);
                        })
                        .addOnFailureListener(e -> startResult(coords, perim, area));
                // Timeout after ~2.5s to avoid blocking the user if GPS is slow
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (!resultStarted) {
                        try { cts.cancel(); } catch (Exception ignore) {}
                        startResult(coords, perim, area);
                    }
                }, 2500);
                return;
            }
        }
        startResult(coords, perim, area);
    }

    /**
     * Launches the results activity with measurement data
     * Packages all coordinates, metrics, and location data for the results screen
     */
    private void startResult(java.util.ArrayList<double[]> coords, double perim, double area) {
        if (resultStarted) return;
        resultStarted = true;
        shouldResetAfterResult = true;
        android.content.Intent i = new android.content.Intent(this, ResultActivity.class);
        i.putExtra("poly2d", coords);
        i.putExtra("perimeter", perim);
        i.putExtra("area", area);
        // Stremma: 1000 m² per stremma (Greek land measurement unit)
        i.putExtra("stremma", area / 1000.0);
        if (headingAtFirstAnchorRad != null) {
            i.putExtra("headingRad", headingAtFirstAnchorRad.doubleValue());
        } else {
            i.putExtra("headingRad", (double) lastAzimuthRad);
        }
        if (originLat != null && originLng != null) {
            i.putExtra("originLat", originLat);
            i.putExtra("originLng", originLng);
        }
        startActivity(i);
    }

    /**
     * Resets all measurement state when returning from results screen
     * Clears anchors, markers, and resets UI to initial state
     */
    private void resetMeasurementState() {
        try {
            // Detach and clear all AR anchors
            for (com.google.ar.core.Anchor a : anchors) {
                a.detach();
            }
            anchors.clear();
            
            // Remove all visual markers
            for (com.google.ar.sceneform.Node n : markers) {
                if (n.getParent() != null) n.getParent().removeChild(n);
            }
            markers.clear();
            
            // Reset orientation tracking
            headingAtFirstAnchorRad = null;
            lastAzimuthRad = 0f;
            
            // Reset UI labels to initial state
            if (tvPoints != null) tvPoints.setText("Points: 0");
            if (tvArea != null) tvArea.setText("Area: 0 m²");
            if (tvPerimeter != null) tvPerimeter.setText("Perimeter: 0 m");
            if (btnFinish != null) btnFinish.setEnabled(false);
            if (btnUndo != null) btnUndo.setEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "Error resetting state", e);
        }
    }

    /**
     * Updates basic UI state (point count, button enablement)
     * Called during initialization and state changes
     */
    private void updateUI() {
        try {
            if (tvPoints != null) {
                tvPoints.setText("Points: " + points.size());
            }
            if (btnFinish != null) {
                btnFinish.setEnabled(points.size() >= 3);
            }
            if (btnUndo != null) {
                btnUndo.setEnabled(!points.isEmpty());
            }
            // tvArea, tvPerimeter will be updated when geometry is implemented
        } catch (Exception e) {
            Log.e(TAG, "Error updating UI: " + e.getMessage());
        }
    }

    /**
     * Projects 3D AR anchors to a local 2D ground plane
     * Uses the first anchor as origin (0,0) for simplified area calculations
     * This converts world coordinates to local meters for geometry calculations
     */
    private java.util.List<P2> projectAnchorsTo2D() {
        java.util.List<P2> pts = new java.util.ArrayList<>();
        if (anchors.isEmpty()) return pts;
        
        // Use first anchor as origin point
        com.google.ar.core.Pose origin = anchors.get(0).getPose();
        float ox = origin.tx(), oz = origin.tz();
        
        // Project each anchor relative to origin
        for (com.google.ar.core.Anchor a : anchors) {
            com.google.ar.core.Pose p = a.getPose();
            pts.add(new P2(p.tx() - ox, p.tz() - oz)); // local ground-plane coords (x,z)
        }
        return pts;
    }

    /**
     * Calculates the perimeter of the polygon in meters
     * Uses Euclidean distance between consecutive vertices
     */
    private static double perimeterMeters(java.util.List<P2> pts){
        if (pts.size() < 2) return 0;
        double sum = 0;
        for (int i = 0; i < pts.size(); i++){
            P2 a = pts.get(i), b = pts.get((i + 1) % pts.size());
            sum += Math.hypot(a.x - b.x, a.y - b.y);
        }
        return sum;
    }

    /**
     * Calculates the area of the polygon using the Shoelace formula
     * Returns area in square meters
     * Shoelace formula: A = 1/2 * |Σ(x_i * y_{i+1} - x_{i+1} * y_i)|
     */
    private static double areaShoelaceSqMeters(java.util.List<P2> pts){
        if (pts.size() < 3) return 0;
        double s = 0;
        for (int i = 0; i < pts.size(); i++){
            P2 a = pts.get(i), b = pts.get((i + 1) % pts.size());
            s += a.x * b.y - b.x * a.y;
        }
        return Math.abs(s) * 0.5;
    }

    /**
     * Updates UI with current measurement metrics
     * Displays point count, perimeter, and area in real-time
     */
    private void updateUIAndMetrics() {
        int count = anchors.size();
        if (tvPoints != null) tvPoints.setText("Points: " + count);
        if (btnFinish != null) btnFinish.setEnabled(count >= 3);
        if (btnUndo != null) btnUndo.setEnabled(count > 0);

        // Calculate and display current metrics
        java.util.List<P2> poly = projectAnchorsTo2D();
        double perim = perimeterMeters(poly);
        double area = areaShoelaceSqMeters(poly);

        if (tvPerimeter != null) tvPerimeter.setText(String.format(java.util.Locale.US, "Perimeter: %.2f m", perim));
        if (tvArea != null) tvArea.setText(String.format(java.util.Locale.US, "Area: %.2f m²", area));

        // Note: For ARCore point noise, alternatives include triangulation or convex-hull + triangulation.
        // Shoelace is kept here for simplicity and good performance on ordered polygon taps.
    }
}



