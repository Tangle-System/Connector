package com.tangle.connector.activities;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tangle.connector.R;
import com.tangle.connector.TangleAndroidConnector;
import com.tangle.connector.TangleParameters;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;

public class ActivityUserSelect extends AppCompatActivity {

    private static final String TAG = ActivityUserSelect.class.getName();
    private static final int PERMISSION_REQUEST_FINE_LOCATION = 1;
    private static final int MANUFACTURE_ID = 0x02e5;
    private static long SCAN_PERIOD = 5000;

    private ListView mListView;
    private TextView mTextView;
    private TextView informationText;
    private Button buttonBack;
    private Button buttonScanAgain;
    private ImageView imageViewBleScanCorrect;
    private LottieAnimationView getImageViewBleScanMain;
    private ConstraintLayout layoutBleScan;

    private BluetoothLeScanner bluetoothLeScanner;
    private ScanSettings.Builder settingsBuilder;
    private ArrayList<ScanFilter> filters;
    private TangleParameters tangleParameters;
    private PairedDeviceAdapter mAdapter;
    private final ArrayList<ScanResult> pairedDeviceList = new ArrayList<>();
    private final Handler handler = new Handler();
    private ScanCallback leScanCallback;

    private boolean scanning;
    private boolean selected = false;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_select);

        mListView = findViewById(R.id.list_view_devices);
        mTextView = findViewById(R.id.text_process);
        informationText = findViewById(R.id.informationText);
        buttonBack = findViewById(R.id.button_back);
        buttonScanAgain = findViewById(R.id.button_scan_again);
        imageViewBleScanCorrect = findViewById(R.id.imageView_ble_scan_correct);
        getImageViewBleScanMain = findViewById(R.id.imageView_ble_scan_mainImage);
        layoutBleScan = findViewById(R.id.layout_ble_scan);

        bluetoothLeScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();

        mAdapter = new PairedDeviceAdapter(getApplicationContext(), R.layout.item_device, R.id.deviceName, pairedDeviceList);
        mListView.setAdapter(mAdapter);

        setButtons();

        setFilter();
        scanLeDevice();
    }

    private void setFilter() {
        settingsBuilder = new ScanSettings.Builder();
        settingsBuilder.setScanMode(ScanSettings.SCAN_MODE_BALANCED);
        filters = new ArrayList<>();

        Intent intent = getIntent();
        String criteriaJson = intent.getStringExtra("manufactureDataCriteria");
        SCAN_PERIOD = intent.getIntExtra("timeout", 5000);

        if (criteriaJson.equals("[]") || criteriaJson.equals("")) {
            ScanFilter.Builder scanFilterBuilder = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(TangleAndroidConnector.TANGLE_SERVICE_UUID), ParcelUuid.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

            filters.add(scanFilterBuilder.build());
        } else {
            Gson gson = new Gson();
            Type type1 = new TypeToken<TangleParameters[]>() {
            }.getType();
            TangleParameters[] criteria = gson.fromJson(criteriaJson, type1);

            try {
                for (TangleParameters criterion : criteria) {
                    ScanFilter.Builder scanFilterBuilder = new ScanFilter.Builder()
                            .setManufacturerData(0x02e5, criterion.getManufactureDataFilter(), criterion.getManufactureDataMask());
                    if (!criterion.getName().equals("")) {
                        scanFilterBuilder.setDeviceName(criterion.getName());
                    }
                    if (!criterion.getMacAddress().equals("")){
                        scanFilterBuilder.setDeviceAddress(criterion.getMacAddress());
                    }
                    filters.add(scanFilterBuilder.build());
                }
            } catch (IOException e) {
                Log.d(TAG, "setFilter: Failed to compile manufactureDataFilter" + e);
            }
        }
    }

    private void scanLeDevice() {
        // Device scan callback.
        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                boolean isSame = false;
                Log.d(TAG, "CallBack result: " + result);
                Log.d(TAG, "onScanResult: " + result.getRssi() + " dB");

                if (mAdapter.getCount() != 0) {
                    for (int i = 0; i < mAdapter.getCount(); i++) {
                        if (mAdapter.getItem(i).getDevice().getAddress().equals(result.getDevice().getAddress())) {
                            mAdapter.set(i, result);
                            mAdapter.notifyDataSetChanged();
                            isSame = true;
                        }
                    }
                }
                if (!isSame) {
                    mAdapter.add(result);
                    mAdapter.notifyDataSetChanged();
                }
            }
        };

        if (!scanning) {
            // Stops scanning after a pre-defined scan period.
            handler.postDelayed(() -> {
                scanning = false;
                bluetoothLeScanner.stopScan(leScanCallback);

                isTangleAvailable();

            }, SCAN_PERIOD);

            scanning = true;
            Log.d(TAG, "scanLeDevice: Start scanning.");
            bluetoothLeScanner.startScan(filters, settingsBuilder.build(), leScanCallback);

        } else {
            scanning = false;
            Log.d(TAG, "scanLeDevice: Stop scanning.");
            bluetoothLeScanner.stopScan(leScanCallback);
        }

        pairDevice();
    }

    private void pairDevice() {
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            bluetoothLeScanner.stopScan(leScanCallback);

            ScanResult device = mAdapter.getItem(position);

            //Set name
            TangleParameters selectedTangleParameters = new TangleParameters();
            selectedTangleParameters.setName(device.getScanRecord().getDeviceName());

            //Get manufactureData
            selectedTangleParameters.parseManufactureData(device.getScanRecord().getManufacturerSpecificData(MANUFACTURE_ID));

            //Send intend and finis
            Intent intent = new Intent(getApplicationContext(), ActivityControl.class);
            intent.putExtra("macAddress", device.getDevice().getAddress().toString());
            intent.putExtra("tangleParameters", (Parcelable) selectedTangleParameters);
            intent.setAction(ActivityControl.USER_SELECT_RESOLVE);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

            selected = true;
            finish();

/*            TransitionManager.beginDelayedTransition(layoutBleScan);
            mTextView.setText("Zařízení xxxx bylo spárováno!");
            getImageViewBleScanMain.setAnimation(R.raw.ble_found_animation);
            getImageViewBleScanMain.setRepeatCount(0);
            getImageViewBleScanMain.playAnimation();
            mListView.setVisibility(View.INVISIBLE);
            mListView.setClickable(false);
            informationText.setVisibility(View.VISIBLE);
            buttonScanAgain.setText("Spárovat nové");
            buttonScanAgain.setVisibility(View.VISIBLE);
            buttonMyApps.setVisibility(View.VISIBLE);*/
        });
    }

    private void isTangleAvailable() {
        TransitionManager.beginDelayedTransition(layoutBleScan);
        if (mAdapter.myList.isEmpty()) {
            mTextView.setText(R.string.no_device_in_range);
            getImageViewBleScanMain.setAnimation(R.raw.ble_unavailable_animation);
            getImageViewBleScanMain.setRepeatCount(0);
            getImageViewBleScanMain.playAnimation();
            informationText.setVisibility(View.VISIBLE);
            buttonBack.setVisibility(View.VISIBLE);
            buttonScanAgain.setVisibility(View.VISIBLE);

        } else {
            mTextView.setVisibility(View.INVISIBLE);
            mTextView.setText(R.string.available_devices);
            imageViewBleScanCorrect.setVisibility(View.VISIBLE);
            getImageViewBleScanMain.setAnimation(R.raw.ble_found_animation);
            getImageViewBleScanMain.setRepeatCount(0);
            getImageViewBleScanMain.playAnimation();
            mTextView.setVisibility(View.VISIBLE);
        }
    }

    private void setButtons() {
        buttonBack.setOnClickListener(v -> {
            finish();
        });

        buttonScanAgain.setOnClickListener(v -> {
            scanLeDevice();
            TransitionManager.beginDelayedTransition(layoutBleScan);
            mTextView.setText(R.string.looking_for_devices);
            getImageViewBleScanMain.setAnimation(R.raw.ble_scan_animation);
            getImageViewBleScanMain.setRepeatCount(LottieDrawable.INFINITE);
            getImageViewBleScanMain.playAnimation();
            informationText.setVisibility(View.GONE);
            buttonScanAgain.setVisibility(View.GONE);
            mListView.setVisibility(View.VISIBLE);
            mListView.setClickable(true);
        });

    }

    public static class PairedDeviceAdapter extends ArrayAdapter<ScanResult> {

        private final Context context;
        private final ArrayList<ScanResult> myList;

        public PairedDeviceAdapter(Context context, int resource, int textViewResourceId, ArrayList<ScanResult> objects) {
            super(context, resource, textViewResourceId, objects);
            this.context = context;
            myList = objects;
        }

        @Override
        public int getCount() {
            return myList.size();
        }

        @Override
        public ScanResult getItem(int position) {
            return myList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }


        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View v = convertView;
            PairedDeviceAdapter.ViewHolder holder;
            if (convertView == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_device, null);
                holder = new ViewHolder();

                holder.name = v.findViewById(R.id.deviceName);
                holder.signalStrength = v.findViewById(R.id.deviceSignalStrength);

                v.setTag(holder);
            } else {
                holder = (PairedDeviceAdapter.ViewHolder) v.getTag();
            }

            ScanResult result = myList.get(position);
            holder.name.setText(result.getDevice().getName());

            signalStrengthSet(holder, result);

            return v;
        }

        private void signalStrengthSet(ViewHolder holder, ScanResult result) {
            int signalStrength = result.getRssi();
            if (signalStrength > -50) {
                holder.signalStrength.setImageResource(R.drawable.ic_bluetooth_signal_bar_4);
            } else if (signalStrength > -65) {
                holder.signalStrength.setImageResource(R.drawable.ic_bluetooth_signal_bar_3);
            } else if (signalStrength > -80) {
                holder.signalStrength.setImageResource(R.drawable.ic_bluetooth_signal_bar_2);
            } else {
                holder.signalStrength.setImageResource(R.drawable.ic_bluetooth_signal_bar_1);
            }
        }

        public void set(int i, ScanResult result) {
            myList.set(i, result);
        }

        private static class ViewHolder {
            TextView name;
            ImageView signalStrength;
        }
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent(getApplicationContext(), ActivityControl.class);
        intent.setAction(ActivityControl.USER_SELECT_CANCELED_SELECTION);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!scanning) {
            scanLeDevice();
        }
    }

    @Override
    protected void onPause() {
        bluetoothLeScanner.stopScan(leScanCallback);
        scanning = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        bluetoothLeScanner.stopScan(leScanCallback);
        scanning = false;

        if (!selected) {
            Intent intent = new Intent(getApplicationContext(), ActivityControl.class);
            intent.setAction(ActivityControl.USER_SELECT_CANCELED_SELECTION);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            finish();
        }
        super.onDestroy();
    }
}
