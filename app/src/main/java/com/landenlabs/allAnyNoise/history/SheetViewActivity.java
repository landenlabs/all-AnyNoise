// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.history;

import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.landenlabs.allAnyNoise.R;

/**
 * Shows the noiseEvents Sheet's own gviz HTML rendering in a WebView — the
 * raw, full-fidelity table (all columns, unmodified UTC timestamps) that
 * used to live on the History tab. History now shows a native equivalent
 * with local-time formatting, so this raw view moved here, reachable from
 * Settings.
 */
public class SheetViewActivity extends AppCompatActivity {

    private static final String SHEET_HTML_URL = "https://docs.google.com/spreadsheets/d/"
            + HistoryFragment.SPREADSHEET_ID + "/gviz/tq?tqx=out:html&gid=" + HistoryFragment.SHEET_GID;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sheet_view);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        WebView webView = findViewById(R.id.webview_sheet);
        TextView tvError = findViewById(R.id.tv_webview_error);

        webView.getSettings().setJavaScriptEnabled(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    webView.setVisibility(View.GONE);
                    tvError.setVisibility(View.VISIBLE);
                }
            }
        });
        webView.loadUrl(SHEET_HTML_URL);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
