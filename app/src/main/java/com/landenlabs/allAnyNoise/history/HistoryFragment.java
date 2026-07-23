package com.landenlabs.allAnyNoise.history;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.NoiseEvent;
import com.google.firebase.firestore.AggregateSource;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Shows the Google Sheet that mirrors noiseEvents history (via the Cloud
 * Function's Sheets webhook) in a WebView, using the Sheet's gviz HTML
 * endpoint since the full Sheets editor UI can't be embedded on mobile.
 * A row-count / last-row summary is read directly from Firestore (the
 * source of truth the Sheet is mirroring) and shown regardless of whether
 * the WebView succeeds, since it needs no Sheets sharing/auth to work.
 */
public class HistoryFragment extends Fragment {

    private static final String SPREADSHEET_ID = "1J2IXyZ5mrE0y2wRPAHoWYa7ZdAo5eySpjeRjyvtWcZk";
    private static final String SHEET_GID = "0";
    private static final String SHEET_HTML_URL = "https://docs.google.com/spreadsheets/d/"
            + SPREADSHEET_ID + "/gviz/tq?tqx=out:html&gid=" + SHEET_GID;

    private TextView tvRowCount;
    private TextView tvLastRow;
    private TextView tvWebViewError;
    private WebView webView;

    private ListenerRegistration lastRowRegistration;

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
        tvWebViewError = view.findViewById(R.id.tv_webview_error);
        webView = view.findViewById(R.id.webview_history);

        webView.getSettings().setJavaScriptEnabled(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showWebViewError();
                }
            }
        });
        webView.loadUrl(SHEET_HTML_URL);

        refreshRowCount();
        lastRowRegistration = FirebaseFirestore.getInstance().collection("noiseEvents")
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(1)
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null || snapshot.isEmpty()) {
                        tvLastRow.setText(R.string.history_no_events);
                        return;
                    }
                    NoiseEvent event = snapshot.getDocuments().get(0).toObject(NoiseEvent.class);
                    String time = event.startedAt != null
                            ? new SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(event.startedAt)
                            : "?";
                    tvLastRow.setText(getString(R.string.history_last_row,
                            time, event.durationSec, event.listenerName));
                    refreshRowCount();
                });
    }

    private void refreshRowCount() {
        FirebaseFirestore.getInstance().collection("noiseEvents").count()
                .get(AggregateSource.SERVER)
                .addOnSuccessListener(snapshot ->
                        tvRowCount.setText(getString(R.string.history_row_count, snapshot.getCount())));
    }

    private void showWebViewError() {
        webView.setVisibility(View.GONE);
        tvWebViewError.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (lastRowRegistration != null) {
            lastRowRegistration.remove();
        }
    }
}
