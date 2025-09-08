package com.example.fieldviewer;

import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Displays the full JSON contents of a saved measurement file.
 * Also extracts a friendly name and timestamp for a small header above the JSON.
 */
public class SavedMeasurementDetailActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_saved_measurement_detail);
        // Handle system UI insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        TextView tv = findViewById(R.id.tvJson);
        String path = getIntent().getStringExtra("path");
        if (path == null) {
            tv.setText("No file path provided");
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            tv.setText("File not found: " + path);
            return;
        }

        // Read entire JSON file into a string
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) {
            tv.setText("Error reading file");
            return;
        }
        String content = sb.toString();

        // Build a small header using name and a readable timestamp from the JSON if available
        String header = "";
        try {
            org.json.JSONObject obj = new org.json.JSONObject(content);
            String name = obj.optString("name", f.getName());
            String pretty = obj.optString("timestamp_local", "");
            if (pretty.isEmpty()) {
                long ts = obj.optLong("timestamp", f.lastModified());
                pretty = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date(ts));
            }
            header = name + "\n" + pretty + "\n\n";
        } catch (Exception ignore) {}

        // Show header followed by raw JSON contents for transparency/debugging
        tv.setText(header + content);
    }
}


