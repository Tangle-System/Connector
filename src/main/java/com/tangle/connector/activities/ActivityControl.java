package com.tangle.connector.activities;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.javiersantos.appupdater.AppUpdater;
import com.github.javiersantos.appupdater.enums.UpdateFrom;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.tangle.connector.BleScanner;
import com.tangle.connector.Functions;
import com.tangle.connector.R;
import com.tangle.connector.TangleBluetoothServices;
import com.tangle.connector.TangleParameters;

import java.util.Arrays;

public class ActivityControl extends AppCompatActivity {

    private static final String TAG = ActivityControl.class.getName();
    private static final int PERMISSION_REQUEST_FINE_LOCATION = 1;
    private static final int PERMISSION_REQUEST_BLUETOOTH = 2;
    public static final String USER_SELECT_CANCELED_SELECTION = "userSelect -> reject('UserCanceledSelection')";
    public static final String USER_SELECT_FAILED = "userSelect -> reject('SelectionFailed')";
    public static final String USER_SELECT_RESOLVE = "userSelect -> resolve(tangleParameters)";

    private WebView webView;
    private FloatingActionButton buttonHome;
    private ConstraintLayout layoutActivityControl;

    private TangleBluetoothServices connector;
    private BroadcastReceiver broadcastReceiver;
    private SharedPreferences mSharedPref;

    private String macAddress = "";
    private String tangleParametersJson = "";
    private String homeWebUrl;
    private String webURL;
    private boolean connecting = false;
    private boolean disconnecting = false;
    private boolean found = false;
    private boolean autoSelectStopped = true;
    private boolean readResponse = false;
    private boolean disableBackButton;
    private boolean fullScreenMode;
    private boolean hideHomeButton = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);
        layoutActivityControl = findViewById(R.id.layout_activity_control);
        setConnectorSpecifications();

        mSharedPref = getSharedPreferences("webURL", MODE_PRIVATE);
        webURL = mSharedPref.getString("webURL", homeWebUrl);

        if (permissionAccessFineLocationGuaranteed()) {
            if (permissionBluetoothConnectGuaranteed()) {
                setWebView();
                setBroadcastReceiver();
                setButtonHomeUrl();
            }
        }
    }

    private void setConnectorSpecifications() {
        Intent intent = getIntent();

        // Set default webUrl for webView
        homeWebUrl = intent.getStringExtra("homeWebUrl");

        // Set appUpdater and his url for checking new versions of application
        if (intent.getStringExtra("updaterUrl") != null) {
            setAppUpdater(intent.getStringExtra("updaterUrl"));
        }

        // Disable system back button functionality
        disableBackButton = intent.getBooleanExtra("disableBackButton", false);

        // Enable fullScreen mode. When is enabled keyboard do not work properly
        if (intent.getBooleanExtra("fullScreenMode", false)) {
            fullScreenMode = true;
            hideSystemUI();
            onChangeVisibility();
        }

        // Set screen orientation of ActivityControl
        this.setRequestedOrientation(intent.getIntExtra("screenOrientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
    }

    private boolean permissionAccessFineLocationGuaranteed() {
        if (this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (this.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.need_location_access)
                        .setMessage(R.string.please_allow_location_access)
                        .setPositiveButton(R.string.accept, (dialog, which) -> requestPermissions(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION
                        }, PERMISSION_REQUEST_FINE_LOCATION))
                        .setNegativeButton(R.string.deny, (dialog, which) -> finish())
                        .setOnCancelListener(dialog -> finish())
                        .show();
            } else {
                this.requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, PERMISSION_REQUEST_FINE_LOCATION);
            }
            return false;
        } else {
            return true;
        }
    }

    private boolean permissionBluetoothConnectGuaranteed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (this.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED || this.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                if (this.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(R.string.need_location_access)
                            .setMessage(R.string.please_allow_location_access)
                            .setPositiveButton(R.string.accept, (dialog, which) -> requestPermissions(new String[]{
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                            }, PERMISSION_REQUEST_BLUETOOTH))
                            .setNegativeButton(R.string.deny, (dialog, which) -> finish())
                            .setOnCancelListener(dialog -> finish())
                            .show();
                } else {
                    this.requestPermissions(new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    }, PERMISSION_REQUEST_BLUETOOTH);
                }
                return false;
            }
        }
        return true;
    }

    private void setButtonHomeUrl() {
        buttonHome = findViewById(R.id.button_home_url);
        buttonHome.setOnClickListener(v -> webView.loadUrl(homeWebUrl));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setWebView() {
        webView = findViewById(R.id.webView);

        webView.setBackgroundColor(Color.BLACK);
        WebSettings webSettings = webView.getSettings();

//        webSettings.setAppCacheEnabled(true);
//        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT); // load online by default
        webSettings.setAppCachePath(getApplicationContext().getCacheDir().getAbsolutePath());
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setSaveFormData(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setSupportZoom(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.setClickable(true);

        webView.setWebContentsDebuggingEnabled(true); // pouze pro debugovani
        webView.addJavascriptInterface(new JavascriptHandler(this), "tangleConnect");

//        if (!isNetworkAvailable()) { // loading offline
//            webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
//        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                new Thread(() -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // Show home button if website isn't the default webSite
                    if (!url.equals(homeWebUrl) & !hideHomeButton) {
                        runOnUiThread(() ->  {
                            TransitionManager.beginDelayedTransition(layoutActivityControl);
                            buttonHome.setVisibility(View.VISIBLE);
                        });
                    } else
                        runOnUiThread(() -> {
                            TransitionManager.beginDelayedTransition(layoutActivityControl);
                            buttonHome.setVisibility(View.GONE);
                        });
                }).start();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
//            public void onPermissionRequest(final PermissionRequest request) {
//                myRequest = request;
//
//                for (String permission : request.getResources()) {
//                    switch (permission) {
//                        case "android.webkit.resource.AUDIO_CAPTURE": {
//                            askForPermission(request.getOrigin().toString(), Manifest.permission.RECORD_AUDIO, MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
//                            break;
//                        }
//                    }
//                }
//            }
        });

        webView.loadUrl(webURL);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public void setAppUpdater(String url) {
        AppUpdater appUpdater = new AppUpdater(this);
        appUpdater.setUpdateFrom(UpdateFrom.JSON)
                .setUpdateJSON(url)
                .setTitleOnUpdateAvailable("New version available")
                .setContentOnUpdateAvailable("Please update the application to ensure that all functions work properly.")
                .setButtonUpdate("Update now")
                .setButtonDismiss("Later")
                .setButtonDoNotShowAgain("")
                .setCancelable(false)
                .start();
    }

    private void bluetoothConnect() {
        if (!connector.connect() && connecting) {
            bluetoothConnect();
        } else {
            setConnectorServices();
        }
    }

    private void setBroadcastReceiver() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case USER_SELECT_RESOLVE:
                        macAddress = intent.getStringExtra("macAddress");
                        connector = new TangleBluetoothServices(macAddress, getApplicationContext());
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
                        break;
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter(USER_SELECT_RESOLVE));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter(USER_SELECT_CANCELED_SELECTION));
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, new IntentFilter(USER_SELECT_FAILED));
    }


    private void setConnectorServices() {
        connector.setChangeStateListener(connectionState -> runOnUiThread(() -> {
            switch (connectionState) {
                case TangleBluetoothServices.STATE_DISCONNECTED:
                    Log.d(TAG, "bluetoothService: Disconnected");
                    if (connecting) { // App state connecting
                        sendReject("ConnectionFailed");
                        connecting = false;
                    } else if (disconnecting) { // App state disconnecting
                        webView.loadUrl("javascript:window.tangleConnect.emit('#disconnected');");
                        sendResolve();
                        disconnecting = false;
                    } else { // App state running
                        webView.loadUrl("javascript:window.tangleConnect.emit('#disconnected');");
                    }
                    break;
                case TangleBluetoothServices.STATE_CONNECTED:
                    Log.d(TAG, "bluetoothService: Connected");
                    webView.loadUrl("javascript:window.tangleConnect.emit('#connected');");
                    if (connecting) {
                        connecting = false;
                        sendResolve();
                    }
                    break;
            }
        }));

        connector.setOTAUpdateProgressListener(updateProgress -> {
            if (updateProgress == -1) {
                runOnUiThread(() -> webView.loadUrl("javascript:window.tangleConnect.emit('ota_status', 'begin');"));
            } else {
                Log.d(TAG, "onOtaUpdateProgress: " + updateProgress);
                runOnUiThread(() -> webView.loadUrl("javascript:window.tangleConnect.emit('ota_progress', " + updateProgress + ");"));
            }
        });

        connector.setCharacteristicCommunicationListener((bytes, communicationType) -> {
            switch (communicationType) {
                case TangleBluetoothServices.CLOCK_READ_RESOLVE:
                case TangleBluetoothServices.REQUEST_READ_RESOLVE:
                    sendResolve(bytes);
                    break;
                case TangleBluetoothServices.REQUEST_WROTE_RESOLVE:
                    if (!readResponse) {
                        sendResolve();
                    }
                    break;
                case TangleBluetoothServices.CLOCK_WROTE_RESOLVE:
                case TangleBluetoothServices.DELIVER_WROTE_RESOLVE:
                case TangleBluetoothServices.TRANSMIT_WROTE_RESOLVE:
                    sendResolve();
                    break;
                case TangleBluetoothServices.CLOCK_READ_REJECT:
                    sendReject("ClockReadFailed");
                    break;
                case TangleBluetoothServices.CLOCK_WROTE_REJECT:
                    sendReject("ClockWriteFailed");
                    break;
                case TangleBluetoothServices.REQUEST_READ_REJECT:
                case TangleBluetoothServices.REQUEST_WROTE_REJECT:
                    sendReject("RequestFailed");
                    break;
                case TangleBluetoothServices.DELIVER_WROTE_REJECT:
                    sendReject("DeliverFailed");
                    break;
                case TangleBluetoothServices.TRANSMIT_WROTE_REJECT:
                    sendReject("TransmitFailed");
                    break;
                case TangleBluetoothServices.UPDATE_FIRMWARE_RESOLVE:
                    runOnUiThread(() -> webView.loadUrl("javascript:window.tangleConnect.emit('ota_status', 'success');"));
                    sendResolve();
                    break;
                case TangleBluetoothServices.UPDATE_FIRMWARE_REJECT:
                    runOnUiThread(() -> webView.loadUrl("javascript:window.tangleConnect.emit('ota_status', 'fail');"));
                    sendReject("UpdateFailed");
                    break;
                case TangleBluetoothServices.CHARACTERISTIC_NOTIFICATION:
                    runOnUiThread(()-> webView.loadUrl("javascript:window.tangleConnect.emit('#bytecode', "+ Functions.logBytes(bytes) +");"));
            }
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
            connector = new TangleBluetoothServices(macAddress, getApplicationContext());
            sendResolve(tangleParametersJson);
        } else {
            sendReject("SelectionFailed");
        }
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

    private void hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        // Set the content to appear under the system bars so that the
                        // content doesn't resize when the system bars hide and show.
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        // Hide the nav bar and status bar
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

    }

    public void onChangeVisibility() {
        View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener(visibility -> {
            // Note that system bars will only be "visible" if none of the
            // LOW_PROFILE, HIDE_NAVIGATION, or FULLSCREEN flags are set.
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                Log.d(TAG, "hideSystemUI: visible");
                new Thread(() -> {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    runOnUiThread(this::hideSystemUI);
                }).start();
                // adjustments to your UI, such as showing the action bar or
                // other navigational controls.
            } else {
                Log.d(TAG, "hideSystemUI: invisible");
                // adjustments to your UI, such as hiding the action bar or
                // other navigational controls.
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullScreenMode) {
            hideSystemUI();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSION_REQUEST_FINE_LOCATION:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (permissionBluetoothConnectGuaranteed()) {
                        setWebView();
                        setBroadcastReceiver();
                        setButtonHomeUrl();
                    }

                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(R.string.limited_functionality)
                            .setMessage(R.string.cant_work_without_location_access)
                            .setPositiveButton(R.string.accept, (dialog, which) -> this.requestPermissions(new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION
                            }, PERMISSION_REQUEST_FINE_LOCATION))
                            .setOnCancelListener(dialog -> finish())
                            .show();
                }
                break;
            case PERMISSION_REQUEST_BLUETOOTH:
                if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) || grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (permissionAccessFineLocationGuaranteed()) {
                        setWebView();
                        setBroadcastReceiver();
                        setButtonHomeUrl();
                    }
                } else {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(R.string.limited_functionality)
                            .setMessage(R.string.cant_work_without_location_access)
                            .setPositiveButton(R.string.accept, (dialog, which) -> this.requestPermissions(new String[]{
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT
                            }, PERMISSION_REQUEST_BLUETOOTH))
                            .setOnCancelListener(dialog -> finish())
                            .show();
                }
                break;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mSharedPref.edit().putString("webURL", webView.getUrl()).apply();
    }

    @Override
    public void onBackPressed() {
        if (!disableBackButton) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                super.onBackPressed();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (connector != null) {
            connector.disconnect();
            connector = null;
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        super.onDestroy();
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
            if (connector != null) {
                if (connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                    connector.disconnect();
                }
            }

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
            if (connector != null) {
                if (connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                    connector.disconnect();
                }
            }

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
            if (connector != null) {
                connector.disconnect();
//                if (connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
//                    connector.disconnect();
//                }
            }

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
                if (connector.getConnectionState() == TangleBluetoothServices.STATE_DISCONNECTED) {
                    connector.disconnect();
                    sendReject("ConnectionFailed");
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            Log.d(TAG, "disconnect: ");
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                disconnecting = true;
                connector.disconnect();
            } else {
                sendResolve();
            }
        }

        @JavascriptInterface
        public void connected() {
            Log.d(TAG, "connected: ");
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
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
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                connector.deliver(command_payload);
            } else {
                sendReject("DeviceDisconnected");
            }
        }

        @JavascriptInterface
        public void transmit(byte[] command_payload) {
            if (command_payload == null) {
                sendReject("TransmitFailed");
                return;
            }
            Log.d(TAG, "transmit: " + Functions.logBytes(command_payload));
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                connector.transmit(command_payload);
            } else {
                sendReject("DeviceDisconnected");
            }
        }

        @JavascriptInterface
        public void request(byte[] command_payload, boolean read_response) {
            if (command_payload == null) {
                sendReject("RequestFailed");
                return;
            }
            Log.d(TAG, "request: " + Functions.logBytes(command_payload) + ", readResponse: " + read_response);

            readResponse = read_response;
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                connector.request(command_payload, read_response);
            } else {
                sendReject("DeviceDisconnected");
            }
        }

        @JavascriptInterface
        public void writeClock(byte[] timeStamp) {
            if (timeStamp == null) {
                sendReject("ClockWriteFailed");
                return;
            }

            Log.d(TAG, "writeClock: " + Functions.logBytes(timeStamp));
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                connector.setClock(timeStamp);
            } else {
                sendReject("DeviceDisconnected");
            }

        }

        @JavascriptInterface
        public void readClock() {
            Log.d(TAG, "readClock: ");
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                connector.getClock();
            } else {
                sendReject("DeviceDisconnected");
            }
        }

        @JavascriptInterface
        public void updateFW(byte[] firmware) {
            Log.d(TAG, "updateFirmware: ");
            if (connector != null && connector.getConnectionState() == TangleBluetoothServices.STATE_CONNECTED) {
                if (firmware != null) {
                    connector.updateFirmware(firmware);
                } else {
                    sendReject("FirmwareNull");
                }
            } else {
                sendReject("DeviceDisconnected");
            }
        }

        /**
         * Function open will open url, which is in parameter, in webView or other preferred application.
         *
         * @param url Web address which will open be open.
         **/
        @JavascriptInterface
        public void open(String url) {
            if (url != null) {
                Log.d(TAG, "open: " + url);
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            }
        }

        /**
         * Function will set the requested orientation according to value in parameter
         *
         * @param requestedOrientation There you can set screen orientation with {@link ActivityInfo} constants.
         **/
        @JavascriptInterface
        public void setRequestedOrientation(int requestedOrientation) {
            Log.d(TAG, "setRequestedOrientation: " + requestedOrientation);
            ActivityControl.this.setRequestedOrientation(requestedOrientation);
        }

        /**
         * Function which in webView load the default "home" website.
         **/
        @JavascriptInterface
        public void goHome() {
            Log.d(TAG, "goHome: ");
            webView.loadUrl(homeWebUrl);
        }

        /**
         * Function will hide home button on website which is not set as home website.
         *
         * @param hide Set true for hide home button and false for show. Default value is false.
         **/
        @JavascriptInterface
        public void hideHomeButton(boolean hide) {
            hideHomeButton = hide;
        }

    }
}