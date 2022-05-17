package com.spectoda.connector;

import android.bluetooth.le.ScanFilter;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TangleParameters implements Parcelable {
    private final String TAG = TangleParameters.class.getName();

    private String name = "";
    private String namePrefix = "";
    private String ownerSignature = "";
    private String fwVersion="";
    private String macAddress = "";
    private int productCode = -1;
    private boolean adoptionFlag = false;
    private boolean legacy = false;

    private byte[] manufactureDataFilter;
    private byte[] manufactureDataMask;

    private ScanFilter.Builder scanFilterBuilder;

    public TangleParameters() {
    }

    protected TangleParameters(Parcel in) {
        name = in.readString();
        namePrefix = in.readString();
        ownerSignature = in.readString();
        fwVersion = in.readString();
        macAddress = in.readString();
        productCode = in.readInt();
        adoptionFlag = in.readByte() != 0;
        legacy = in.readByte() != 0;
    }

    public void parseManufactureData(byte[] manufactureData) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(manufactureData, 0, 2);
        fwVersion = parseFwVersion(buffer.toByteArray());
        buffer.reset();
        buffer.write(manufactureData, 2, 2);
        productCode = parseProductCode(buffer.toByteArray());
        buffer.reset();
        buffer.write(manufactureData, 4, 16);
        ownerSignature = parseOwnerSignatureKey(buffer.toByteArray());
        adoptionFlag = parseAdoptingFlag(manufactureData[20]);
    }

    public void getManufactureDataFilters(ArrayList<ScanFilter> filters){

        manufactureDataFilter = new byte[21];
        manufactureDataMask = new byte[21];
        scanFilterBuilder = new ScanFilter.Builder();

        if(!name.equals("")){
            scanFilterBuilder.setDeviceName(name);
        }
        if (!macAddress.equals("")) {
            scanFilterBuilder.setDeviceAddress(macAddress);
        }
        compileProductCode();
        compileOwnerSignatureKey();
        compileAdoptionFlag();
        compileFWVersion(filters);

    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public boolean isLegacy() {
        return legacy;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(namePrefix);
        dest.writeString(ownerSignature);
        dest.writeString(fwVersion);
        dest.writeString(macAddress);
        dest.writeInt(productCode);
        dest.writeByte((byte) (adoptionFlag ? 1 : 0));
        dest.writeByte((byte) (legacy ? 1 : 0));
    }

    public static final Creator<TangleParameters> CREATOR = new Creator<>() {
        @Override
        public TangleParameters createFromParcel(Parcel in) {
            return new TangleParameters(in);
        }

        @Override
        public TangleParameters[] newArray(int size) {
            return new TangleParameters[size];
        }
    };

    // Filters compilation

    // 0-1 firmware version (2 bytes)
    // 2-3 product code (2 bytes)
    // 4-19 owner signature (16 bytes)
    // 20 adoptionFlag: false-0 = do not adopting; true-1 = adopting (1 byte)

    private void compileFWVersion(ArrayList<ScanFilter> filters) {
        final int byteOffset = 0;
        if (!fwVersion.equals("")) {
            Pattern p = Pattern.compile("(!?)([\\d]?).([\\d]+).([\\d]+)");
            Matcher m = p.matcher(fwVersion);

            if (m.find()) {
                int versionCode;
                versionCode = groupToInt(m.group(2)) * 10000;
                versionCode += groupToInt(m.group(3)) * 100;
                versionCode += groupToInt(m.group(4));

                if (Objects.requireNonNull(m.group(1)).equals("!")) {
                    // filter all device with different Fw version.
                    // we will generate 16 filters, each filtering one of the 16 bits that is different from my version.
                    // if the one bit is different, then the version of the found device is different than mine.

                    byte[] versionBytes = {(byte) (versionCode & 0xff), (byte) ((versionCode >> 8) & 0xff)};

                    // version is defined as 2 bytes
                    for (int i = 0; i < 2; i++) {
                        // each byte have 8 bits
                        for (int j = 0; j < 8; j++) {
                            // set bytes to zero
                            for (int k = 0; k < 2; k++) {
                                manufactureDataFilter[byteOffset + k] = 0;
                                manufactureDataMask[byteOffset + k] = 0;
                            }

                            manufactureDataFilter[byteOffset + i] = (byte) ~(versionBytes[i] & (1 << j));
                            manufactureDataMask[byteOffset + i] = (byte) (1 << j);
                            scanFilterBuilder.setManufacturerData(0x02e5, manufactureDataFilter, manufactureDataMask);
                            filters.add(scanFilterBuilder.build());
                        }
                    }
                    return;
                } else {
                    byte[] versionCodeBytes = Functions.integerToBytes(versionCode, 2);
                    for (int i = 0; i < 2; i++) {
                        manufactureDataFilter[byteOffset + i] = versionCodeBytes[i];
                        manufactureDataMask[byteOffset + i] = (byte) 0xff;
                    }
                }
            }
        }
        scanFilterBuilder.setManufacturerData(0x02e5, manufactureDataFilter, manufactureDataMask);
        filters.add(scanFilterBuilder.build());
    }

    private void compileProductCode() {
        final int byteOffset = 2;
        if (productCode < 0 || productCode > 0xffff) {
            Log.e(TAG, "compileProductCode: Invalid productCode");
        } else {
            byte[] productCodeBytes = Functions.integerToBytes(productCode, 2);
            for (int i = 0; i < 2; i++) {
                manufactureDataFilter[byteOffset + i] = productCodeBytes[i];
                manufactureDataMask[byteOffset + i] = (byte) 0xff;
            }
        }
    }

    private void compileOwnerSignatureKey() {
        final int byteOffset = 4;
        if (ownerSignature.length() == 32) {
            for (int i = 0; i < 32; i = i + 2) {
                manufactureDataFilter[byteOffset + i / 2] = (byte) (Integer.parseInt(ownerSignature.substring(i, i + 2), 16));
            }
            for (int i = 0; i < 16; i++) {
                manufactureDataMask[byteOffset + i] = (byte) 0xff;
            }
        } else if (!ownerSignature.equals("")) {
            Log.e(TAG, "compileOwnerSignatureKey: Invalid ownerSignature");
        }
    }

    private void compileAdoptionFlag() {
        final int byteOffset = 20;

        manufactureDataFilter[byteOffset] = (byte) (adoptionFlag ? 1 : 0);
        manufactureDataMask[byteOffset] = (byte) 0xff;
    }

    private int groupToInt(String group) {
        if (group == null) {
            return 0;
        }
        if (group.equals("")) {
            return 0;
        } else return Integer.parseInt(group);
    }

    private String parseFwVersion(byte[] bytes) {
        int fwVersionCode = 0;

        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb = bb.order(ByteOrder.LITTLE_ENDIAN);
        while (bb.hasRemaining()) {
            short s = bb.getShort();
            fwVersionCode = 0xFFFF & s;
        }
        int thousands = fwVersionCode / 1000;
        int hundreds = (fwVersionCode - thousands) / 100;
        int dozens = (fwVersionCode - thousands * 1000 - hundreds * 100);

        return "" + thousands + "." + hundreds + "." + dozens;
    }

    private int parseProductCode(byte[] bytes) {
        int productCode = 0;

        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb = bb.order(ByteOrder.LITTLE_ENDIAN);
        while (bb.hasRemaining()) {
            short s = bb.getShort();
            productCode = 0xFFFF & s;
        }
        return productCode;
    }

    private String parseOwnerSignatureKey(byte[] bytes) {
        StringBuilder ownerSignatureKey = new StringBuilder();
        for (byte b : bytes) {
            ownerSignatureKey.append(String.format("%02x", b));
        }
        return ownerSignatureKey.toString();
    }

    private boolean parseAdoptingFlag(byte b) {
        return b == 1;
    }
}

