/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.Handler;
import android.util.SparseArray;
import android.util.SparseIntArray;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages HDMI-CEC command and behaviors. It converts user's command into CEC command
 * and pass it to CEC HAL so that it sends message to other device. For incoming
 * message it translates the message and delegates it to proper module.
 *
 * <p>It can be created only by {@link HdmiCecController#create}
 *
 * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
 */
final class HdmiCecController {
    private static final String TAG = "HdmiCecController";

    private static final byte[] EMPTY_BODY = EmptyArray.BYTE;

    // A message to pass cec send command to IO looper.
    private static final int MSG_SEND_CEC_COMMAND = 1;
    // A message to delegate logical allocation to IO looper.
    private static final int MSG_ALLOCATE_LOGICAL_ADDRESS = 2;

    // Message types to handle incoming message in main service looper.
    private final static int MSG_RECEIVE_CEC_COMMAND = 1;
    // A message to report allocated logical address to main control looper.
    private final static int MSG_REPORT_LOGICAL_ADDRESS = 2;

    private static final int NUM_LOGICAL_ADDRESS = 16;

    // TODO: define other constants for errors.
    private static final int ERROR_SUCCESS = 0;

    // Handler instance to process synchronous I/O (mainly send) message.
    private Handler mIoHandler;

    // Handler instance to process various messages coming from other CEC
    // device or issued by internal state change.
    private Handler mControlHandler;

    // Stores the pointer to the native implementation of the service that
    // interacts with HAL.
    private long mNativePtr;

    private HdmiControlService mService;

    // Map-like container of all cec devices. A logical address of device is
    // used as key of container.
    private final SparseArray<HdmiCecDeviceInfo> mDeviceInfos =
            new SparseArray<HdmiCecDeviceInfo>();
    // Set-like container for all local devices' logical address.
    // Key and value are same.
    private final SparseIntArray mLocalAddresses = new SparseIntArray();

    // Private constructor.  Use HdmiCecController.create().
    private HdmiCecController() {
        // TODO: Consider restoring the local device addresses from persistent storage
        //       to allocate the same addresses again if possible.
    }

    /**
     * A factory method to get {@link HdmiCecController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     * @param service {@link HdmiControlService} instance used to create internal handler
     *                and to pass callback for incoming message or event.
     * @return {@link HdmiCecController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiCecController create(HdmiControlService service) {
        HdmiCecController handler = new HdmiCecController();
        long nativePtr = nativeInit(handler);
        if (nativePtr == 0L) {
            handler = null;
            return null;
        }

        handler.init(service, nativePtr);
        return handler;
    }

    /**
     * Initialize {@link #mLocalAddresses} by allocating logical addresses for each hosted type.
     *
     * @param deviceTypes local device types
     */
    void initializeLocalDevices(int[] deviceTypes) {
        for (int deviceType : deviceTypes) {
            int preferred = getPreferredAddress(deviceType);
            allocateLogicalAddress(deviceType, preferred, new AllocateLogicalAddressCallback() {
                @Override
                public void onAllocated(int deviceType, int logicalAddress) {
                    addLogicalAddress(logicalAddress);
                }
            });
        }
    }

    /**
     * Get the preferred address for a given type.
     *
     * @param deviceType logical device type to get the address for
     * @return preferred address; {@link HdmiCec#ADDR_UNREGISTERED} if not available.
     */
    private int getPreferredAddress(int deviceType) {
        // Uses the data restored from persistent memory at boot up if they are available.
        // Otherwise we return UNREGISTERED indicating there is no preferred address.
        // Note that for address SPECIFIC_USE(14), HdmiCec.getTypeFromAddress() returns DEVICE_TV,
        // meaning that we do not support device type video processor yet.
        for (int i = 0; i < mLocalAddresses.size(); ++i) {
            int address = mLocalAddresses.keyAt(i);
            int type = HdmiCec.getTypeFromAddress(address);
            if (type == deviceType) {
                return address;
            }
        }
        return HdmiCec.ADDR_UNREGISTERED;
    }

    /**
     * Interface to report allocated logical address.
     */
    interface AllocateLogicalAddressCallback {
        /**
         * Called when a new logical address is allocated.
         *
         * @param deviceType requested device type to allocate logical address
         * @param logicalAddress allocated logical address. If it is
         *                       {@link HdmiCec#ADDR_UNREGISTERED}, it means that
         *                       it failed to allocate logical address for the given device type
         */
        void onAllocated(int deviceType, int logicalAddress);
    }

    /**
     * Allocate a new logical address of the given device type. Allocated
     * address will be reported through {@link AllocateLogicalAddressCallback}.
     *
     * <p> Declared as package-private, accessed by {@link HdmiControlService} only.
     *
     * @param deviceType type of device to used to determine logical address
     * @param preferredAddress a logical address preferred to be allocated.
     *                         If sets {@link HdmiCec#ADDR_UNREGISTERED}, scans
     *                         the smallest logical address matched with the given device type.
     *                         Otherwise, scan address will start from {@code preferredAddress}
     * @param callback callback interface to report allocated logical address to caller
     */
    void allocateLogicalAddress(final int deviceType, final int preferredAddress,
            final AllocateLogicalAddressCallback callback) {
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                handleAllocateLogicalAddress(deviceType, preferredAddress, callback);
            }
        });
    }

    private void handleAllocateLogicalAddress(final int deviceType, int preferredAddress,
            final AllocateLogicalAddressCallback callback) {
        int startAddress = preferredAddress;
        // If preferred address is "unregistered", start address will be the smallest
        // address matched with the given device type.
        if (preferredAddress == HdmiCec.ADDR_UNREGISTERED) {
            for (int i = 0; i < NUM_LOGICAL_ADDRESS; ++i) {
                if (deviceType == HdmiCec.getTypeFromAddress(i)) {
                    startAddress = i;
                    break;
                }
            }
        }

        int logicalAddress = HdmiCec.ADDR_UNREGISTERED;
        // Iterates all possible addresses which has the same device type.
        for (int i = 0; i < NUM_LOGICAL_ADDRESS; ++i) {
            int curAddress = (startAddress + i) % NUM_LOGICAL_ADDRESS;
            if (curAddress != HdmiCec.ADDR_UNREGISTERED
                    && deviceType == HdmiCec.getTypeFromAddress(i)) {
                // <Polling Message> is a message which has empty body and
                // uses same address for both source and destination address.
                // If sending <Polling Message> failed (NAK), it becomes
                // new logical address for the device because no device uses
                // it as logical address of the device.
                int error = nativeSendCecCommand(mNativePtr, curAddress, curAddress,
                        EMPTY_BODY);
                if (error != ERROR_SUCCESS) {
                    logicalAddress = curAddress;
                    break;
                }
            }
        }

        final int assignedAddress = logicalAddress;
        if (callback != null) {
            runOnServiceThread(new Runnable() {
                    @Override
                public void run() {
                    callback.onAllocated(deviceType, assignedAddress);
                }
            });
        }
    }

    private static byte[] buildBody(int opcode, byte[] params) {
        byte[] body = new byte[params.length + 1];
        body[0] = (byte) opcode;
        System.arraycopy(params, 0, body, 1, params.length);
        return body;
    }

    /**
     * Add a new {@link HdmiCecDeviceInfo}. It returns old device info which has the same
     * logical address as new device info's.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param deviceInfo a new {@link HdmiCecDeviceInfo} to be added.
     * @return {@code null} if it is new device. Otherwise, returns old {@HdmiCecDeviceInfo}
     *         that has the same logical address as new one has.
     */
    HdmiCecDeviceInfo addDeviceInfo(HdmiCecDeviceInfo deviceInfo) {
        HdmiCecDeviceInfo oldDeviceInfo = getDeviceInfo(deviceInfo.getLogicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getLogicalAddress());
        }
        mDeviceInfos.append(deviceInfo.getLogicalAddress(), deviceInfo);
        return oldDeviceInfo;
    }

    /**
     * Remove a device info corresponding to the given {@code logicalAddress}.
     * It returns removed {@link HdmiCecDeviceInfo} if exists.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param logicalAddress logical address of device to be removed
     * @return removed {@link HdmiCecDeviceInfo} it exists. Otherwise, returns {@code null}
     */
    HdmiCecDeviceInfo removeDeviceInfo(int logicalAddress) {
        HdmiCecDeviceInfo deviceInfo = mDeviceInfos.get(logicalAddress);
        if (deviceInfo != null) {
            mDeviceInfos.remove(logicalAddress);
        }
        return deviceInfo;
    }

    /**
     * Return a list of all {@HdmiCecDeviceInfo}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    List<HdmiCecDeviceInfo> getDeviceInfoList() {
        List<HdmiCecDeviceInfo> deviceInfoList = new ArrayList<HdmiCecDeviceInfo>(
                mDeviceInfos.size());
        for (int i = 0; i < mDeviceInfos.size(); ++i) {
            deviceInfoList.add(mDeviceInfos.valueAt(i));
        }
        return deviceInfoList;
    }

    /**
     * Return a {@link HdmiCecDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiCecDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    HdmiCecDeviceInfo getDeviceInfo(int logicalAddress) {
        return mDeviceInfos.get(logicalAddress);
    }

    /**
     * Add a new logical address to the device. Device's HW should be notified
     * when a new logical address is assigned to a device, so that it can accept
     * a command having available destinations.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param newLogicalAddress a logical address to be added
     * @return 0 on success. Otherwise, returns negative value
     */
    int addLogicalAddress(int newLogicalAddress) {
        if (HdmiCec.isValidAddress(newLogicalAddress)) {
            mLocalAddresses.put(newLogicalAddress, newLogicalAddress);
            return nativeAddLogicalAddress(mNativePtr, newLogicalAddress);
        } else {
            return -1;
        }
    }

    /**
     * Clear all logical addresses registered in the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    void clearLogicalAddress() {
        // TODO: consider to backup logical address so that new logical address
        // allocation can use it as preferred address.
        mLocalAddresses.clear();
        nativeClearLogicalAddress(mNativePtr);
    }

    /**
     * Return the physical address of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @return CEC physical address of the device. The range of success address
     *         is between 0x0000 and 0xFFFF. If failed it returns -1
     */
    int getPhysicalAddress() {
        return nativeGetPhysicalAddress(mNativePtr);
    }

    /**
     * Return CEC version of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    int getVersion() {
        return nativeGetVersion(mNativePtr);
    }

    /**
     * Return vendor id of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    int getVendorId() {
        return nativeGetVendorId(mNativePtr);
    }

    private void runOnIoThread(Runnable runnable) {
        mIoHandler.post(runnable);
    }

    private void runOnServiceThread(Runnable runnable) {
        mControlHandler.post(runnable);
    }

    private void init(HdmiControlService service, long nativePtr) {
        mService = service;
        mIoHandler = new Handler(service.getServiceLooper());
        mControlHandler = new Handler(service.getServiceLooper());
        mNativePtr = nativePtr;
    }

    private boolean isAcceptableAddress(int address) {
        // Can access command targeting devices available in local device or
        // broadcast command.
        return address == HdmiCec.ADDR_BROADCAST
                || mLocalAddresses.indexOfKey(address) < 0;
    }

    private void onReceiveCommand(HdmiCecMessage message) {
        if (isAcceptableAddress(message.getDestination()) &&
                mService.handleCecCommand(message)) {
            return;
        }

        // TODO: Use device's source address for broadcast message.
        int sourceAddress = message.getDestination() != HdmiCec.ADDR_BROADCAST ?
                message.getDestination() : 0;
        // Reply <Feature Abort> to initiator (source) for all requests.
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildFeatureAbortCommand
                (sourceAddress, message.getSource(), message.getOpcode(),
                        HdmiCecMessageBuilder.ABORT_REFUSED);
        sendCommand(cecMessage, null);
    }

    void sendCommand(final HdmiCecMessage cecMessage,
            final HdmiControlService.SendMessageCallback callback) {
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                byte[] body = buildBody(cecMessage.getOpcode(), cecMessage.getParams());
                final int error = nativeSendCecCommand(mNativePtr, cecMessage.getSource(),
                        cecMessage.getDestination(), body);
                if (callback != null) {
                    runOnServiceThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSendCompleted(error);
                        }
                    });
                }
            }
        });
    }

    /**
     * Called by native when incoming CEC message arrived.
     */
    private void handleIncomingCecCommand(int srcAddress, int dstAddress, byte[] body) {
        byte opcode = body[0];
        byte params[] = Arrays.copyOfRange(body, 1, body.length);
        final HdmiCecMessage cecMessage = new HdmiCecMessage(srcAddress, dstAddress, opcode, params);

        // Delegate message to main handler so that it handles in main thread.
        runOnServiceThread(new Runnable() {
            @Override
            public void run() {
                onReceiveCommand(cecMessage);
            }
        });
    }

    /**
     * Called by native when a hotplug event issues.
     */
    private void handleHotplug(boolean connected) {
        // TODO: once add port number to cec HAL interface, pass port number
        // to the service.
        mService.onHotplug(0, connected);
    }

    private static native long nativeInit(HdmiCecController handler);
    private static native int nativeSendCecCommand(long controllerPtr, int srcAddress,
            int dstAddress, byte[] body);
    private static native int nativeAddLogicalAddress(long controllerPtr, int logicalAddress);
    private static native void nativeClearLogicalAddress(long controllerPtr);
    private static native int nativeGetPhysicalAddress(long controllerPtr);
    private static native int nativeGetVersion(long controllerPtr);
    private static native int nativeGetVendorId(long controllerPtr);
}
