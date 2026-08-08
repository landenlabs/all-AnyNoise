// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.history;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.color.MaterialColors;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.landenlabs.allAnyNoise.Prefs;
import com.landenlabs.allAnyNoise.R;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plots each noise event as a lone vertical segment — x = event time,
 * y = event duration — so gaps and clusters in activity are visible at a
 * glance. Each event is its own LineDataSet (rather than one connected
 * line) purely so it can later carry its own color/width/dash style once
 * sounds can be classified. Locked to landscape (see the manifest entry)
 * since a time axis needs the width regardless of how the phone is held.
 *
 * The light/vibration sections below plot the same way (x = time, y = the
 * raw sensor reading that tripped the transition) but are read straight from
 * Firestore rather than the Sheet CSV - that data never needed to round-trip
 * through the Sheet/Apps Script pipeline, so this avoids depending on it.
 */
public class HistoryGraphActivity extends AppCompatActivity {

    private static final String TAG = "HistoryGraphActivity";

    // Keeps a double-tap or pinch from zooming in past a window this narrow.
    private static final float MIN_VISIBLE_SECONDS = 30f;

    // Most-recent noiseEvents docs fetched to feed the light/vibration charts;
    // filtered client-side by soundType to avoid needing a composite Firestore index.
    private static final int SENSOR_EVENT_FETCH_LIMIT = 1000;

    // On the phone layout (layout/, inside a ScrollView) each graph is sized
    // to this fraction of screen height so it's actually usable - three
    // stacked exceeds one screen, hence the scrolling. The tablet layout
    // (layout-sw600dp/) has no #scroll_container and instead splits the
    // screen into thirds via layout_weight, so this fraction never applies there.
    private static final float SECTION_HEIGHT_FRACTION = 0.8f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LineChart chart;
    private LineChart chartLight;
    private LineChart chartVibration;
    private TextView tvError;
    private TextView tvLightError;
    private TextView tvVibrationError;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_graph);

        chart = findViewById(R.id.chart_history);
        chartLight = findViewById(R.id.chart_light);
        chartVibration = findViewById(R.id.chart_vibration);
        tvError = findViewById(R.id.tv_graph_error);
        tvLightError = findViewById(R.id.tv_light_graph_error);
        tvVibrationError = findViewById(R.id.tv_vibration_graph_error);

        sizeSectionsForPhone();

        setupChart(chart);
        setupChart(chartLight);
        setupChart(chartVibration);

        loadGraphData();
        loadSensorGraphs();
    }

    /** No-op on the tablet layout, which has no #scroll_container and splits the screen into thirds instead. */
    private void sizeSectionsForPhone() {
        if (findViewById(R.id.scroll_container) == null) {
            return;
        }
        int sectionHeightPx = (int) (getResources().getDisplayMetrics().heightPixels * SECTION_HEIGHT_FRACTION);
        setSectionHeight(R.id.section_noise, sectionHeightPx);
        setSectionHeight(R.id.section_light, sectionHeightPx);
        setSectionHeight(R.id.section_vibration, sectionHeightPx);
    }

    private void setSectionHeight(int sectionId, int heightPx) {
        View section = findViewById(sectionId);
        ViewGroup.LayoutParams params = section.getLayoutParams();
        params.height = heightPx;
        section.setLayoutParams(params);
    }

    private void setupChart(LineChart chart) {
        int axisTextColor = resolveAxisTextColor();

        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getAxisLeft().setEnabled(true);
        chart.getAxisLeft().setDrawLabels(true);
        chart.getAxisLeft().setDrawGridLines(true);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setTextSize(15f);
        chart.getAxisLeft().setTextColor(axisTextColor);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        });

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawLabels(true);
        xAxis.setDrawGridLines(true);
        xAxis.setTextColor(axisTextColor);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return HistoryFragment.formatHistoryTimestamp(new Date((long) (value * 1000L)));
            }
        });

        // Only the time (x) axis scrolls/zooms; the value (y) axis stays fixed.
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleXEnabled(true);
        chart.setScaleYEnabled(false);
        chart.setPinchZoom(false);
        chart.setDoubleTapToZoomEnabled(true);
        chart.setVisibleXRangeMinimum(MIN_VISIBLE_SECONDS);
    }

    /** Resolves the theme's current text color so chart labels follow dark/light mode like every other TextView. */
    private int resolveAxisTextColor() {
        return MaterialColors.getColor(this, android.R.attr.textColorPrimary, Color.BLACK);
    }

    private void loadGraphData() {
        executor.execute(() -> {
            try {
                List<String[]> rows = HistoryFragment.fetchSheetRows();
                showGraph(rows);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load Sheet1 CSV", e);
                showError(e);
            }
        });
    }

    private void showGraph(List<String[]> rows) {
        List<ILineDataSet> dataSets = new ArrayList<>();
        float maxX = Float.MIN_VALUE;
        for (String[] row : rows) {
            if (row.length < 3) {
                continue;
            }
            Date date = HistoryFragment.parseIsoTimestamp(row[0]);
            if (date == null) {
                continue;
            }
            float durationSec;
            try {
                durationSec = Float.parseFloat(row[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            float timeSec = date.getTime() / 1000f;
            maxX = Math.max(maxX, timeSec);

            List<Entry> entries = new ArrayList<>(2);
            entries.add(new Entry(timeSec, 0f));
            entries.add(new Entry(timeSec, durationSec));
            LineDataSet dataSet = new LineDataSet(entries, null);
            dataSet.setColor(getColor(R.color.anynoise_link));
            dataSet.setLineWidth(2f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setHighlightEnabled(false);
            dataSets.add(dataSet);
        }

        float latestTimeSec = maxX;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (dataSets.isEmpty()) {
                showEmptyState();
                return;
            }
            tvError.setVisibility(View.GONE);
            chart.setVisibility(View.VISIBLE);
            chart.setData(new LineData(dataSets));
            chart.invalidate();
            chart.moveViewToX(latestTimeSec);
        });
    }

    private void showEmptyState() {
        chart.setVisibility(View.GONE);
        tvError.setVisibility(View.VISIBLE);
        tvError.setText(R.string.history_graph_empty);
    }

    private void showError(Exception e) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            chart.setVisibility(View.GONE);
            tvError.setVisibility(View.VISIBLE);
            tvError.setText(getString(R.string.history_graph_load_error) + "\n" + e);
        });
    }

    private void loadSensorGraphs() {
        int lightThreshold = Prefs.getLightSensitivityThresholdLux(this);
        int vibrationThreshold = Prefs.getVibrationSensitivityThreshold(this);

        FirebaseFirestore.getInstance().collection("noiseEvents")
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(SENSOR_EVENT_FETCH_LIMIT)
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<DocumentSnapshot> lightDocs = new ArrayList<>();
                    List<DocumentSnapshot> vibrationDocs = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        String soundType = doc.getString("soundType");
                        if (soundType == null) {
                            continue;
                        }
                        if (soundType.startsWith("LIGHT_")) {
                            lightDocs.add(doc);
                        } else if (soundType.startsWith("VIBRATION_")) {
                            vibrationDocs.add(doc);
                        }
                    }
                    renderSensorChart(chartLight, tvLightError, lightDocs, lightThreshold,
                            R.string.history_graph_light_empty);
                    renderSensorChart(chartVibration, tvVibrationError, vibrationDocs, vibrationThreshold,
                            R.string.history_graph_vibration_empty);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to load light/vibration events", e);
                    showSensorChartError(chartLight, tvLightError, e);
                    showSensorChartError(chartVibration, tvVibrationError, e);
                });
    }

    private void renderSensorChart(LineChart chart, TextView errorView, List<DocumentSnapshot> docs,
                                    float threshold, int emptyStringRes) {
        List<ILineDataSet> dataSets = new ArrayList<>();
        float maxX = Float.MIN_VALUE;
        for (DocumentSnapshot doc : docs) {
            Date startedAt = doc.getDate("startedAt");
            Double sensorValue = doc.getDouble("sensorValue");
            String soundType = doc.getString("soundType");
            if (startedAt == null || sensorValue == null || soundType == null) {
                continue;
            }
            float timeSec = startedAt.getTime() / 1000f;
            maxX = Math.max(maxX, timeSec);

            List<Entry> entries = new ArrayList<>(2);
            entries.add(new Entry(timeSec, 0f));
            entries.add(new Entry(timeSec, sensorValue.floatValue()));
            LineDataSet dataSet = new LineDataSet(entries, null);
            dataSet.setColor(soundType.endsWith("_ON") ? getColor(R.color.anynoise_link) : Color.LTGRAY);
            dataSet.setLineWidth(2f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setHighlightEnabled(false);
            dataSets.add(dataSet);
        }

        float latestTimeSec = maxX;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            chart.getAxisLeft().removeAllLimitLines();
            LimitLine limitLine = new LimitLine(threshold, getString(R.string.history_graph_threshold_label, threshold));
            limitLine.setLineColor(getColor(R.color.anynoise_accent));
            limitLine.setLineWidth(1.5f);
            limitLine.setTextSize(11f);
            limitLine.setTextColor(resolveAxisTextColor());
            limitLine.enableDashedLine(12f, 6f, 0f);
            chart.getAxisLeft().addLimitLine(limitLine);

            if (dataSets.isEmpty()) {
                chart.setVisibility(View.GONE);
                errorView.setVisibility(View.VISIBLE);
                errorView.setText(emptyStringRes);
                return;
            }
            errorView.setVisibility(View.GONE);
            chart.setVisibility(View.VISIBLE);
            chart.setData(new LineData(dataSets));
            chart.invalidate();
            chart.moveViewToX(latestTimeSec);
        });
    }

    private void showSensorChartError(LineChart chart, TextView errorView, Exception e) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            chart.setVisibility(View.GONE);
            errorView.setVisibility(View.VISIBLE);
            errorView.setText(getString(R.string.history_graph_load_error) + "\n" + e);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
