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

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Message;
import android.telephony.PhoneStateListener;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;

/**
 * ANSServiceStateMonitor class which will monitor service state of a given subscription.
 */
public class ANSServiceStateMonitor {
    @VisibleForTesting
    protected Context mContext;

    @VisibleForTesting
    protected TelephonyManager mTelephonyManager;

    @VisibleForTesting
    protected ConnectivityManager mConnectivityManager;
    private ANSServiceMonitorCallback mServiceMonitorCallback;
    private PhoneStateListener mPhoneStateListener;
    private int mSubId;
    private int mSignalStrengthState;
    private int mServiceStateState;
    private final Object mLock = new Object();

    /* service states to be used while reporting onServiceMonitorUpdate */
    public static final int SERVICE_STATE_UNKNOWN = 0;
    public static final int SERVICE_STATE_NO_SERVICE = 1;
    public static final int SERVICE_STATE_BAD = 2;
    public static final int SERVICE_STATE_GOOD = 3;

    /* messages to handle network condition changes */
    private static final int MSG_SIGNAL_STRENGTH_CHANGED = 1;
    private static final int MSG_SERVICE_STATE_CHANGED = 2;

    private static final String LOG_TAG = "ANSServiceStateMonitor";
    private static final boolean DBG = true;

    protected void init(Context c, ANSServiceMonitorCallback serviceMonitorCallback) {
        mContext = c;
        mTelephonyManager = TelephonyManager.from(mContext);
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mSignalStrengthState = SERVICE_STATE_UNKNOWN;
        mServiceStateState = SERVICE_STATE_UNKNOWN;
        mServiceMonitorCallback = serviceMonitorCallback;
        logDebug("[ANSServiceStateMonitor] init by Context");
    }

    /**
     * get the string name of a state
     * @param state service state
     * @return string name of a state
     */
    public static String getStateString(int state) {
        switch (state) {
            case SERVICE_STATE_NO_SERVICE:
                return "No Service";
            case SERVICE_STATE_BAD:
                return "Bad Service";
            case SERVICE_STATE_GOOD:
                return "Good Service";
            default:
                return "Unknown";
        }
    }

    /**
     * returns whether the fail reason is permanent
     * @param failCause fail reason
     * @return true if reason is permanent
     */
    @VisibleForTesting
    public static boolean isFatalFailCause(String failCause) {
        if (failCause == null || failCause.isEmpty()) {
            return false;
        }

        switch (failCause) {
            case "OPERATOR_BARRED":
            case "USER_AUTHENTICATION":
            case "ACTIVATION_REJECT_GGSN":
            case "SERVICE_OPTION_NOT_SUPPORTED":
            case "SERVICE_OPTION_NOT_SUBSCRIBED":
            case "SERVICE_OPTION_OUT_OF_ORDER":
            case "PROTOCOL_ERRORS":
                return true;
            default:
                return false;
        }
    }

    private void updateCallbackOnFinalState() {
        int evaluatedState = SERVICE_STATE_UNKNOWN;

        logDebug("mServiceStateState: " + getStateString(mServiceStateState)
                + " mSignalStrengthState: " + getStateString(mSignalStrengthState));

        /* Service state has highest priority in this validation. If no service, no need to
           check further. */
        if (mServiceStateState == SERVICE_STATE_GOOD) {
            evaluatedState = SERVICE_STATE_GOOD;
        } else if (mServiceStateState == SERVICE_STATE_NO_SERVICE) {
            evaluatedState = SERVICE_STATE_NO_SERVICE;
            mServiceMonitorCallback.onServiceMonitorUpdate(mSubId, SERVICE_STATE_NO_SERVICE);
            return;
        }

        /* use signal strength to determine service quality only, i.e is good or bad. */
        if (evaluatedState == SERVICE_STATE_GOOD) {
            if (mSignalStrengthState == SERVICE_STATE_BAD) {
                evaluatedState = SERVICE_STATE_BAD;
            }
        }

        if (evaluatedState != SERVICE_STATE_UNKNOWN) {
            mServiceMonitorCallback.onServiceMonitorUpdate(mSubId, evaluatedState);
        }
    }

    private void analyzeSignalStrengthChange(SignalStrength signalStrength) {
        if (mServiceMonitorCallback == null) {
            return;
        }

        if (signalStrength.getLevel() <= SignalStrength.SIGNAL_STRENGTH_POOR) {
            mSignalStrengthState = SERVICE_STATE_BAD;
        } else {
            mSignalStrengthState = SERVICE_STATE_GOOD;
        }

        updateCallbackOnFinalState();
    }

    private void analyzeServiceStateChange(ServiceState serviceState) {
        logDebug("analyzeServiceStateChange state:"
                + serviceState.getDataRegState());
        if (mServiceMonitorCallback == null) {
            return;
        }

        if ((serviceState.getDataRegState() == ServiceState.STATE_OUT_OF_SERVICE)
                || (serviceState.getState() == ServiceState.STATE_EMERGENCY_ONLY)) {
            mServiceMonitorCallback.onServiceMonitorUpdate(mSubId, SERVICE_STATE_NO_SERVICE);
            mServiceStateState = SERVICE_STATE_NO_SERVICE;
        } else if (serviceState.getDataRegState() == ServiceState.STATE_IN_SERVICE) {
            mServiceStateState = SERVICE_STATE_GOOD;
        }

        updateCallbackOnFinalState();
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SIGNAL_STRENGTH_CHANGED:
                    analyzeSignalStrengthChange((SignalStrength) msg.obj);
                    break;
                case MSG_SERVICE_STATE_CHANGED:
                    analyzeServiceStateChange((ServiceState) msg.obj);
                    break;
                default:
                    log("invalid message");
                    break;
            }
        }
    };

    private void sendEvent(int event, Object obj) {
        mHandler.obtainMessage(event, obj).sendToTarget();
    }

    /**
     * Implements phone state listener
     */
    @VisibleForTesting
    public class PhoneStateListenerImpl extends PhoneStateListener {
        PhoneStateListenerImpl(int subId) {
            super(subId);
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            sendEvent(MSG_SIGNAL_STRENGTH_CHANGED, signalStrength);
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            sendEvent(MSG_SERVICE_STATE_CHANGED, serviceState);
        }
    };

    /**
     * get phone state listener instance
     * @param subId subscription id
     * @return the listener instance
     */
    @VisibleForTesting
    public PhoneStateListener getPhoneStateListener(int subId) {
        synchronized (mLock) {
            if (mPhoneStateListener != null && subId == mSubId) {
                return mPhoneStateListener;
            }
            mSubId = subId;
            mPhoneStateListener = (PhoneStateListener) new PhoneStateListenerImpl(subId);
        }
        return mPhoneStateListener;
    }

    /**
     * call back interface
     */
    public interface ANSServiceMonitorCallback {
        /**
         * call back interface
         */
        void onServiceMonitorUpdate(int subId, int state);
    }

    /**
     * request to start listening for network changes.
     */
    public void startListeningForNetworkConditionChange(int subId) {

        logDebug("start network condition listen for " + subId);
        /* monitor service state, signal strength and data connection state */
        int events = PhoneStateListener.LISTEN_SERVICE_STATE
                | PhoneStateListener.LISTEN_SIGNAL_STRENGTH;
        mTelephonyManager.listen(getPhoneStateListener(subId), events);
        return;
    }

    /**
     * request to stop listening for network changes.
     */
    public void stopListeningForNetworkConditionChange() {
        logDebug("stop network condition listen for " + mSubId);
        synchronized (mLock) {
            if (mPhoneStateListener != null) {
                mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
            mSignalStrengthState = SERVICE_STATE_UNKNOWN;
            mServiceStateState = SERVICE_STATE_UNKNOWN;
        }
        return;
    }

    public ANSServiceStateMonitor(Context c, ANSServiceMonitorCallback serviceMonitorCallback) {
        init(c, serviceMonitorCallback);
    }

    private static void log(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private static void logDebug(String msg) {
        if (DBG) {
            Rlog.d(LOG_TAG, msg);
        }
    }
}
