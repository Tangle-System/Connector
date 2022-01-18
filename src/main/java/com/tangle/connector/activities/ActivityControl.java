package com.tangle.connector.activities;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.tangle.connector.BleScanner;
import com.tangle.connector.Functions;
import com.tangle.connector.R;
import com.tangle.connector.TangleAndroidConnector;
import com.tangle.connector.TangleParameters;

public class ActivityControl extends AppCompatActivity {

    private static final String TAG = ActivityControl.class.getName();
    public static final String USER_SELECT_CANCELED_SELECTION = "userSelect -> reject('UserCanceledSelection')";
    public static final String USER_SELECT_FAILED = "userSelect -> reject('SelectionFailed')";
    public static final String USER_SELECT_RESOLVE = "userSelect -> resolve(tangleParameters)";
    public static final String COMMUNICATION_REJECT = "Connector reject()";

    private WebView webView;
    private FloatingActionButton buttonDefaultUrl;
    private ConstraintLayout layoutActivityControl;

    private TangleAndroidConnector connector;
    private BroadcastReceiver broadcastReceiver;
    private SharedPreferences mSharedPref;

    private String macAddress = "";
    private String tangleParametersJson = "";
    private String defaultWebUrl;
    private String webURL;
    private boolean connecting = false;
    private boolean disconnecting = false;
    private boolean found = false;
    private boolean autoSelectStopped = true;
    private boolean readResponse = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        layoutActivityControl = findViewById(R.id.layout_activity_control);

        Intent intent = getIntent();
        defaultWebUrl = intent.getStringExtra("defaultWebUrl");

        mSharedPref = getSharedPreferences("webURL", MODE_PRIVATE);
        webURL = mSharedPref.getString("webURL", defaultWebUrl);

        setWebView();
        setBroadcastReceiver();
        setButtonDefaultUrl();
        //TODO: defaultní webovku dostane activita skrz intent extra.
        //TODO: pokud se ve webview otevře jiná stránka než defaultní, tak se zobrazí tlačítko zpět.
    }

    private void setButtonDefaultUrl() {
        buttonDefaultUrl = findViewById(R.id.button_default_url);

        buttonDefaultUrl.setOnClickListener(v -> {
            webView.loadUrl(defaultWebUrl);
        });
    }

    private void setWebView() {
        webView = findViewById(R.id.webView);

        webView.setWebViewClient(new WebViewClient());
        WebSettings webSettings = webView.getSettings();

        webSettings.setAppCachePath(getApplicationContext().getCacheDir().getAbsolutePath());
        webSettings.setAllowFileAccess(true);
        webSettings.setAppCacheEnabled(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT); // load online by default

        webView.setWebContentsDebuggingEnabled(true); // pouze pro debugovani
        webView.addJavascriptInterface(new JavascriptHandler(this), "tangleConnect");

        if (!isNetworkAvailable()) { // loading offline
            webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        }

        webView.loadUrl(webURL);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                TransitionManager.beginDelayedTransition(layoutActivityControl);
                if (!url.equals(defaultWebUrl)) {
                    buttonDefaultUrl.setVisibility(View.VISIBLE);
                } else
                    buttonDefaultUrl.setVisibility(View.GONE);
            }
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void bluetoothConnect() {
        if (!connector.connect()) {
            sendReject("ConnectionFailed");
            connecting = false;
            return;
        }
        bluetoothService();

        connector.setOTAUpdateProgressListener(updateProgress -> {
            runOnUiThread(() -> {
                Log.d(TAG, "onOtaUpdateProgress: " + updateProgress);
                webView.loadUrl("window.tangleConnect.emit('ota_progress', " + updateProgress + ");");
            });
        });

        connector.setCharacteristicCommunicationListener((bytes, characteristic) -> {
            switch (characteristic) {
                case TangleAndroidConnector.CLOCK_READ_RESOLVE:
                case TangleAndroidConnector.REQUEST_READ_RESOLVE:
                    sendResolve(bytes);
                    break;
                case TangleAndroidConnector.REQUEST_WROTE_RESOLVE:
                    if (readResponse) {
                        sendResolve();
                    }
                    break;
                case TangleAndroidConnector.CLOCK_WROTE_RESOLVE:
                case TangleAndroidConnector.DELIVER_WROTE_RESOLVE:
                case TangleAndroidConnector.TRANSMIT_WROTE_RESOLVE:
                    sendResolve();
                    break;
                case TangleAndroidConnector.CLOCK_READ_REJECT:
                    sendReject("ClockReadFailed");
                    break;
                case TangleAndroidConnector.CLOCK_WROTE_REJECT:
                    sendReject("ClockWriteFailed");
                    break;
                case TangleAndroidConnector.REQUEST_READ_REJECT:
                case TangleAndroidConnector.REQUEST_WROTE_REJECT:
                    sendReject("RequestFailed");
                    break;
                case TangleAndroidConnector.DELIVER_WROTE_REJECT:
                    sendReject("DeliverFailed");
                    break;
                case TangleAndroidConnector.TRANSMIT_WROTE_REJECT:
                    sendReject("TransmitFailed");
                    break;
            }
        });
    }

    private void sendResolveNull() {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.resolve(null);");
            webView.loadUrl("javascript:window.tangleConnect.resolve(null);");
        });
    }

    private void sendResolve() {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.resolve();");
            webView.loadUrl("javascript:window.tangleConnect.resolve();");
        });
    }

    private void sendResolve(String data) {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.resolve('" + data.replaceAll("'", "\\'") + "');");
            webView.loadUrl("javascript:window.tangleConnect.resolve('" + data.replaceAll("'", "\\'") + "');");
        });

    }

    private void sendResolve(int data) {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.resolve(" + data + ");");
            webView.loadUrl("javascript:window.tangleConnect.resolve(" + data + ");");
        });
    }

    private void sendResolve(byte[] data) {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.resolve(" + Functions.logBytes(data) + ");");
            webView.loadUrl("javascript:window.tangleConnect.resolve(" + Functions.logBytes(data) + ");");
        });
    }

    private void sendReject() {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.reject();");
            webView.loadUrl("javascript:window.tangleConnect.reject();");
        });
    }

    private void sendReject(String data) {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.reject('" + data.replaceAll("'", "\\'") + "');");
            webView.loadUrl("javascript:window.tangleConnect.reject('" + data.replaceAll("'", "\\'") + "');");
        });

    }

    private void sendReject(int data) {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.reject(" + data + ");");
            webView.loadUrl("javascript:window.tangleConnect.reject(" + data + ");");
        });
    }

    private void sendReject(byte[] data) {
        runOnUiThread(() -> {
            Log.d(TAG, "javascript:window.tangleConnect.reject(" + Functions.logBytes(data) + ");");
            webView.loadUrl("javascript:window.tangleConnect.reject(" + Functions.logBytes(data) + ");");
        });
    }

    private void setBroadcastReceiver() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case USER_SELECT_RESOLVE:
                        macAddress = intent.getStringExtra("macAddress");
                        connector = new TangleAndroidConnector(macAddress, getApplicationContext());
                        TangleParameters tangleParameters = intent.getParcelableExtra("tangleParameters");
                        Gson gson = new Gson();
                        tangleParametersJson = gson.toJson(tangleParameters);
                        sendResolve(tangleParametersJson);
                        break;
                    case USER_SELECT_CANCELED_SELECTION:
                        sendReject("UserCanceledSelection");
                        break;
                    case USER_SELECT_FAILED:
                        sendReject("SelectionFailed");
                    case COMMUNICATION_REJECT:
                        String massage = intent.getStringExtra("massage");
                        sendReject(massage);
                        break;
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter(USER_SELECT_RESOLVE));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter(USER_SELECT_CANCELED_SELECTION));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter(USER_SELECT_FAILED));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter(COMMUNICATION_REJECT));
    }


    private void bluetoothService() {
        connector.setChangeStateListener(connectionState -> {
            runOnUiThread(() -> {
                switch (connectionState) {
                    case TangleAndroidConnector.STATE_DISCONNECTED:
                        Log.d(TAG, "bluetoothService: Disconnected");
                        if (connecting) {
                            sendReject("ConnectionFailed");
                            connecting = false;
                        } else if (disconnecting) {
                            webView.loadUrl("javascript:window.tangleConnect.emit('#disconnected');");
                            sendResolve();
                            disconnecting = false;
                        } else {
                            webView.loadUrl("javascript:window.tangleConnect.emit('#disconnected');");
                        }
                        break;
                    case TangleAndroidConnector.STATE_CONNECTED:
                        Log.d(TAG, "bluetoothService: Connected");
                        webView.loadUrl("javascript:window.tangleConnect.emit('#connected');");
                        if (connecting) {
                            connecting = false;
                            sendResolve();
                        }
                        break;
                }
            });
        });
    }

    private void autoSelectResolve(ScanResult nearestDevice) {
        if (nearestDevice != null) {
            //Set name
            TangleParameters nearestTangleParameters = new TangleParameters();
            nearestTangleParameters.setName(nearestDevice.getScanRecord().getDeviceName());

            //Get manufactureData
            nearestTangleParameters.parseManufactureData(nearestDevice.getScanRecord().getManufacturerSpecificData(0x02e5));

            Gson gson = new Gson();
            macAddress = nearestDevice.getDevice().getAddress();
            tangleParametersJson = gson.toJson(nearestTangleParameters);
            connector = new TangleAndroidConnector(macAddress, getApplicationContext());
            sendResolve(tangleParametersJson);
        } else {
            sendReject("SelectionFailed");
        }
    }

    public class JavascriptHandler {
        private final String TAG = JavascriptHandler.class.getName();
        Context mContext;

        public JavascriptHandler(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void userSelect(String criteria) {
            Log.d(TAG, "userSelect: " + criteria);

            if (criteria != null) {
                Intent intent = new Intent(getApplicationContext(), ActivityUserSelect.class);
                intent.putExtra("manufactureDataCriteria", criteria);
                startActivity(intent);
            } else {
                sendReject("SelectionFailed");
            }
        }

        @JavascriptInterface
        public void userSelect(String criteria, int timeout) {
            Log.d(TAG, "userSelect: " + criteria);

            if (criteria != null) {
                Intent intent = new Intent(getApplicationContext(), ActivityUserSelect.class);
                intent.putExtra("manufactureDataCriteria", criteria);
                intent.putExtra("timeout", timeout);
                startActivity(intent);
            } else {
                sendReject("SelectionFailed");
            }
        }

        @JavascriptInterface
        public void autoSelect(String criteria, int scan_period, int timeout) {
            Log.d(TAG, "autoSelect: " + criteria);
            found = false;
            autoSelectStopped = false;

            BleScanner bleScanner = new BleScanner();
            bleScanner.scanLeDevice(criteria, scan_period);
            new Thread(() -> {
                try {
                    Log.d(TAG, "autoSelect: Thread: sleep: " + timeout);
                    Thread.sleep(timeout);
                    Log.d(TAG, "autoSelect: Thread: weak: ");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (!found) {
                    autoSelectResolve(bleScanner.stopScan());
                    autoSelectStopped = true;
                }
            }).start();

            bleScanner.nearestDeviceListener(nearestDevice -> {
                if (nearestDevice == null && !autoSelectStopped) {
                    bleScanner.scanLeDevice(criteria, scan_period);
                } else if (!autoSelectStopped) {
                    autoSelectResolve(nearestDevice);
                    found = true;
                }
            });

        }

        @JavascriptInterface
        public void selected() {
            Log.d(TAG, "selected: ");
            if (!macAddress.equals("")) {
                sendResolve(tangleParametersJson);
            } else {
                sendResolveNull();
            }
        }

        @JavascriptInterface
        public void unselect() {
            Log.d(TAG, "unselect: ");
            macAddress = "";
            tangleParametersJson = "";

            if (connector != null) {
                connector.disconnect();
                connector = null;
            }
            sendResolve();
        }

        @JavascriptInterface
        public void connect(int timeout) {
            Log.d(TAG, "connect: ");
            if (connector != null) {
                bluetoothConnect();
                connecting = true;
            } else {
                sendReject("DeviceNotSelected");
            }

            new Thread(() -> {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (connector.getConnectionState() == TangleAndroidConnector.STATE_DISCONNECTED) {
                    connector.disconnect();
                    sendReject("ConnectionFailed");
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            Log.d(TAG, "disconnect: ");
            if (connector != null && connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                disconnecting = true;
                connector.disconnect();
                return;
            }
            sendResolve();
        }

        @JavascriptInterface
        public void connected() {
            Log.d(TAG, "connected: ");
            if (connector != null && connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                sendResolve(tangleParametersJson);
            } else {
                sendResolveNull();
            }
        }

        @JavascriptInterface
        public void deliver(byte[] command_payload) {
            if (command_payload == null) {
                sendReject("DeliverFailed");
                return;
            }
            Log.d(TAG, "deliver: " + Functions.logBytes(command_payload));
            if (connector != null && connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                connector.deliver(command_payload);
            } else {
                sendReject("DeviceNotConnected");
            }
        }

        @JavascriptInterface
        public void transmit(byte[] command_payload) {
            if (command_payload == null) {
                sendReject("TransmitFailed");
                return;
            }
            Log.d(TAG, "transmit: " + Functions.logBytes(command_payload));
            if (connector != null && connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                connector.transmit(command_payload);
            } else {
                sendReject("DeviceNotConnected");
            }
        }

        @JavascriptInterface
        public void request(byte[] command_payload, boolean read_response) {
            if (command_payload == null) {
                sendReject("RequestFailed");
                return;
            }
            readResponse = read_response;
            Log.d(TAG, "request: " + Functions.logBytes(command_payload));
            if (connector != null && connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                connector.request(command_payload, read_response);
            } else {
                sendReject("DeviceNotConnected");
            }
        }

        @JavascriptInterface
        public void writeClock(byte[] timeStamp) {
            if (timeStamp == null) {
                sendReject("ClockWriteFailed");
                return;
            }

            Log.d(TAG, "writeClock: " + Functions.logBytes(timeStamp));
            if (connector != null && connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                connector.setClock(timeStamp);
            } else {
                sendReject("DeviceNotConnected");
            }

        }

        @JavascriptInterface
        public void readClock() {
            Log.d(TAG, "readClock: ");
            if (connector != null) {
                if (connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                    connector.getClock();
                } else {
                    sendReject("Device is not connected");
                }
            } else {
                sendReject("Device is not selected");
            }
        }

        @JavascriptInterface
        public void updateFirmware(byte[] firmware) {
            Log.d(TAG, "updateFirmware: ");
            if (connector != null) {
                if (connector.getConnectionState() == TangleAndroidConnector.STATE_CONNECTED) {
                    if (firmware != null) {
//                        connector.updateFirmware(firmware);
                    } else {
                        sendReject("Firmware null");
                    }
                } else {
                    sendReject("Device is not connected");
                }
            } else {
                sendReject("Device is not selected");
            }
        }
    }

    @Override
    protected void onStop() {

        mSharedPref.edit().putString("webURL", webView.getUrl()).apply();
        super.onStop();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    public void onBackPressed() {
        if (connector != null) {
            connector.disconnect();
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (connector != null) {
            connector.disconnect();
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        super.onDestroy();
    }
}