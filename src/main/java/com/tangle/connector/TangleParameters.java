package com.tangle.connector;

import android.os.Parcel;
import android.os.Parcelable;

import com.airbnb.lottie.parser.IntegerParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TangleParameters implements Parcelable {
    private final static String TAG = TangleParameters.class.getName();

    private String name = "";
    private String namePrefix = "";
    private String ownerSignature = "";
    private String fwVersion = "";
    private String macAddress = "";
    private int productCode;
    private boolean adoptionFlag;

    private ByteArrayOutputStream manufactureDataFilter;

    private ByteArrayOutputStream manufactureDataMask;

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
    }

    private void compileManufactureData() throws IOException {
        manufactureDataFilter = new ByteArrayOutputStream();
        manufactureDataMask = new ByteArrayOutputStream();

        Compiler.compileFWVersion(fwVersion, manufactureDataFilter, manufactureDataMask);
        Compiler.compileProductCode(productCode, manufactureDataFilter, manufactureDataMask);
        Compiler.compileOwnerSignatureKey(ownerSignature, manufactureDataFilter, manufactureDataMask);
        Compiler.compileAdoptionFlag(adoptionFlag, manufactureDataFilter, manufactureDataMask);
    }

    public void parseManufactureData(byte[] manufactureData) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(manufactureData, 0, 2);
        fwVersion = Compiler.parseFwVersion(buffer.toByteArray());
        buffer.reset();
        buffer.write(manufactureData, 2, 2);
        productCode = Compiler.parseProductCode(buffer.toByteArray());
        buffer.reset();
        buffer.write(manufactureData, 4, 16);
        ownerSignature = Compiler.parseOwnerSignatureKey(buffer.toByteArray());
        adoptionFlag = Compiler.parseAdoptingFlag(manufactureData[20]);

    }

    public byte[] getManufactureDataFilter() throws IOException {
        if (manufactureDataFilter == null) {
            compileManufactureData();
        }
        return manufactureDataFilter.toByteArray();
    }

    public byte[] getManufactureDataMask() throws IOException {
        if (manufactureDataMask == null) {
            compileManufactureData();
        }
        return manufactureDataMask.toByteArray();
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

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getNamePrefix() {
        return namePrefix;
    }

    public void setNamePrefix(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    public String getOwnerSignature() {
        return ownerSignature;
    }

    public void setOwnerSignature(String ownerSignature) {
        this.ownerSignature = ownerSignature;
    }

    public String getFwVersion() {
        return fwVersion;
    }

    public void setFwVersion(String fwVersion) {
        this.fwVersion = fwVersion;
    }

    public int getProductCode() {
        return productCode;
    }

    public void setProductCode(int productCode) {
        this.productCode = productCode;
    }

    public boolean isAdoptionFlag() {
        return adoptionFlag;

    }

    public void setAdoptionFlag(boolean adoptionFlag) {
        this.adoptionFlag = adoptionFlag;
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
    }

    public static final Creator<TangleParameters> CREATOR = new Creator<TangleParameters>() {
        @Override
        public TangleParameters createFromParcel(Parcel in) {
            return new TangleParameters(in);
        }

        @Override
        public TangleParameters[] newArray(int size) {
            return new TangleParameters[size];
        }
    };


    private static class Compiler {

        // 0-2 verze firmwaru
        // 2-4 product code
        // 4-20 owner signature
        // 21 adoptionFlag flas 0 = neadoptuje 1 = adotptuje

        private static void compileFWVersion(String fwVersion, ByteArrayOutputStream manufactureDataFilter, ByteArrayOutputStream manufactureDataMask) throws IOException {

            if (fwVersion != null) {
                Pattern p = Pattern.compile("(!?)([\\d]?).([\\d]+).([\\d]+)");
                Matcher m = p.matcher(fwVersion);

                if (m.find()) {
                    if (Objects.requireNonNull(m.group(1)).equals("!")) {
                        //TODO: compile fww version reverse filter ( vyfiltruj vsechnz verze kromne te co je vzbrana)
                    }

                    int versionCode = 0;
                    versionCode = groupToInt(m.group(2)) * 10000;
                    versionCode += groupToInt(m.group(3)) * 100;
                    versionCode += groupToInt(m.group(4));
                    manufactureDataFilter.write(Functions.integerToBytes(versionCode, 2));
                    manufactureDataMask.write(Functions.integerToBytes(0xffff, 2));
                } else {
                    manufactureDataFilter.write(Functions.integerToBytes(0, 2));
                    manufactureDataMask.write(Functions.integerToBytes(0, 2));
                }
            } else {
                manufactureDataFilter.write(Functions.integerToBytes(0, 2));
                manufactureDataMask.write(Functions.integerToBytes(0, 2));
            }
        }

        private static void compileProductCode(int productCode, ByteArrayOutputStream manufactureDataFilter, ByteArrayOutputStream manufactureDataMask) throws IOException {
            //Compile Product Code
            if (productCode != 0) {
                manufactureDataFilter.write(Functions.integerToBytes(productCode, 2));
                manufactureDataMask.write(Functions.integerToBytes(0xffff, 2));
            } else {
                manufactureDataFilter.write(Functions.integerToBytes(0, 2));
                manufactureDataMask.write(Functions.integerToBytes(0, 2));
            }
        }

        private static void compileOwnerSignatureKey(String ownerSignature, ByteArrayOutputStream manufactureDataFilter, ByteArrayOutputStream manufactureDataMask) throws IOException {
            if (ownerSignature != null) {
                if (ownerSignature.length() == 32) {
                    for (int i = 0; i < 32; i = i + 2) {
                        manufactureDataFilter.write(Integer.parseInt(ownerSignature.substring(i, i + 2), 16));
                    }
                    for (int i = 0; i < 16; i++) {
                        manufactureDataMask.write((byte) 0xff);
                    }
                } else {
                    manufactureDataFilter.write(Functions.integerToBytes(0, 16));
                    manufactureDataMask.write(Functions.integerToBytes(0, 16));
                }
            } else {
                manufactureDataFilter.write(Functions.integerToBytes(0, 16));
                manufactureDataMask.write(Functions.integerToBytes(0, 16));

            }
        }

        private static void compileAdoptionFlag(boolean adoptionFlag, ByteArrayOutputStream manufactureDataFilter, ByteArrayOutputStream manufactureDataMask) {
            manufactureDataFilter.write(adoptionFlag ? 1 : 0);
            manufactureDataMask.write(0xff);
        }

        private static int groupToInt(String group) {
            if (group == null) {
                return 0;
            }
            if (group.equals("")) {
                return 0;
            } else return Integer.parseInt(group);
        }

        private static String parseFwVersion(byte[] bytes) {
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

        private static int parseProductCode(byte[] bytes) {
            int productCode = 0;

            ByteBuffer bb = ByteBuffer.wrap(bytes);
            bb = bb.order(ByteOrder.LITTLE_ENDIAN);
            while (bb.hasRemaining()) {
                short s = bb.getShort();
                productCode = 0xFFFF & s;
            }
            return productCode;
        }

        private static String parseOwnerSignatureKey(byte[] bytes) {
            StringBuilder ownerSignatureKey = new StringBuilder();
            for (byte b : bytes) {
                ownerSignatureKey.append(String.format("%02x", b));
            }
            return ownerSignatureKey.toString();
        }

        private static boolean parseAdoptingFlag(byte b) {
            return b == 1;
        }
    }
}
