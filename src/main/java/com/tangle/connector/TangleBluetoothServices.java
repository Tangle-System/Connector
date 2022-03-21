package com.tangle.connector;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public class TangleBluetoothServices extends Service {
    private final String TAG = TangleBluetoothServices.class.getName();
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTED = 1;

    public static final int COMMUNICATION_TYPE_DELIVER = 0;
    public static final int COMMUNICATION_TYPE_TRANSMIT = 1;
    public static final int COMMUNICATION_TYPE_REQUEST = 2;
    public static final int COMMUNICATION_TYPE_CLOCK = 3;
    public static final int COMMUNICATION_TYPE_UPDATE_FIRMWARE = 4;

    //=====// CHARACTERISTIC_COMMUNICATION_CONSTANTS //=====//
    public static final int CLOCK_READ_RESOLVE = 0;
    public static final int CLOCK_READ_REJECT = 1;
    public static final int CLOCK_WROTE_RESOLVE = 2;
    public static final int CLOCK_WROTE_REJECT = 3;

    public static final int REQUEST_READ_RESOLVE = 4;
    public static final int REQUEST_READ_REJECT = 5;
    public static final int REQUEST_WROTE_RESOLVE = 6;
    public static final int REQUEST_WROTE_REJECT = 7;

    public static final int DELIVER_WROTE_RESOLVE = 8;
    public static final int DELIVER_WROTE_REJECT = 9;

    public static final int TRANSMIT_WROTE_RESOLVE = 10;
    public static final int TRANSMIT_WROTE_REJECT = 11;

    public static final int UPDATE_FIRMWARE_RESOLVE = 12;
    public static final int UPDATE_FIRMWARE_REJECT = 13;

    public static final int CHARACTERISTIC_NOTIFICATION = 14;


    private final String deviceMacAddress;
    private final asyncWriteThread mAsyncWriteReadThread;

    private volatile boolean isDataSent = true;
    private boolean requested = false;
    private boolean otaUpdateFailed = false;
    private boolean otaUpdateEnd = false;
    private int communicationType;
    private int writtenUpdate;
    private float updateProgress;

    public static final UUID TANGLE_SERVICE_UUID = UUID.fromString("cc540e31-80be-44af-b64a-5d2def886bf5");
    private final UUID TERMINAL_CHAR_UUID = UUID.fromString("33a0937e-0c61-41ea-b770-007ade2c79fa");
    private final UUID CLOCK_CHAR_UUID = UUID.fromString("7a1e0e3a-6b9b-49ef-b9b7-65c81b714a19");
    private final UUID DEVICE_CHAR_UUID = UUID.fromString("9ebe2e4b-10c7-4a81-ac83-49540d1135a5");

    private BluetoothGatt mBluetoothGatt;
    private int connectionState = STATE_DISCONNECTED;
    private ChangeStateListener changeStateListener;
    private OTAUpdateProgressListener otaUpdateProgressListener;
    private CharacteristicCommunicationListener characteristicCommunicationListener;
    private final Context mContext;

    public TangleBluetoothServices(String deviceMacAddress, Context context) {
        this.deviceMacAddress = deviceMacAddress;
        this.mContext = context;

        mAsyncWriteReadThread = new asyncWriteThread();
        mAsyncWriteReadThread.start();
    }

    // --- CONNECTION STATE LISTENER --- //

    public void setChangeStateListener(ChangeStateListener listener) {
        this.changeStateListener = listener;
    }

    public interface ChangeStateListener {
        void onChangeState(int connectionState);
    }

    public void setConnectionState(int connectionState) {
        this.connectionState = connectionState;
        if (changeStateListener != null) changeStateListener.onChangeState(connectionState);
    }

    public int getConnectionState() {
        return connectionState;
    }

    // --- OTA UPDATE LISTENER --- //

    public void setOTAUpdateProgressListener(OTAUpdateProgressListener listener) {
        this.otaUpdateProgressListener = listener;
    }

    public interface OTAUpdateProgressListener {
        void onOTAUpdateProgressChange(float updateProgress);
    }

    // --- CHARACTERISTIC COMMUNICATION LISTENER --- //

    public void setCharacteristicCommunicationListener(CharacteristicCommunicationListener listener) {
        this.characteristicCommunicationListener = listener;
    }

    public interface CharacteristicCommunicationListener {
        void onCharacteristicCommunicationMassage(byte[] readBytes, int communicationType);
    }

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    Log.d(TAG, "onConnectionStateChange: Connected to GATT server.");
                    Log.d(TAG, "onConnectionStateChange: Attempting to start service discovery:" + mBluetoothGatt.discoverServices());
                    break;

                case BluetoothProfile.STATE_DISCONNECTED:
                    setConnectionState(STATE_DISCONNECTED);
                    Log.d(TAG, "onConnectionStateChange: Disconnected from GATT server.");
                    mBluetoothGatt.close();
                    mBluetoothGatt = null;
                    break;
            }
        }

        // New services discovered
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.setCharacteristicNotification(gatt.getService(TANGLE_SERVICE_UUID).getCharacteristic(TERMINAL_CHAR_UUID), true);
                gatt.requestMtu(517);

                isDataSent = false;
                setConnectionState(STATE_CONNECTED);

            } else {
                Log.w(TAG, "onServicesDiscovered: " + status);
            }
        }

        // Result of a characteristic read operation
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            synchronized (mAsyncWriteReadThread) {
                isDataSent = true;
                requested = false;
//                Log.d(TAG, "onCharacteristicRead: mAsyncWriteReadThread: notify");
                mAsyncWriteReadThread.notify();
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.getUuid().toString().equals(CLOCK_CHAR_UUID.toString())) {
                    characteristicCommunicationListener.onCharacteristicCommunicationMassage(characteristic.getValue(), CLOCK_READ_RESOLVE);
                } else if (characteristic.getUuid().toString().equals(DEVICE_CHAR_UUID.toString())) {
                    characteristicCommunicationListener.onCharacteristicCommunicationMassage(characteristic.getValue(), REQUEST_READ_RESOLVE);
                }
            } else {
                switch (communicationType) {
                    case COMMUNICATION_TYPE_CLOCK:
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], CLOCK_READ_REJECT);
                        break;
                    case COMMUNICATION_TYPE_REQUEST:
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], REQUEST_READ_REJECT);
                        break;
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            final byte[] data = characteristic.getValue();
            if (status == BluetoothGatt.GATT_SUCCESS && data != null) {
                synchronized (mAsyncWriteReadThread) {
                    isDataSent = true;
//                    Log.d(TAG, "onCharacteristicWrite: mAsyncWriteReadThread: notify");
                    mAsyncWriteReadThread.notify();
                }
                Log.d(TAG, "Wrote bytes: " + Functions.logBytes(data));
                if (!requested) {
                    if (characteristic.getUuid().equals(CLOCK_CHAR_UUID)) {
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], CLOCK_WROTE_RESOLVE);
                    } else if (characteristic.getUuid().toString().equals(TERMINAL_CHAR_UUID.toString())) {
                        switch (communicationType) {
                            case COMMUNICATION_TYPE_DELIVER:
                                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], DELIVER_WROTE_RESOLVE);
                                break;
                            case COMMUNICATION_TYPE_TRANSMIT:
                                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], TRANSMIT_WROTE_RESOLVE);
                                break;
                        }
                    } else if (characteristic.getUuid().toString().equals(DEVICE_CHAR_UUID.toString())) {
                        switch (communicationType) {
                            case COMMUNICATION_TYPE_REQUEST:
                                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], REQUEST_WROTE_RESOLVE);
                                break;
                            case COMMUNICATION_TYPE_UPDATE_FIRMWARE:
                                if (otaUpdateEnd) {
                                    characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], UPDATE_FIRMWARE_RESOLVE);
                                }
                                break;
                        }
                    }
                }
            } else {
                switch (communicationType) {
                    case COMMUNICATION_TYPE_DELIVER:
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], DELIVER_WROTE_REJECT);
                        break;
                    case COMMUNICATION_TYPE_TRANSMIT:
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], TRANSMIT_WROTE_REJECT);
                        break;
                    case COMMUNICATION_TYPE_REQUEST:
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], REQUEST_WROTE_REJECT);
                        break;
                    case COMMUNICATION_TYPE_CLOCK:
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], CLOCK_WROTE_REJECT);
                        break;
                    case COMMUNICATION_TYPE_UPDATE_FIRMWARE:
                        otaUpdateFailed = true;
                        characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], UPDATE_FIRMWARE_REJECT);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            final byte[] notifiedData = characteristic.getValue();

            characteristicCommunicationListener.onCharacteristicCommunicationMassage(notifiedData, CHARACTERISTIC_NOTIFICATION);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            synchronized (mAsyncWriteReadThread) {
                isDataSent = true;
//                Log.d(TAG, "onMtuChanged: mAsyncWriteReadThread: notify");
                mAsyncWriteReadThread.notify();
            }
            Log.d(TAG, "onMtuChanged: " + mtu);
        }


    };

    public boolean connect() {
        BluetoothAdapter bluetoothAdapter;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null || deviceMacAddress == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        } else {
            try {
                final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceMacAddress);

                mBluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (IllegalArgumentException exception) {
                Log.w(TAG, "Device not found with provided address.");
                return false;
            }
        }
        return  true;
    }

    public void deliver(byte[] command_payload) {
        communicationType = COMMUNICATION_TYPE_DELIVER;
        mAsyncWriteReadThread.mHandler.post(() -> {
            if (!isDataSent) {
                pauseThread();
            }
            try {
                writeBytes(TERMINAL_CHAR_UUID, command_payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } catch (Exception e) {
                Log.d(TAG, "deliver: failed with: " + e);
                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], DELIVER_WROTE_REJECT);
            }
        });
    }

    public void transmit(byte[] command_payload) {
        communicationType = COMMUNICATION_TYPE_TRANSMIT;
        mAsyncWriteReadThread.mHandler.postAtFrontOfQueue(() -> {
            if (!isDataSent) {
                pauseThread();
            }
            try {
                writeBytes(TERMINAL_CHAR_UUID, command_payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } catch (Exception e) {
                Log.d(TAG, "transmit: failed with: " + e);
                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], TRANSMIT_WROTE_REJECT);
            }
        });
    }

    public void request(byte[] command_payload, boolean read_response) {
        communicationType = COMMUNICATION_TYPE_REQUEST;
        mAsyncWriteReadThread.mHandler.post(() -> {
            if (!isDataSent) {
                pauseThread();
            }

            requested = read_response;
            try {
                writeBytes(DEVICE_CHAR_UUID, command_payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } catch (Exception e) {
                Log.d(TAG, "request: failed with: " + e);
                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], REQUEST_WROTE_REJECT);
                return;
            }
            if (!read_response) {
                return;
            }
            if (!isDataSent) {
                pauseThread();
            }
            readBytes(DEVICE_CHAR_UUID);
        });
    }

    public void setClock(byte[] clock) {
        communicationType = COMMUNICATION_TYPE_CLOCK;
        mAsyncWriteReadThread.mHandler.post(() -> {
            if (!isDataSent) {
                pauseThread();
            }
            try {
                writeClock(clock);
            } catch (Exception e) {
                Log.d(TAG, "setClock: failed with: " + e);
                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], CLOCK_WROTE_REJECT);
            }
        });
    }

    public void getClock() {
        communicationType = COMMUNICATION_TYPE_CLOCK;
        mAsyncWriteReadThread.mHandler.post(() -> {
            if (!isDataSent) {
                pauseThread();
            }
            readBytes(CLOCK_CHAR_UUID);
        });

    }

    public void updateFirmware(byte[] firmware) {
        final int FLAG_OTA_BEGIN = 255;
        final int FLAG_OTA_WRITE = 0;
        final int FLAG_OTA_END = 254;
        final int FLAG_OTA_RESET = 253;

        int data_size = 4992; // must by modulo 16
        otaUpdateFailed = false;
        otaUpdateEnd = false;

        Log.d(TAG, "writeFirmware: OTA UPDATE");
        Log.d(TAG, "writeFirmware: firmware");
        communicationType = COMMUNICATION_TYPE_UPDATE_FIRMWARE;
        otaUpdateProgressListener.onOTAUpdateProgressChange(-1); // ota_status = begin

        //===========// RESET //===========//
        mAsyncWriteReadThread.mHandler.post(() -> {
            Log.d(TAG, "writeFirmware: OTA RESET");

            if (!isDataSent) {
                pauseThread();
            }
            if (otaUpdateFailed) {
                return;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                outputStream.write(FLAG_OTA_RESET);
                outputStream.write(0x00);
                outputStream.write(Functions.integerToBytes(0x00000000, 4));
            } catch (IOException e) {
                Log.e(TAG, "" + e);
            }
            byte[] bytes = outputStream.toByteArray();

            try {
                writeBytes(DEVICE_CHAR_UUID, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } catch (Exception e) {
                Log.d(TAG, "updateFirmware: failed");
                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], UPDATE_FIRMWARE_REJECT);
                otaUpdateFailed = true;
            }
        });

        //===========// BEGIN //===========//
        mAsyncWriteReadThread.mHandler.post(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "writeFirmware: OTA BEGIN");

            if (!isDataSent) {
                pauseThread();
            }
            if (otaUpdateFailed) {
                return;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                outputStream.write(FLAG_OTA_BEGIN);
                outputStream.write(0x00);
                outputStream.write(Functions.integerToBytes(firmware.length, 4));
            } catch (Exception e) {
                Log.e(TAG, "" + e);
            }
            byte[] bytes = outputStream.toByteArray();

            try {
                writeBytes(DEVICE_CHAR_UUID, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } catch (Exception e) {
                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], UPDATE_FIRMWARE_REJECT);
                otaUpdateFailed = true;
            }
        });

        //===========// WRITE //===========//
        mAsyncWriteReadThread.mHandler.post(() -> {
            int indexFrom = 0;
            int indexTo = data_size;

            writtenUpdate = 0;

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.d(TAG, "writeFirmware: OTA WRITE");


            while (writtenUpdate < firmware.length) {
                if (indexTo > firmware.length) {
                    indexTo = firmware.length;
                }

                if (!isDataSent) {
                    pauseThread();
                }
                if (otaUpdateFailed) {
                    return;
                }

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                try {
                    outputStream.write(FLAG_OTA_WRITE);
                    outputStream.write(0x00);
                    outputStream.write(Functions.integerToBytes(writtenUpdate, 4));
                    outputStream.write(Arrays.copyOfRange(firmware, indexFrom, indexTo));
                } catch (Exception e) {
                    Log.e(TAG, "" + e);
                }
                byte[] bytes = outputStream.toByteArray();

                try {
                    writeBytes(DEVICE_CHAR_UUID, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                } catch (Exception e) {
                    Log.d(TAG, "updateFirmware: failed");
                    characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], UPDATE_FIRMWARE_REJECT);
                    otaUpdateFailed = true;
                    return;
                }
                writtenUpdate += indexTo - indexFrom;
                updateProgress = (((float) writtenUpdate) / firmware.length) * 100;
                if (otaUpdateProgressListener != null)
                    otaUpdateProgressListener.onOTAUpdateProgressChange(updateProgress);

                Log.d(TAG, "writeFirmware: " + updateProgress + "%");

                indexFrom += data_size;
                indexTo = indexFrom + data_size;
            }
        });

        //===========// END //===========//
        mAsyncWriteReadThread.mHandler.post(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "writeFirmware: OTA END");

            if (!isDataSent) {
                pauseThread();
            }
            if (otaUpdateFailed) {
                return;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                outputStream.write(FLAG_OTA_END);
                outputStream.write(0x00);
                outputStream.write(Functions.integerToBytes(writtenUpdate, 4));
            } catch (Exception e) {
                Log.e(TAG, "" + e);
            }
            byte[] bytes = outputStream.toByteArray();

            try {
                otaUpdateEnd = true;
                writeBytes(DEVICE_CHAR_UUID, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } catch (Exception e) {
                Log.d(TAG, "updateFirmware: failed");
                characteristicCommunicationListener.onCharacteristicCommunicationMassage(new byte[0], UPDATE_FIRMWARE_REJECT);
                otaUpdateFailed = true;
            }
        });
    }

    private void writeClock(byte[] clock) throws Exception {
        BluetoothGattCharacteristic characteristic = mBluetoothGatt.getService(TANGLE_SERVICE_UUID).getCharacteristic(CLOCK_CHAR_UUID);
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

        if (!isDataSent) {
            pauseThread();
        }
        isDataSent = false;

        Log.d(TAG, "syncClock: Tray write: " + Functions.logBytes(clock));
        try {
            characteristic.setValue(clock);
            mBluetoothGatt.writeCharacteristic(characteristic);
        } catch (Exception e) {
            isDataSent = true;
            Log.d(TAG, "syncClock: Was not wrote");
            throw e;
        }
    }

    private void writeBytes(UUID characteristicUUID, byte[] payload, int writeType) throws Exception {

        long payloadUuid = (long) (Math.random() * Long.decode("0xffffffff"));
        int packetSize = 512;
        int bytesSize = packetSize - 12;

        int indexFrom = 0;
        int indexTo = bytesSize;

        BluetoothGattCharacteristic characteristic = mBluetoothGatt.getService(TANGLE_SERVICE_UUID).getCharacteristic(characteristicUUID);
        characteristic.setWriteType(writeType);

        while (indexFrom < payload.length) {
            if (indexTo > payload.length) {
                indexTo = payload.length;
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                outputStream.write(Functions.longToBytes(payloadUuid, 4));
                outputStream.write(Functions.longToBytes(indexFrom, 4));
                outputStream.write(Functions.longToBytes(payload.length, 4));
                outputStream.write(Arrays.copyOfRange(payload, indexFrom, indexTo));
            } catch (Exception e) {
                Log.e(TAG, "" + e);
            }
            byte[] bytes = outputStream.toByteArray();

            if (!isDataSent) {
                pauseThread();
            }
            isDataSent = false;

            Log.d(TAG, "writeBytes: Tray write: " + Functions.logBytes(bytes));
            try {
                characteristic.setValue(bytes);
                mBluetoothGatt.writeCharacteristic(characteristic);
            } catch (Exception e) {
                Log.e(TAG, "writeBytes: Value was not wrote");
                isDataSent = true;
                throw e;
            }

            indexFrom += bytesSize;
            indexTo = indexFrom + bytesSize;
        }
    }

    private void readBytes(UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = mBluetoothGatt.getService(TANGLE_SERVICE_UUID).getCharacteristic(characteristicUUID);
        Log.d(TAG, "readBytes: characteristic: " + characteristicUUID);
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    private static class asyncWriteThread extends Thread {
        public Handler mHandler;

        @Override
        public void run() {
            super.run();
            Looper.prepare();
            mHandler = new Handler();
            Looper.loop();
        }
    }

    private void pauseThread() {
        synchronized (mAsyncWriteReadThread) {
            try {
//                Log.d(TAG, "mAsyncWriteReadThread: paused");
                mAsyncWriteReadThread.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void disconnect() {
        Log.d(TAG, "Call close");
        if (mBluetoothGatt == null) {
            setConnectionState(STATE_DISCONNECTED);
            return;
        }

        if (connectionState == STATE_CONNECTED) {
            mBluetoothGatt.disconnect();
        } else {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}