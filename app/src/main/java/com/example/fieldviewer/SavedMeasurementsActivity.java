package com.example.fieldviewer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Displays a list of saved measurement files (JSON) and opens detail view on tap.
 * Files are loaded from app external files directory under "measurements".
 */
public class SavedMeasurementsActivity extends AppCompatActivity {

    // Backing list of JSON files shown in the ListView
    private List<File> jsonFiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_saved_measurements);
        // Handle system UI insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Find ListView that will display saved files
        ListView listView = findViewById(R.id.lvFiles);
        if (listView == null) {
            Toast.makeText(this, "Layout error: lvFiles not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Locate the measurements directory and collect *.json files
        File dir = new File(getExternalFilesDir(null), "measurements");
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
            if (files != null) {
                // Sort descending by last modified (most recent first)
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                jsonFiles = Arrays.asList(files);
            }
        }

        // Build display names with human-readable timestamps
        List<String> names = new ArrayList<>();
        for (File f : jsonFiles) {
            String display = f.getName();
            try {
                java.util.Date d = new java.util.Date(f.lastModified());
                String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(d);
                display = display + "  (" + when + ")";
            } catch (Exception ignore) {}
            names.add(display);
        }
        // Simple adapter using a built-in one-line layout
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);

        // Open detail screen when a file is tapped
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= jsonFiles.size()) return;
                File f = jsonFiles.get(position);
                Intent i = new Intent(SavedMeasurementsActivity.this, SavedMeasurementDetailActivity.class);
                i.putExtra("path", f.getAbsolutePath());
                startActivity(i);
            }
        });
    }
}


