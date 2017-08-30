/* Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.camera;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

public class SDCard {
    private static final String TAG = "SDCard";

    private static final int VOLUME_SDCARD_INDEX = 1;
    private static SDCard sSDCard;
    private StorageManager mStorageManager = null;
    private StorageVolume mVolume = null;
    private String mPath = null;
    private String mRawpath = null;
    private BroadcastReceiver mMediaBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            initVolume();
        }
    };

    private SDCard(Context context) {
        try {
            mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            scanVolumes();
        } catch (Exception e) {
            Log.e(TAG, "couldn't talk to MountService", e);
        }
    }

    public static void initialize(Context context) {
        if (sSDCard == null) {
            sSDCard = new SDCard(context);
        }
    }

    public static synchronized SDCard instance() {
        return sSDCard;
    }

    public boolean isWriteable() {
        if (mVolume == null) return false;
        final String state = getSDCardStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    public String getRoot() {
        if (mVolume == null) {
            return null;
        }
        return mVolume.getPath();
    }

    public String getDirectory() {
        if (mVolume == null) {
            return null;
        }
        if (mPath == null) {
            mPath = mVolume.getPath() + "/DCIM/Camera";
        }
        return mPath;
    }

    public String getRawDirectory() {
        if (mVolume == null) {
            return null;
        }
        if (mRawpath == null) {
            mRawpath = mVolume.getPath() + "/DCIM/Camera/raw";
        }
        return mRawpath;
    }

    @TargetApi(Build.VERSION_CODES.N)
    private String getSDCardStorageState() {
        return mVolume.getState();
    }

    public void scanVolumes() {
        if (mStorageManager != null) {
            final StorageVolume[] volumes = mStorageManager.getVolumeList();
            if (volumes.length > VOLUME_SDCARD_INDEX) {
                mVolume = volumes[VOLUME_SDCARD_INDEX];
                Log.i(TAG, "found SDCard volume");
            }
        } else {
            Log.e(TAG, "couldn't talk to MountService");
        }
    }

    private void initVolume() {
        final StorageVolume[] volumes = mStorageManager.getVolumeList();
        mVolume = (volumes.length > VOLUME_SDCARD_INDEX) ?
                volumes[VOLUME_SDCARD_INDEX] : null;
        mPath = null;
        mRawpath = null;
    }

    private void registerMediaBroadcastreceiver(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addDataScheme("file");
        context.registerReceiver(mMediaBroadcastReceiver, filter);
    }
}
