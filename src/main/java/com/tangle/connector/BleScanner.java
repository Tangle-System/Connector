package com.tangle.connector;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class BleScanner {
    private static final String TAG = BleScanner.class.getName();

    private final BluetoothLeScanner bluetoothLeScanner;
    private final Handler handler = new Handler();
    private ScanCallback leScanCallback;
    NearestDeviceListener nearestDeviceListener;
    private ScanSettings.Builder settingsBuilder;
    private ArrayList<ScanFilter> filters;

    private ArrayList<ScanResult> results;
    private boolean scanning;

    public BleScanner() {
        bluetoothLeScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
    }


    private void setFilter(String criteriaJson) {
        settingsBuilder = new ScanSettings.Builder();
        settingsBuilder.setScanMode(ScanSettings.SCAN_MODE_BALANCED);
        filters = new ArrayList<>();

        if (criteriaJson.equals("[]") || criteriaJson.equals("")) {
            ScanFilter.Builder scanFilterBuilder = new ScanFilter.Builder();
            scanFilterBuilder.setServiceUuid(new ParcelUuid(TangleBluetoothServices.TANGLE_SERVICE_UUID), ParcelUuid.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
            filters.add(scanFilterBuilder.build());
        } else {
            Gson gson = new Gson();
            Type type1 = new TypeToken<TangleParameters[]>() {
            }.getType();
            TangleParameters[] criteria = gson.fromJson(criteriaJson, type1);

            for (TangleParameters criterion : criteria) {
                if (!criterion.isLegacy()) {
                    criterion.getManufactureDataFilters(filters);
                }
            }

        }
    }

    public void scanLeDevice(String tangleParameters, int scan_period) {
        setFilter(tangleParameters);
        results = new ArrayList<>();
        // Device scan callback.
        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                Log.d(TAG, "CallBack result: " + result);
                results.add(result);
            }
        };

        if (!scanning) {
            // Stops scanning after a pre-defined scan period.
            handler.postDelayed(() -> {
                scanning = false;
                bluetoothLeScanner.stopScan(leScanCallback);

                results.sort((lhs, rhs) -> Integer.compare(rhs.getRssi(), lhs.getRssi()));
                if (!results.isEmpty()) {
                    nearestDeviceListener.nearestDeviceChange(results.get(0));
                } else {
                    nearestDeviceListener.nearestDeviceChange(null);
                }

            }, scan_period);

            settingsBuilder = new ScanSettings.Builder();
            settingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES);

            scanning = true;
            Log.d(TAG, "scanLeDevice: Start scanning.");
            bluetoothLeScanner.startScan(filters, settingsBuilder.build(), leScanCallback);

        } else {
            scanning = false;
            Log.d(TAG, "scanLeDevice: Stop scanning.");
            bluetoothLeScanner.stopScan(leScanCallback);
        }
    }

    public ScanResult stopScan() {
        bluetoothLeScanner.stopScan(leScanCallback);
        results.sort((lhs, rhs) -> Integer.compare(rhs.getRssi(), lhs.getRssi()));
        if (results.isEmpty()) {
            return null;
        } else {
            return results.get(0);
        }
    }

    public void nearestDeviceListener(NearestDeviceListener listener) {
        this.nearestDeviceListener = listener;
    }

    public interface NearestDeviceListener {
        void nearestDeviceChange(ScanResult nearestDevice);
    }
}
