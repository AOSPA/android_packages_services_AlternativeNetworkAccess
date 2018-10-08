/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.ans;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.os.ServiceManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IAns;
import com.android.internal.telephony.TelephonyPermissions;

/**
 * AlternativeNetworkService implements ians.
 * It scans network and matches the results with opportunistic subscriptions.
 * Use the same to provide user opportunistic data in areas with corresponding networks
 */
public class AlternativeNetworkService extends Service {
    private Context mContext;
    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubsriptionManager;

    private final Object mLock = new Object();
    private boolean mIsEnabled;
    private ANSProfileSelector mProfileSelector;
    private ANSServiceStateEvaluator mServiceStateEvaluator;
    private SharedPreferences mSharedPref;

    private static final String TAG = "ANS";
    private static final String PREF_NAME = TAG;
    private static final String PREF_ENABLED = "isEnabled";
    private static final String SERVICE_NAME = "ians";
    private static final boolean DBG = true;

    /**
     * Profile selection callback. Will be called once Profile selector decides on
     * the opportunistic data profile.
     */
    private ANSProfileSelector.ANSProfileSelectionCallback  mProfileSelectionCallback =
            new ANSProfileSelector.ANSProfileSelectionCallback() {

                @Override
                public void onProfileSelectionDone(int dataSubId, int voiceSubId) {
                    logDebug("profile selection done");
                    mProfileSelector.stopProfileSelection();
                    mServiceStateEvaluator.startEvaluation(dataSubId, voiceSubId);
                }
            };

    /**
     * Service state evaluator callback. Will be called once service state evaluator thinks
     * that current opportunistic data is not providing good service.
     */
    private ANSServiceStateEvaluator.ANSServiceEvaluatorCallback mServiceEvaluatorCallback =
            new ANSServiceStateEvaluator.ANSServiceEvaluatorCallback() {
                @Override
                public void onBadDataService() {
                    logDebug("Bad opportunistic data service");
                    mServiceStateEvaluator.stopEvaluation();
                    mProfileSelector.selectPrimaryProfileForData();
                    mProfileSelector.startProfileSelection();
                }
            };

    private final IAns.Stub mBinder = new IAns.Stub() {
        /**
         * Enable or disable Alternative Network service.
         *
         * This method should be called to enable or disable
         * AlternativeNetwork service on the device.
         *
         * <p>
         * Requires Permission:
         *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
         * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
         *
         * @param enable enable(True) or disable(False)
         * @param callingPackage caller's package name
         * @return returns true if successfully set.
         */
        @Override
        public boolean setEnable(boolean enable, String callingPackage) {
            TelephonyPermissions.enforceCallingOrSelfModifyPermissionOrCarrierPrivilege(
                    mContext, mSubsriptionManager.getDefaultSubscriptionId(), "setEnable");
            log("setEnable: " + enable);

            final long identity = Binder.clearCallingIdentity();
            try {
                enableAlternativeNetwork(enable);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            return true;
        }

        /**
         * is Alternative Network service enabled
         *
         * This method should be called to determine if the Alternative Network service
         * is enabled
         *
         * <p>
         * Requires Permission:
         *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
         * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
         *
         * @param callingPackage caller's package name
         */
        @Override
        public boolean isEnabled(String callingPackage) {
            TelephonyPermissions.enforeceCallingOrSelfReadPhoneStatePermissionOrCarrierPrivilege(
                    mContext, mSubsriptionManager.getDefaultSubscriptionId(), "isEnabled");
            return mIsEnabled;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        initialize(getBaseContext());

        /* register the service */
        if (ServiceManager.getService(SERVICE_NAME) == null) {
            ServiceManager.addService(SERVICE_NAME, mBinder);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("Destroyed Successfully...");

    }

    /**
     * initialize ANS and register as service.
     * Read persistent state to update enable state
     * Start sub components if already enabled.
     * @param context context instance
     */
    private void initialize(Context context) {
        mContext = context;
        mTelephonyManager = TelephonyManager.from(mContext);
        mServiceStateEvaluator = new ANSServiceStateEvaluator(mContext, mServiceEvaluatorCallback);
        mProfileSelector = new ANSProfileSelector(mContext, mProfileSelectionCallback);
        mSharedPref = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        mSubsriptionManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        enableAlternativeNetwork(getPersistentEnableState());
    }


    private boolean getPersistentEnableState() {
        return mSharedPref.getBoolean(PREF_ENABLED, true);
    }

    private void updateEnableState(boolean enable) {
        mIsEnabled = enable;
        mSharedPref.edit().putBoolean(PREF_ENABLED, mIsEnabled).apply();
    }

    /**
     * update the enable state
     * start profile selection if enabled.
     * @param enable enable(true) or disable(false)
     */
    private void enableAlternativeNetwork(boolean enable) {
        synchronized (mLock) {
            if (mIsEnabled != enable) {
                updateEnableState(enable);
                if (mIsEnabled) {
                    mProfileSelector.startProfileSelection();
                } else {
                    mProfileSelector.stopProfileSelection();
                }
            }
        }
        logDebug("service is enable state " + mIsEnabled);
    }

    private void log(String msg) {
        Rlog.d(TAG, msg);
    }

    private void logDebug(String msg) {
        if (DBG) Rlog.d(TAG, msg);
    }
}
