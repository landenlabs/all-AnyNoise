package com.landenlabs.allAnyNoise.history;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
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
 */
public class HistoryGraphActivity extends AppCompatActivity {

    private static final String TAG = "HistoryGraphActivity";

    // Keeps a double-tap or pinch from zooming in past a window this narrow.
    private static final float MIN_VISIBLE_SECONDS = 30f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LineChart chart;
    private TextView tvError;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_graph);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        chart = findViewById(R.id.chart_history);
        tvError = findViewById(R.id.tv_graph_error);
        setupChart();
        loadGraphData();
    }

    private void setupChart() {
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setTextSize(15f);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        });

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return HistoryFragment.formatHistoryTimestamp(new Date((long) (value * 1000L)));
            }
        });

        // Only the time (x) axis scrolls/zooms; duration (y) stays fixed.
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleXEnabled(true);
        chart.setScaleYEnabled(false);
        chart.setPinchZoom(false);
        chart.setDoubleTapToZoomEnabled(true);
        chart.setVisibleXRangeMinimum(MIN_VISIBLE_SECONDS);
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

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
