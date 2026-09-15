package ir.khatman.dksession;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Fetches the session for a given access code from the backend, applies the
 * returned cookies via {@link CookieManager}, then loads Digikala in a WebView
 * and injects the returned localStorage entries. This mirrors the behaviour of
 * the browser extension (input.js + content.js).
 */
public class WebViewActivity extends AppCompatActivity {

    public static final String EXTRA_CODE = "extra_code";

    private static final String BASE_URL = "https://bot.khatman.ir/get-session/";
    private static final String TARGET_URL = "https://www.digikala.com/profile/";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private WebView webView;
    private ProgressBar progress;
    private TextView overlayStatus;
    private SessionStore sessionStore;

    private JSONObject localStorageData;
    private boolean localStorageInjected = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        sessionStore = new SessionStore(this);
        webView = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        overlayStatus = findViewById(R.id.overlayStatus);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // Present ourselves as a normal mobile Chrome, not the WebView UA.
        settings.setUserAgentString(MOBILE_UA);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep navigation inside the WebView.
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                // After the first successful load, inject localStorage once and
                // reload so the site picks it up on start-up (like content.js).
                if (!localStorageInjected
                        && localStorageData != null
                        && localStorageData.length() > 0) {
                    injectLocalStorage(view);
                    localStorageInjected = true;
                    progress.setVisibility(View.VISIBLE);
                    view.reload();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    showOverlay(getString(R.string.error_network));
                }
            }

            // Legacy callback for API 21-22 (WebResourceRequest version is API 23+).
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                progress.setVisibility(View.GONE);
                showOverlay(getString(R.string.error_network));
            }
        });

        String code = getIntent().getStringExtra(EXTRA_CODE);
        if (code == null || !code.matches("\\d{12}")) {
            showOverlay(getString(R.string.error_invalid));
            return;
        }
        fetchSession(code);
    }

    private void fetchSession(final String code) {
        progress.setVisibility(View.VISIBLE);
        showOverlay(getString(R.string.loading));

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BASE_URL + code);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept", "application/json");

                int status = conn.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    runOnUiThread(() -> showOverlay(getString(R.string.error_invalid)));
                    return;
                }

                String body = readStream(conn.getInputStream());
                final JSONObject data = new JSONObject(body);
                runOnUiThread(() -> applySession(data));
            } catch (Exception e) {
                runOnUiThread(() -> showOverlay(getString(R.string.error_network)));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private void applySession(JSONObject data) {
        try {
            JSONArray cookies = data.optJSONArray("cookies");
            CookieManager cookieManager = CookieManager.getInstance();
            if (cookies != null) {
                for (int i = 0; i < cookies.length(); i++) {
                    JSONObject cookie = cookies.optJSONObject(i);
                    if (cookie == null) {
                        continue;
                    }
                    String domain = cookie.optString("domain", "");
                    String domainNoDot = domain.startsWith(".") ? domain.substring(1) : domain;
                    if (domainNoDot.isEmpty()) {
                        continue;
                    }
                    String path = cookie.optString("path", "/");
                    String cookieUrl = "https://" + domainNoDot + path;
                    cookieManager.setCookie(cookieUrl, buildCookieString(cookie));
                }
            }
            cookieManager.flush();

            localStorageData = data.optJSONObject("localStorage");
            localStorageInjected = false;

            // Clear persisted session data now that it's in memory.
            sessionStore.clear();

            hideOverlay();
            progress.setVisibility(View.VISIBLE);
            webView.loadUrl(TARGET_URL);
        } catch (Exception e) {
            showOverlay(getString(R.string.error_network));
        }
    }

    private void injectLocalStorage(WebView view) {
        StringBuilder script = new StringBuilder();
        script.append("(function(){try{");
        Iterator<String> keys = localStorageData.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = localStorageData.optString(key, "");
            // JSONObject.quote produces a safely escaped JS string literal.
            script.append("localStorage.setItem(")
                    .append(JSONObject.quote(key)).append(",")
                    .append(JSONObject.quote(value)).append(");");
        }
        script.append("}catch(e){console.error(e);}})();");
        view.evaluateJavascript(script.toString(), null);
    }

    /**
     * Builds a standard Set-Cookie string from a Chrome-extension style cookie
     * object so CookieManager can store it.
     */
    private String buildCookieString(JSONObject cookie) {
        StringBuilder sb = new StringBuilder();
        sb.append(cookie.optString("name")).append("=").append(cookie.optString("value"));
        sb.append("; Path=").append(cookie.optString("path", "/"));

        String domain = cookie.optString("domain", "");
        if (!domain.isEmpty()) {
            sb.append("; Domain=").append(domain);
        }

        boolean secure = cookie.optBoolean("secure", false);
        if (secure) {
            sb.append("; Secure");
        }
        if (cookie.optBoolean("httpOnly", false)) {
            sb.append("; HttpOnly");
        }

        String sameSite = mapSameSite(cookie.optString("sameSite", ""));
        if (sameSite != null) {
            // SameSite=None must be accompanied by Secure, otherwise the WebView
            // rejects the cookie outright.
            if ("None".equals(sameSite) && !secure) {
                sb.append("; Secure");
            }
            sb.append("; SameSite=").append(sameSite);
        }

        if (cookie.has("expirationDate") && !cookie.isNull("expirationDate")) {
            double exp = cookie.optDouble("expirationDate", 0);
            if (exp > 0) {
                sb.append("; Expires=").append(formatExpires((long) (exp * 1000L)));
            }
        }
        return sb.toString();
    }

    private String mapSameSite(String value) {
        if (value == null) {
            return null;
        }
        switch (value.toLowerCase(Locale.US)) {
            case "no_restriction":
                return "None";
            case "lax":
                return "Lax";
            case "strict":
                return "Strict";
            default:
                return null;
        }
    }

    private String formatExpires(long millis) {
        // Standard HTTP cookie date format: space-separated, not dashes.
        SimpleDateFormat fmt =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt.format(new Date(millis));
    }

    private String readStream(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void showOverlay(String message) {
        overlayStatus.setText(message);
        overlayStatus.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlayStatus.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}