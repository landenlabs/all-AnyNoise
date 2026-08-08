// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.listen;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.google.android.material.color.MaterialColors;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.landenlabs.allAnyNoise.Prefs;
import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.NoiseEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Full-page live view opened when the Listen tab starts listening: a Stop
 * button, the last detected event, and three equal strip charts (audio,
 * light, vibration) scrolling in real time. Each chart is capped to a
 * rolling 5-minute window - older points are dropped as new ones arrive -
 * so pinch-zoom only ever narrows into data that's actually there instead of
 * zooming out into empty space.
 */
public class LiveListenActivity extends AppCompatActivity implements NoiseListenerService.LiveSampleListener {

    private static final int RECENT_EVENTS_LIMIT = 25;
    private static final float WINDOW_SECONDS = 5 * 60f;
    private static final float MIN_VISIBLE_SECONDS = 10f;

    private TextView tvLastEvent;
    private LiveStrip audioStrip;
    private LiveStrip lightStrip;
    private LiveStrip vibrationStrip;
    private ListenerRegistration lastEventRegistration;
    private long startTimeMs = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_listen);

        startTimeMs = System.currentTimeMillis();

        Button btnStop = findViewById(R.id.btn_live_stop);
        btnStop.setOnClickListener(v -> stopListening());

        tvLastEvent = findViewById(R.id.tv_live_last_event);

        int lineColor = getColor(R.color.anynoise_link);
        audioStrip = new LiveStrip(findViewById(R.id.chart_live_audio), lineColor,
                Prefs.getThresholdAmplitude(this), startTimeMs);
        lightStrip = new LiveStrip(findViewById(R.id.chart_live_light), lineColor,
                Prefs.getLightSensitivityThresholdLux(this), startTimeMs);
        vibrationStrip = new LiveStrip(findViewById(R.id.chart_live_vibration), lineColor,
                Prefs.getVibrationSensitivityThreshold(this), startTimeMs);

        listenForLastEvent();

        // While listening is active, this screen is the only way in - back
        // should exit like it would from the app's root, not pop into the
        // Listen tab underneath (which would leave no way back to this screen;
        // see ListenFragment.onResume(), which re-opens it whenever that tab
        // surfaces while listening is still active).
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
    }

    private void listenForLastEvent() {
        lastEventRegistration = FirebaseFirestore.getInstance().collection("noiseEvents")
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(RECENT_EVENTS_LIMIT)
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null) {
                        return;
                    }
                    String listenerId = Prefs.getListenerId(this);
                    for (QueryDocumentSnapshot doc : snapshot) {
                        NoiseEvent event = doc.toObject(NoiseEvent.class);
                        if (listenerId != null && listenerId.equals(event.listenerId)) {
                            tvLastEvent.setText(describeEvent(event));
                            return;
                        }
                    }
                    tvLastEvent.setText(R.string.listen_last_event_none);
                });
    }

    private String describeEvent(NoiseEvent event) {
        String time = event.startedAt != null
                ? new SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(event.startedAt)
                : "?";
        String binaryState = describeBinaryState(event.soundType);
        return binaryState != null
                ? getString(R.string.listen_last_event_binary_value, time, binaryState)
                : getString(R.string.listen_last_event_value, time, event.durationSec);
    }

    @Nullable
    private String describeBinaryState(@Nullable String soundType) {
        if (soundType == null) {
            return null;
        }
        switch (soundType) {
            case "LIGHT_ON":
                return getString(R.string.listen_last_event_light_on);
            case "LIGHT_OFF":
                return getString(R.string.listen_last_event_light_off);
            case "VIBRATION_ON":
                return getString(R.string.listen_last_event_vibration_on);
            case "VIBRATION_OFF":
                return getString(R.string.listen_last_event_vibration_off);
            default:
                return null;
        }
    }

    private void stopListening() {
        Intent intent = new Intent(this, NoiseListenerService.class);
        intent.setAction(NoiseListenerService.ACTION_STOP);
        startService(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        NoiseListenerService.setLiveSampleListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        NoiseListenerService.setLiveSampleListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lastEventRegistration != null) {
            lastEventRegistration.remove();
        }
    }

    @Override
    public void onAudioSample(long timestampMs, float amplitude) {
        audioStrip.append(timestampMs, amplitude);
    }

    @Override
    public void onLightSample(long timestampMs, float lux) {
        lightStrip.append(timestampMs, lux);
    }

    @Override
    public void onVibrationSample(long timestampMs, float magnitude) {
        vibrationStrip.append(timestampMs, magnitude);
    }

    /**
     * One scrolling strip chart: appends a sample, drops anything older than
     * WINDOW_SECONDS, and auto-follows the latest point - except while the
     * user is actively touching the chart (pinch/pan), so a live sample
     * doesn't yank the view out from under a zoom gesture.
     */
    private class LiveStrip {
        private final LineChart chart;
        private final LineDataSet dataSet;
        private final long baseTimeMs;
        private volatile boolean userInteracting;

        LiveStrip(LineChart chart, int lineColor, float threshold, long baseTimeMs) {
            this.chart = chart;
            this.baseTimeMs = baseTimeMs;
            this.dataSet = new LineDataSet(new ArrayList<>(), null);
            setUp(lineColor, threshold);
        }

        private void setUp(int lineColor, float threshold) {
            int axisTextColor = MaterialColors.getColor(LiveListenActivity.this, android.R.attr.textColorPrimary, Color.BLACK);

            chart.getDescription().setEnabled(false);
            chart.getLegend().setEnabled(false);
            chart.getAxisRight().setEnabled(false);
            chart.getAxisLeft().setAxisMinimum(0f);
            chart.getAxisLeft().setTextColor(axisTextColor);

            XAxis xAxis = chart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setTextColor(axisTextColor);
            xAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float value) {
                    long absoluteMs = baseTimeMs + (long) (value * 1000L);
                    return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(absoluteMs));
                }
            });

            chart.setTouchEnabled(true);
            chart.setDragEnabled(true);
            chart.setScaleXEnabled(true);
            chart.setScaleYEnabled(false);
            chart.setPinchZoom(false);
            chart.setDoubleTapToZoomEnabled(true);
            // NOT setting setVisibleXRangeMinimum/Maximum here: the dataset is
            // still empty at this point, so MPAndroidChart would compute the
            // scale constraint against a meaningless zero-width axis range and
            // never revisit it. Applied instead in append(), once real data exists.
            chart.setOnChartGestureListener(new OnChartGestureListener() {
                @Override
                public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture gesture) {
                    userInteracting = true;
                }

                @Override
                public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture gesture) {
                    userInteracting = false;
                }

                @Override
                public void onChartLongPressed(MotionEvent me) {
                }

                @Override
                public void onChartDoubleTapped(MotionEvent me) {
                }

                @Override
                public void onChartSingleTapped(MotionEvent me) {
                }

                @Override
                public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {
                }

                @Override
                public void onChartScale(MotionEvent me, float scaleX, float scaleY) {
                }

                @Override
                public void onChartTranslate(MotionEvent me, float dX, float dY) {
                }
            });

            LimitLine limitLine = new LimitLine(threshold, getString(R.string.history_graph_threshold_label, threshold));
            limitLine.setLineColor(getColor(R.color.anynoise_accent));
            limitLine.setLineWidth(1.5f);
            limitLine.setTextSize(11f);
            limitLine.setTextColor(axisTextColor);
            limitLine.enableDashedLine(12f, 6f, 0f);
            chart.getAxisLeft().addLimitLine(limitLine);

            dataSet.setColor(lineColor);
            dataSet.setLineWidth(1.5f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setHighlightEnabled(false);
            chart.setData(new LineData(dataSet));
        }

        void append(long timestampMs, float value) {
            // Seconds elapsed since this screen opened, NOT absolute epoch
            // seconds - a raw epoch-seconds value (~1.7 billion) stored in a
            // 32-bit float loses so much precision that consecutive samples
            // 150-200ms apart round to the same float, so the trace never
            // visibly advances. Kept small and relative instead.
            float timeSec = (timestampMs - baseTimeMs) / 1000f;
            dataSet.addEntry(new Entry(timeSec, value));

            float cutoff = timeSec - WINDOW_SECONDS;
            while (dataSet.getEntryCount() > 0 && dataSet.getEntryForIndex(0).getX() < cutoff) {
                dataSet.removeFirst();
            }

            chart.getData().notifyDataChanged();
            chart.notifyDataSetChanged();
            // Re-applied every call (cheap) rather than once in setUp(), since the
            // very first call is what gives the axis its first real, non-empty range.
            chart.setVisibleXRangeMinimum(MIN_VISIBLE_SECONDS);
            chart.setVisibleXRangeMaximum(WINDOW_SECONDS);
            if (!userInteracting) {
                chart.moveViewToX(timeSec);
            }
            chart.invalidate();
        }
    }
}
