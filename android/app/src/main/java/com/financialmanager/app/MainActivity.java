package com.financialmanager.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Hosts the app's web UI in a WebView pointed at the user's own server.
 *
 * The interesting parts are the three things a plain WebView gets wrong:
 * CSV import (needs a file chooser), CSV export (needs a download handler),
 * and bank authorisation links (need the real browser, because banks refuse
 * to authenticate inside an embedded WebView).
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "server_url";

    private WebView webView;
    private String baseUrl;
    private ValueCallback<Uri[]> pendingFileCallback;

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (pendingFileCallback == null) {
                    return;
                }
                Uri[] uris = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri single = result.getData().getData();
                    if (single != null) {
                        uris = new Uri[]{single};
                    }
                }
                pendingFileCallback.onReceiveValue(uris);
                pendingFileCallback = null;
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        baseUrl = getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(baseUrl)) {
            SharedPreferences prefs = getSharedPreferences(SetupActivity.PREFS, MODE_PRIVATE);
            baseUrl = prefs.getString(SetupActivity.KEY_SERVER_URL, null);
        }
        if (TextUtils.isEmpty(baseUrl)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        webView = findViewById(R.id.web_view);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);       // the UI keeps state in localStorage
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isOwnServer(uri)) {
                    return false;  // stay in the app
                }
                // Bank authorisation and anything else external goes to the real
                // browser: banks reject embedded WebViews, and it keeps their login
                // page out of a window this app controls.
                openExternally(uri);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showUnreachableDialog();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    filePicker.launch(params.createIntent());
                    return true;
                } catch (ActivityNotFoundException exception) {
                    pendingFileCallback = null;
                    Toast.makeText(MainActivity.this, R.string.no_file_picker, Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        // CSV export: hand the download to the system downloader, with the
        // session cookie attached so the request is authenticated.
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setMimeType(mimeType);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalFilesDir(
                            MainActivity.this, null, guessFileName(contentDisposition));
                    DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, R.string.downloading, Toast.LENGTH_SHORT).show();
                } catch (Exception exception) {
                    Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(baseUrl);
        }
    }

    private boolean isOwnServer(Uri uri) {
        Uri base = Uri.parse(baseUrl);
        return uri.getHost() != null && uri.getHost().equalsIgnoreCase(base.getHost());
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private static String guessFileName(String contentDisposition) {
        if (contentDisposition != null && contentDisposition.contains("filename=")) {
            String name = contentDisposition.substring(contentDisposition.indexOf("filename=") + 9)
                    .replace("\"", "").trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return "transactions.csv";
    }

    private void showUnreachableDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cannot_connect_title)
                .setMessage(getString(R.string.cannot_connect_body, baseUrl))
                .setPositiveButton(R.string.retry, (dialog, which) -> webView.loadUrl(baseUrl))
                .setNegativeButton(R.string.change_server, (dialog, which) -> {
                    Intent intent = new Intent(MainActivity.this, SetupActivity.class);
                    intent.putExtra("reconfigure", true);
                    startActivity(intent);
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
