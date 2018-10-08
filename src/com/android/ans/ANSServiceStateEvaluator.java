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

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.telephony.Rlog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.TimeUnit;

/**
 * ANSServiceStateEvaluator class which will evaluate the data service state of a subId
 * compared to another.
 */
public class ANSServiceStateEvaluator {
    private Context mContext;
    private final Object mLock = new Object();
    private int mOppDataSubId;
    private int mPrimarySubId;
    /* new opportunistic data service state */
    private int mOppDataNewState = ANSServiceStateMonitor.SERVICE_STATE_UNKNOWN;
    private int mPrimaryNewState = ANSServiceStateMonitor.SERVICE_STATE_UNKNOWN;
    private boolean mIsWaitingForTimeout = false;

    @VisibleForTesting
    protected ANSServiceEvaluatorCallback mServiceEvaluatorCallback;

    @VisibleForTesting
    protected ANSServiceStateMonitor mOppDataSubMonitor;

    @VisibleForTesting
    protected ANSServiceStateMonitor mPrimarySubMonitor;

    @VisibleForTesting
    protected AlarmManager mAlarmManager;

    private static final int WAIT_FOR_DATA_SERVICE_PERIOD = (int) TimeUnit.SECONDS.toMillis(10);
    private static final String LOG_TAG = "ANSServiceStateEvaluator";
    private static final boolean DBG = true;

    /* message to indicate no data for WAIT_FOR_DATA_SERVICE_PERIOD */
    private static final int MSG_WAIT_FOR_DATA_SERVICE_TIMOUT = 1;

    /**
     * call back to confirm service state evaluation
     */
    public interface ANSServiceEvaluatorCallback {

        /**
         * call back to confirm bad service
         */
        void onBadDataService();
    }

    private Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_WAIT_FOR_DATA_SERVICE_TIMOUT:
                        mIsWaitingForTimeout = false;
                        logDebug("Msg received to get data");
                        evaluateUpdatedState();
                        break;

                    default:
                        log("invalid message");
                        break;
                }
            }
        };

    public final AlarmManager.OnAlarmListener mDataServiceWaitTimer =
            (AlarmManager.OnAlarmListener) () -> {
                logDebug("Alarm fired");
                mHandler.sendEmptyMessage(MSG_WAIT_FOR_DATA_SERVICE_TIMOUT);
            };

    /**
     * set alarm to wait for data service
     */
    private void setDataServiceWaitAlarm() {
        mAlarmManager.set(AlarmManager.RTC, System.currentTimeMillis()
                + WAIT_FOR_DATA_SERVICE_PERIOD, LOG_TAG, mDataServiceWaitTimer, null);
    }

    /**
     * stop the alarm
     */
    private void stopDataServiceWaitAlarm() {
        mAlarmManager.cancel(mDataServiceWaitTimer);
    }

    private boolean evaluateIfBadServiceState() {
        /* if we have not received update on both subId, we can not take decision, yes */
        log("evaluateIfBadServiceState: mPrimaryNewState: "
                + ANSServiceStateMonitor.getStateString(mPrimaryNewState) + " mOppDataNewState: "
                + ANSServiceStateMonitor.getStateString(mOppDataNewState));

        if ((mPrimaryNewState == ANSServiceStateMonitor.SERVICE_STATE_UNKNOWN)
                || (mOppDataNewState == ANSServiceStateMonitor.SERVICE_STATE_UNKNOWN)) {
            return false;
        }

        /* Evaluate if primary subscription has good service and if
           opportunistic data subscription is not, if yes return true.
         */
        switch (mPrimaryNewState) {
            case ANSServiceStateMonitor.SERVICE_STATE_NO_SERVICE:
                /* no need to make any change */
                return false;
            case ANSServiceStateMonitor.SERVICE_STATE_BAD:
                if (mOppDataNewState != ANSServiceStateMonitor.SERVICE_STATE_NO_SERVICE) {
                    return false;
                }

                break;
            case ANSServiceStateMonitor.SERVICE_STATE_GOOD:
                if (mOppDataNewState == ANSServiceStateMonitor.SERVICE_STATE_GOOD) {
                    return false;
                }
                break;
            default:
                log("invalid state");
                break;
        }

        return true;
    }

    private void evaluateUpdatedState() {
        logDebug("evaluateUpdatedState " + mIsWaitingForTimeout);
        if (!mIsWaitingForTimeout && evaluateIfBadServiceState()) {
            mServiceEvaluatorCallback.onBadDataService();
        }
    }

    /* service monitor callback will get called for service state change on a particular subId. */
    ANSServiceStateMonitor.ANSServiceMonitorCallback mServiceMonitorCallback =
            new ANSServiceStateMonitor.ANSServiceMonitorCallback() {
                @Override
                public void onServiceMonitorUpdate(int subId, int state) {
                    logDebug("onServiceMonitorUpdate subId: " + subId + " state: "
                            + ANSServiceStateMonitor.getStateString(state));
                    synchronized (mLock) {
                        if (mServiceEvaluatorCallback == null) {
                            return;
                        }

                        if (subId == mPrimarySubId) {
                            mPrimaryNewState = state;
                        } else if (subId == mOppDataSubId) {
                            mOppDataNewState = state;
                        } else {
                            logDebug("invalid sub id");
                        }

                        evaluateUpdatedState();
                    }
                }
            };

    public ANSServiceStateEvaluator(Context c,
            ANSServiceEvaluatorCallback serviceEvaluatorCallback) {
        init(c, serviceEvaluatorCallback);
    }

    protected void init(Context c, ANSServiceEvaluatorCallback serviceEvaluatorCallback) {
        mContext = c;
        mServiceEvaluatorCallback = serviceEvaluatorCallback;
        mOppDataSubMonitor = new ANSServiceStateMonitor(mContext, mServiceMonitorCallback);
        mPrimarySubMonitor = new ANSServiceStateMonitor(mContext, mServiceMonitorCallback);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * start service state evaluation for dataSubId compared to voiceSubId.
     * This API evaluates the service state conditions of dataSubId and decides whether
     * data service is bad compared to voiceSubId
     * @param dataSubId current data subscription id
     * @param voiceSubId voice subscription id
     */
    public void startEvaluation(int dataSubId, int voiceSubId) {
        logDebug("Start evaluation");
        /* make sure to clean up if there is any evaluation going on. */
        stopEvaluation();
        setDataServiceWaitAlarm();
        synchronized (mLock) {
            mIsWaitingForTimeout = true;
            mOppDataSubId = dataSubId;
            mPrimarySubId = voiceSubId;
            mOppDataSubMonitor.startListeningForNetworkConditionChange(dataSubId);
            mPrimarySubMonitor.startListeningForNetworkConditionChange(voiceSubId);
        }
    }

    /**
     * stop service state evaluation
     */
    public void stopEvaluation() {
        logDebug("Stop evaluation");
        synchronized (mLock) {
            mOppDataSubId = -1;
            mPrimarySubId = -1;
            if (mIsWaitingForTimeout) {
                stopDataServiceWaitAlarm();
            }
            mIsWaitingForTimeout = false;
            mOppDataSubMonitor.stopListeningForNetworkConditionChange();
            mPrimarySubMonitor.stopListeningForNetworkConditionChange();
            mOppDataNewState = ANSServiceStateMonitor.SERVICE_STATE_UNKNOWN;
            mPrimaryNewState = ANSServiceStateMonitor.SERVICE_STATE_UNKNOWN;
        }
    }


    private void log(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logDebug(String msg) {
        if (DBG) {
            Rlog.d(LOG_TAG, msg);
        }
    }
}
