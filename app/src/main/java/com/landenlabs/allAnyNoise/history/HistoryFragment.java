package com.landenlabs.allAnyNoise.history;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows Sheet1's own rows and columns natively — downloaded as CSV rather
 * than rendered via the Sheet's gviz HTML (that raw view moved to Settings,
 * see SheetViewActivity) — so the Timestamp column can be reformatted from
 * UTC ISO 8601 into a short device-local-time string; every other column
 * is shown exactly as stored.
 */
public class HistoryFragment extends Fragment {

    private static final String TAG = "HistoryFragment";

    static final String SPREADSHEET_ID = "1J2IXyZ5mrE0y2wRPAHoWYa7ZdAo5eySpjeRjyvtWcZk";
    static final String SHEET_GID = "0";
    private static final String SHEET_CSV_URL = "https://docs.google.com/spreadsheets/d/"
            + SPREADSHEET_ID + "/gviz/tq?tqx=out:csv&gid=" + SHEET_GID;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvRowCount;
    private TextView tvLastRow;
    private TextView tvError;
    private HistoryAdapter adapter;
    private TextView[] headerColumns;
    private String[] headerLabels;
    private int sortColumn = -1;
    private boolean sortAscending = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvRowCount = view.findViewById(R.id.tv_row_count);
        tvLastRow = view.findViewById(R.id.tv_last_row);
        tvError = view.findViewById(R.id.tv_history_error);

        RecyclerView recyclerView = view.findViewById(R.id.rv_history);
        adapter = new HistoryAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        setupSortableHeader(view);
        requireActivity().addMenuProvider(graphMenuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        loadSheetRows();
    }

    private final MenuProvider graphMenuProvider = new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
            menuInflater.inflate(R.menu.history_menu, menu);
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
            if (menuItem.getItemId() == R.id.action_view_graph) {
                startActivity(new Intent(requireContext(), HistoryGraphActivity.class));
                return true;
            }
            return false;
        }
    };

    private void setupSortableHeader(@NonNull View view) {
        headerColumns = new TextView[]{
                view.findViewById(R.id.tv_head0),
                view.findViewById(R.id.tv_head1),
                view.findViewById(R.id.tv_head2),
                view.findViewById(R.id.tv_head3),
                view.findViewById(R.id.tv_head4),
                view.findViewById(R.id.tv_head5)
        };
        headerLabels = new String[]{
                getString(R.string.history_col_time),
                getString(R.string.history_col_listener),
                getString(R.string.history_col_duration),
                getString(R.string.history_col_audio),
                getString(R.string.history_col_type),
                getString(R.string.history_col_label)
        };
        for (int i = 0; i < headerColumns.length; i++) {
            headerColumns[i].setText(headerLabels[i]);
            int column = i;
            headerColumns[i].setOnClickListener(v -> onHeaderClicked(column));
        }
    }

    private void onHeaderClicked(int column) {
        sortAscending = column != sortColumn || !sortAscending;
        sortColumn = column;
        adapter.sortBy(sortColumn, sortAscending);
        String arrow = sortAscending ? " ▲" : " ▼";
        for (int i = 0; i < headerColumns.length; i++) {
            headerColumns[i].setText(i == sortColumn ? headerLabels[i] + arrow : headerLabels[i]);
        }
    }

    private void loadSheetRows() {
        executor.execute(() -> {
            try {
                List<String[]> rows = fetchSheetRows();
                showRows(rows);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load Sheet1 CSV", e);
                showError(e);
            }
        });
    }

    private void showRows(List<String[]> rows) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            tvError.setVisibility(View.GONE);
            adapter.submit(rows);
            tvRowCount.setText(getString(R.string.history_row_count, rows.size()));
            if (rows.isEmpty()) {
                tvLastRow.setText(R.string.history_no_events);
                return;
            }
            String[] last = rows.get(rows.size() - 1);
            String time = last.length > 0 ? formatIsoTimestamp(last[0]) : "?";
            String listenerName = last.length > 1 ? last[1] : "";
            double durationSec = 0;
            if (last.length > 2) {
                try {
                    durationSec = Double.parseDouble(last[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            tvLastRow.setText(getString(R.string.history_last_row, time, durationSec, listenerName));
        });
    }

    private void showError(Exception e) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText(getString(R.string.history_load_error) + "\n" + e);
            tvLastRow.setText(R.string.history_no_events);
        });
    }

    // Some networks (asymmetric IPv6 routing) stall for 10-15s on a failed
    // IPv6 attempt before falling back to IPv4; give that fallback room.
    private static final int NETWORK_TIMEOUT_MS = 30000;

    static List<String[]> fetchSheetRows() throws IOException {
        Log.d(TAG, "Fetching " + SHEET_CSV_URL);
        URL url = new URL(SHEET_CSV_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.connect();
            int code = connection.getResponseCode();
            Log.d(TAG, "Response code: " + code);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            StringBuilder csv = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    csv.append(line).append('\n');
                }
            }
            List<String[]> rows = parseCsv(csv.toString());
            Log.d(TAG, "Parsed " + rows.size() + " rows, " + csv.length() + " chars");
            return rows;
        } finally {
            connection.disconnect();
        }
    }

    private static List<String[]> parseCsv(String csv) {
        List<String[]> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int length = csv.length();
        for (int i = 0; i < length; i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < length && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                if (current.size() > 1 || !current.get(0).isEmpty()) {
                    rows.add(current.toArray(new String[0]));
                }
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current.toArray(new String[0]));
        }
        return rows;
    }

    /**
     * Parses a UTC ISO 8601 timestamp string and reformats it in the
     * device's local timezone, dropping the year and month when they match
     * today's — e.g. "23 02:30 PM" this month, or "07/23 02:30 PM" in a
     * different month. Falls back to the raw string if it can't be parsed.
     */
    static String formatIsoTimestamp(String iso) {
        Date date = parseIsoTimestamp(iso);
        return date != null ? formatHistoryTimestamp(date) : iso;
    }

    /** Parses a UTC ISO 8601 timestamp string into a Date, or null if it can't be parsed. */
    static Date parseIsoTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) {
            return null;
        }
        try {
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            return isoFormat.parse(iso);
        } catch (ParseException e) {
            return null;
        }
    }

    static String formatHistoryTimestamp(Date date) {
        Calendar now = Calendar.getInstance();
        Calendar event = Calendar.getInstance();
        event.setTime(date);
        boolean sameMonth = now.get(Calendar.YEAR) == event.get(Calendar.YEAR)
                && now.get(Calendar.MONTH) == event.get(Calendar.MONTH);
        String pattern = sameMonth ? "dd hh:mm a" : "MM/dd hh:mm a";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(date);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
