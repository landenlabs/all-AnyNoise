package com.landenlabs.allAnyNoise.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Renders raw Sheet1 rows [timestamp, listenerName, durationSec, audioUrl,
 * soundType] as-is, except column 0, which is reformatted from UTC ISO
 * 8601 into a short device-local-time string.
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    // Column 2 (durationSec) sorts numerically; every other column sorts as text.
    // Column 0 (ISO 8601 timestamp) happens to sort correctly as text too.
    private static final int NUMERIC_COLUMN = 2;

    private final List<String[]> rows = new ArrayList<>();
    private int sortColumn = -1;
    private boolean sortAscending = true;

    public void submit(List<String[]> newRows) {
        rows.clear();
        rows.addAll(newRows);
        if (sortColumn >= 0) {
            sortRows();
        }
        notifyDataSetChanged();
    }

    public void sortBy(int column, boolean ascending) {
        sortColumn = column;
        sortAscending = ascending;
        sortRows();
        notifyDataSetChanged();
    }

    private void sortRows() {
        Comparator<String[]> comparator = (a, b) -> compareCell(a, b, sortColumn);
        if (!sortAscending) {
            comparator = comparator.reversed();
        }
        Collections.sort(rows, comparator);
    }

    private static int compareCell(String[] a, String[] b, int column) {
        String sa = column < a.length ? a[column] : "";
        String sb = column < b.length ? b[column] : "";
        if (column == NUMERIC_COLUMN) {
            try {
                return Double.compare(Double.parseDouble(sa), Double.parseDouble(sb));
            } catch (NumberFormatException ignored) {
                // Fall through to text comparison.
            }
        }
        return sa.compareToIgnoreCase(sb);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String[] row = rows.get(position);
        for (int i = 0; i < holder.columns.length; i++) {
            String cell = i < row.length ? row[i] : "";
            holder.columns[i].setText(i == 0 ? HistoryFragment.formatIsoTimestamp(cell) : cell);
        }
        int bgColor = ContextCompat.getColor(holder.itemView.getContext(), position % 2 == 0
                ? R.color.anynoise_surface
                : R.color.anynoise_history_row_alt);
        holder.itemView.setBackgroundColor(bgColor);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView[] columns;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            columns = new TextView[]{
                    itemView.findViewById(R.id.tv_col0),
                    itemView.findViewById(R.id.tv_col1),
                    itemView.findViewById(R.id.tv_col2),
                    itemView.findViewById(R.id.tv_col3),
                    itemView.findViewById(R.id.tv_col4)
            };
        }
    }
}
