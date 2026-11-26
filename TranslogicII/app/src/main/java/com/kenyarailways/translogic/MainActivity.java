
package com.kenyarailways.translogic;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class MainActivity extends Activity {

    private static final String TRANSLOGIC_URL =
        "http://197.248.185.37/Translogic2/translogicII/vRelPainelGrafico.asp?IDUP=RVR&CODUP=RVR&CODCLASSETREM=&IDUPDEST=0&CODUPDEST=&IDIOMA=en-US";

    private static final String CHANNEL_ID = "translogic_updates";
    private static final String PREFS = "train_monitor";
    private static final String RECORDS_KEY = "previous_records";

    private WebView webView;
    private boolean pageReady = false;

    private final Handler refreshHandler =
        new Handler(Looper.getMainLooper());

    private boolean refreshScheduled = false;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                webView.reload();
                refreshHandler.postDelayed(this, 60000);
            }
        }
    };

    private final String scanScript =
        "(function() {" +
        "var result=[];" +
        "var tables=document.querySelectorAll('table');" +
        "tables.forEach(function(table) {" +
        "var rows=table.querySelectorAll('tr');" +
        "var headerIndex=-1;" +
        "var trainIndex=-1, soIndex=-1;" +
        "var destinationIndex=-1, locationIndex=-1;" +

        "for(var r=0;r<rows.length;r++) {" +
        "var cells=rows[r].querySelectorAll('th,td');" +
        "var headers=Array.from(cells).map(function(c) {" +
        "return c.innerText.trim().toLowerCase().replace(/[^a-z]/g,'');" +
        "});" +

        "var ti=headers.indexOf('train');" +
        "var si=headers.indexOf('so');" +

        "if(ti>=0 && si>=0) {" +
        "headerIndex=r;" +
        "trainIndex=ti;" +
        "soIndex=si;" +
        "destinationIndex=headers.indexOf('destination');" +
        "locationIndex=headers.indexOf('currentlocation');" +
        "break;" +
        "}" +
        "}" +

        "if(headerIndex<0)return;" +

        "for(var r=headerIndex+1;r<rows.length;r++) {" +
        "var cells=rows[r].querySelectorAll('td');" +
        "if(cells.length<=Math.max(trainIndex,soIndex))continue;" +

        "var values=Array.from(cells).map(function(c) {" +
        "return c.innerText.trim().replace(/\\s+/g,' ');" +
        "});" +

        "var train=values[trainIndex]||'';" +
        "var so=values[soIndex]||'';" +
        "if(!train || !so)continue;" +

        "result.push({" +
        "train:train," +
        "so:so," +
        "destination:destinationIndex>=0?(values[destinationIndex]||''):''," +
        "location:locationIndex>=0?(values[locationIndex]||''):''," +
        "details:values.join(' | ')" +
        "});" +
        "}" +
        "});" +
        "return JSON.stringify(result);" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{"android.permission.POST_NOTIFICATIONS"},
                100
            );
        }

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        createNotificationChannel();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        webView.addJavascriptInterface(
            new TrainMonitorBridge(),
            "NativeMonitor"
        );

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view,
                WebResourceRequest request
            ) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                pageReady = true;

                if (!refreshScheduled) {
                    refreshScheduled = true;
                    refreshHandler.postDelayed(refreshRunnable, 60000);
                }

                scanTrains();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        if (savedInstanceState == null) {
            webView.loadUrl(TRANSLOGIC_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private class TrainMonitorBridge {
        @JavascriptInterface
        public void scan() {
            runOnUiThread(() -> scanTrains());
        }
    }

    private void scanTrains() {
        if (webView == null || !pageReady) return;

        webView.evaluateJavascript(
            scanScript,
            value -> {
                try {
                    if (value == null || value.equals("null")) return;

                    Object decoded =
                        new JSONTokener(value).nextValue();

                    processRecords(decoded.toString());

                } catch (Exception e) {
                    android.util.Log.e(
                        "Translogic",
                        "Table reading failed",
                        e
                    );
                }
            }
        );
    }

    private void processRecords(String data) {
        try {
            JSONArray records = new JSONArray(data);
            if (records.length() == 0) return;

            SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

            boolean hasBaseline =
                prefs.getBoolean("baseline", false);

            JSONObject previous = new JSONObject(
                prefs.getString(RECORDS_KEY, "{}")
            );

            JSONObject current = new JSONObject();

            StringBuilder whatsappAlerts = new StringBuilder();

            for (int i = 0; i < records.length(); i++) {

                JSONObject record = records.getJSONObject(i);

                String train = record.optString("train");
                String so = record.optString("so");
                String details = record.optString("details");
                String destination = record.optString("destination");
                String location = record.optString("location");

                String key = train + "|" + so;

                JSONObject savedRecord = new JSONObject();
                savedRecord.put("details", details);
                savedRecord.put("destination", destination);
                savedRecord.put("location", location);

                current.put(key, savedRecord);

                if (!hasBaseline) continue;

                if (!previous.has(key)) {

                    sendNotification(
                        "New train introduced",
                        "Train " + train + " | SO " + so
                    );

                    whatsappAlerts.append(
                        "NEW TRAIN\nTrain: " + train +
                        "\nSO: " + so + "\n\n"
                    );

                } else {

                    Object oldValue = previous.opt(key);
                    String oldDetails = "";
                    String oldLocation = "";
                    String oldDestination = "";

                    boolean structuredRecord =
                        oldValue instanceof JSONObject;

                    if (structuredRecord) {

                        JSONObject oldRecord = (JSONObject) oldValue;

                        oldDetails = oldRecord.optString("details");
                        oldLocation = oldRecord.optString("location");
                        oldDestination = oldRecord.optString("destination");

                    } else if (oldValue != null) {

                        // Supports records saved by the previous app version.
                        oldDetails = oldValue.toString();
                    }

                    if (!oldDetails.equals(details)) {
                        sendNotification(
                            "Train information updated",
                            "Train " + train + " | SO " + so
                        );
                    }

                    // Compare location and destination only when
                    // a structured previous record is available.
                    if (structuredRecord) {

                        if (!oldLocation.isEmpty() &&
                            !location.isEmpty() &&
                            !oldLocation.equals(location)) {

                            whatsappAlerts.append(
                                "LOCATION CHANGE\nTrain: " + train +
                                "\nSO: " + so +
                                "\nPrevious: " + oldLocation +
                                "\nNew: " + location + "\n\n"
                            );
                        }

                        if (!oldDestination.isEmpty() &&
                            !destination.isEmpty() &&
                            !oldDestination.equals(destination)) {

                            whatsappAlerts.append(
                                "DESTINATION CHANGE\nTrain: " + train +
                                "\nSO: " + so +
                                "\nPrevious: " + oldDestination +
                                "\nNew: " + destination + "\n\n"
                            );
                        }
                    }
                }
            }

            prefs.edit()
                .putString(RECORDS_KEY, current.toString())
                .putBoolean("baseline", true)
                .apply();

            if (whatsappAlerts.length() > 0) {
                openWhatsAppBusiness(
                    "KENYA RAILWAYS - TRANSLOGIC ALERTS\n\n" +
                    whatsappAlerts.toString()
                );
            }

        } catch (Exception e) {
            android.util.Log.e(
                "Translogic",
                "Record comparison failed",
                e
            );
        }
    }

    private void openWhatsAppBusiness(String message) {

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.setPackage("com.whatsapp.w4b");
        intent.putExtra(Intent.EXTRA_TEXT, message);

        try {
            startActivity(intent);

        } catch (Exception e) {

            sendNotification(
                "WhatsApp Business unavailable",
                "Please check that WhatsApp Business is installed."
            );

            android.util.Log.e(
                "Translogic",
                "Could not open WhatsApp Business",
                e
            );
        }
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                new NotificationChannel(
                    CHANNEL_ID,
                    "Translogic train updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                );

            channel.setDescription(
                "Alerts for new trains and train changes"
            );

            NotificationManager manager =
                getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    private void sendNotification(String title, String message) {

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager manager =
            (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE
            );

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true);

        manager.notify(
            (title + message).hashCode(),
            builder.build()
        );
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        pageReady = false;

        refreshHandler.removeCallbacks(refreshRunnable);

        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
