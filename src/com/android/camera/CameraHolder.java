/*
 * Copyright (C) 2009 The Android Open Source Project
 *               2015 The CyanogenMod Project
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

package com.android.camera;

import android.content.Context;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.camera.CameraManager.CameraProxy;
import com.android.camera.app.CameraApp;

import org.fusion.sdcam.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import static com.android.camera.util.CameraUtil.Assert;

/**
 * The class is used to hold an {@code android.hardware.Camera} instance.
 * <p>
 * <p>The {@code open()} and {@code release()} calls are similar to the ones
 * in {@code android.hardware.Camera}. The difference is if {@code keep()} is
 * called before {@code release()}, CameraHolder will try to hold the {@code
 * android.hardware.Camera} instance for a while, so if {@code open()} is
 * called soon after, we can avoid the cost of {@code open()} in {@code
 * android.hardware.Camera}.
 * <p>
 * <p>This is used in switching between different modules.
 */
public class CameraHolder {
    private static final String TAG = "CameraHolder";
    private static final int KEEP_CAMERA_TIMEOUT = 3000; // 3 seconds
    /* Debug double-open issue */
    private static final boolean DEBUG_OPEN_RELEASE = true;
    private static final int RELEASE_CAMERA = 1;
    private static CameraProxy mMockCamera[];
    private static CameraInfo mMockCameraInfo[];
    private static ArrayList<OpenReleaseState> sOpenReleaseStates =
            new ArrayList<>();
    private static SimpleDateFormat sDateFormat = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS");
    // Use a singleton.
    private static CameraHolder sHolder;
    private final Handler mHandler;
    private final int mNumberOfCameras;
    private final CameraInfo[] mInfo;
    private CameraProxy mCameraDevice;
    private long mKeepBeforeTime;  // Keep the Camera before this time.
    private boolean mCameraOpened;  // true if camera is opened
    private int mCameraId = -1;  // current camera id
    private int mBackCameraId = -1;
    private int mFrontCameraId = -1;
    // We store the camera parameters when we actually open the device,
    // so we can restore them in the subsequent open() requests by the user.
    // This prevents the parameters set by PhotoModule used by VideoModule
    // inadvertently.
    private Parameters mParameters;

    private CameraHolder() {
        HandlerThread ht = new HandlerThread("CameraHolder");
        ht.start();
        mHandler = new MyHandler(ht.getLooper());
        if (mMockCameraInfo != null) {
            mNumberOfCameras = mMockCameraInfo.length;
            mInfo = mMockCameraInfo;
        } else {
            mNumberOfCameras = android.hardware.Camera.getNumberOfCameras();
            mInfo = new CameraInfo[mNumberOfCameras];
            for (int i = 0; i < mNumberOfCameras; i++) {
                mInfo[i] = new CameraInfo();
                android.hardware.Camera.getCameraInfo(i, mInfo[i]);
            }
        }

        // get the first (smallest) back and first front camera id
        for (int i = 0; i < mNumberOfCameras; i++) {
            if (mBackCameraId == -1 && mInfo[i].facing == CameraInfo.CAMERA_FACING_BACK) {
                mBackCameraId = i;
            } else if (mFrontCameraId == -1 && mInfo[i].facing == CameraInfo.CAMERA_FACING_FRONT) {
                mFrontCameraId = i;
            }
        }
    }

    private static synchronized void collectState(int id, CameraProxy device) {
        OpenReleaseState s = new OpenReleaseState();
        s.time = System.currentTimeMillis();
        s.id = id;
        if (device == null) {
            s.device = "(null)";
        } else {
            s.device = device.toString();
        }

        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String[] lines = new String[stack.length];
        for (int i = 0; i < stack.length; i++) {
            lines[i] = stack[i].toString();
        }
        s.stack = lines;

        if (sOpenReleaseStates.size() > 10) {
            sOpenReleaseStates.remove(0);
        }
        sOpenReleaseStates.add(s);
    }

    private static synchronized void dumpStates() {
        for (int i = sOpenReleaseStates.size() - 1; i >= 0; i--) {
            OpenReleaseState s = sOpenReleaseStates.get(i);
            String date = sDateFormat.format(new Date(s.time));
            Log.d(TAG, "State " + i + " at " + date);
            Log.d(TAG, "mCameraId = " + s.id + ", mCameraDevice = " + s.device);
            Log.d(TAG, "Stack:");
            for (int j = 0; j < s.stack.length; j++) {
                Log.d(TAG, "  " + s.stack[j]);
            }
        }
    }

    public static synchronized CameraHolder instance() {
        if (sHolder == null) {
            sHolder = new CameraHolder();
        }
        return sHolder;
    }

    public static void injectMockCamera(CameraInfo[] info, CameraProxy[] camera) {
        mMockCameraInfo = info;
        mMockCamera = camera;
        sHolder = new CameraHolder();
    }

    public int getNumberOfCameras() {
        return mNumberOfCameras;
    }

    public CameraInfo[] getCameraInfo() {
        return mInfo;
    }

    public synchronized CameraProxy open(
            Handler handler, int cameraId,
            CameraManager.CameraOpenErrorCallback cb) {

        Context context = CameraApp.getContext();

        if (DEBUG_OPEN_RELEASE) {
            collectState(cameraId, mCameraDevice);
            if (mCameraOpened) {
                Log.e(TAG, "double open");
                dumpStates();
            }
        }
        Assert(!mCameraOpened);
        if (mCameraDevice != null && mCameraId != cameraId) {
            mCameraDevice.release();
            mCameraDevice = null;
            mCameraId = -1;
        }
        if (mCameraDevice == null) {
            Log.v(TAG, "open camera " + cameraId);
            if (mMockCameraInfo == null) {
                mCameraDevice = CameraManagerFactory
                        .getAndroidCameraManager().cameraOpen(handler, cameraId, cb);
            } else {
                if (mMockCamera != null) {
                    mCameraDevice = mMockCamera[cameraId];
                } else {
                    Log.e(TAG, "MockCameraInfo found, but no MockCamera provided.");
                    mCameraDevice = null;
                }
            }
            if (mCameraDevice == null) {
                Log.e(TAG, "fail to connect Camera:" + mCameraId + ", aborting.");
                return null;
            }
            mCameraId = cameraId;
            mParameters = mCameraDevice.getCamera().getParameters();

            // Manufacturer specific key values
            String manufacturerKeyValues = context.getResources().getString(R.string.manufacturer_key_values);
            if (manufacturerKeyValues != null && !manufacturerKeyValues.isEmpty()) {
                String[] keyValuesArray = manufacturerKeyValues.split(";");
                for (String kvPair : keyValuesArray) {
                    String[] manufacturerParamPair = kvPair.split("=");
                    if (!manufacturerParamPair[0].isEmpty() && !manufacturerParamPair[1].isEmpty()) {
                        Log.d(TAG, "Set manufacturer specific parameter " + manufacturerParamPair[0] + "=" + manufacturerParamPair[1]);
                        mParameters.set(manufacturerParamPair[0], manufacturerParamPair[1]);
                    }
                }
                mCameraDevice.setParameters(mParameters);
            }

        } else {
            if (!mCameraDevice.reconnect(handler, cb)) {
                Log.e(TAG, "fail to reconnect Camera:" + mCameraId + ", aborting.");
                return null;
            }
            mCameraDevice.setParameters(mParameters);
        }
        mCameraOpened = true;
        mHandler.removeMessages(RELEASE_CAMERA);
        mKeepBeforeTime = 0;
        return mCameraDevice;
    }

    /**
     * Tries to open the hardware camera. If the camera is being used or
     * unavailable then return {@code null}.
     */
    public synchronized CameraProxy tryOpen(
            Handler handler, int cameraId, CameraManager.CameraOpenErrorCallback cb) {
        return (!mCameraOpened ? open(handler, cameraId, cb) : null);
    }

    public synchronized void release() {
        if (DEBUG_OPEN_RELEASE) {
            collectState(mCameraId, mCameraDevice);
        }

        if (mCameraDevice == null) return;

        long now = System.currentTimeMillis();
        if (now < mKeepBeforeTime) {
            if (mCameraOpened) {
                mCameraOpened = false;
                mCameraDevice.stopPreview();
            }
            mHandler.sendEmptyMessageDelayed(RELEASE_CAMERA,
                    mKeepBeforeTime - now);
            return;
        }
        strongRelease();
    }

    public synchronized void strongRelease() {
        if (mCameraDevice == null) return;

        mCameraOpened = false;
        mCameraDevice.release();
        mCameraDevice = null;
        // We must set this to null because it has a reference to Camera.
        // Camera has references to the listeners.
        mParameters = null;
        mCameraId = -1;
    }

    public void keep() {
        keep(KEEP_CAMERA_TIMEOUT);
    }

    public synchronized void keep(int time) {
        // We allow mCameraOpened in either state for the convenience of the
        // calling activity. The activity may not have a chance to call open()
        // before the user switches to another activity.
        mKeepBeforeTime = System.currentTimeMillis() + time;
    }

    public int getBackCameraId() {
        return mBackCameraId;
    }

    public int getFrontCameraId() {
        return mFrontCameraId;
    }

    private static class OpenReleaseState {
        long time;
        int id;
        String device;
        String[] stack;
    }

    private class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case RELEASE_CAMERA:
                    synchronized (CameraHolder.this) {
                        // In 'CameraHolder.open', the 'RELEASE_CAMERA' message
                        // will be removed if it is found in the queue. However,
                        // there is a chance that this message has been handled
                        // before being removed. So, we need to add a check
                        // here:
                        if (!mCameraOpened) release();
                    }
                    break;
            }
        }
    }
}
