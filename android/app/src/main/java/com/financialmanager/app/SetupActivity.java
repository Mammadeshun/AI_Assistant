package com.financialmanager.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Asks where the user's own server lives.
 *
 * This app is a client for a server you run yourself — there is no hosted
 * backend to default to, so the first launch has to ask for the address.
 */
public class SetupActivity extends AppCompatActivity {

    public static final String PREFS = "fm_prefs";
    public static final String KEY_SERVER_URL = "server_url";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_SERVER_URL, null);
        boolean reconfiguring = getIntent().getBooleanExtra("reconfigure", false);

        if (!TextUtils.isEmpty(saved) && !reconfiguring) {
            openApp(saved);
            return;
        }

        setContentView(R.layout.activity_setup);

        EditText input = findViewById(R.id.server_url);
        Button connect = findViewById(R.id.connect);
        TextView statusView = findViewById(R.id.status);

        if (!TextUtils.isEmpty(saved)) {
            input.setText(saved);
        }

        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String raw = input.getText().toString().trim();
                if (TextUtils.isEmpty(raw)) {
                    Toast.makeText(SetupActivity.this, R.string.enter_address, Toast.LENGTH_SHORT).show();
                    return;
                }
                final String url = normalise(raw);
                connect.setEnabled(false);
                statusView.setText(R.string.checking);

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final boolean reachable = canReach(url);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                connect.setEnabled(true);
                                if (reachable) {
                                    prefs.edit().putString(KEY_SERVER_URL, url).apply();
                                    openApp(url);
                                } else {
                                    statusView.setText(getString(R.string.cannot_reach, url));
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    /** Accepts "192.168.1.20:8000" as readily as a full URL. */
    static String normalise(String raw) {
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private boolean canReach(String baseUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/api/health").openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (Exception exception) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void openApp(String url) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_URL, url);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
