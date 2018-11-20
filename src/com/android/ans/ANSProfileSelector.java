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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Profile selector class which will select the right profile based upon
 * geographic information input and network scan results.
 */
public class ANSProfileSelector {
    private static final String LOG_TAG = "ANSProfileSelector";
    private static final boolean DBG = true;
    private final Object mLock = new Object();

    private static final int INVALID_SEQUENCE_ID = -1;
    private static final int START_SEQUENCE_ID = 1;

    /* message to indicate profile update */
    private static final int MSG_PROFILE_UPDATE = 1;

    /* message to indicate start of profile selection process */
    private static final int MSG_START_PROFILE_SELECTION = 2;
    private boolean mIsEnabled = false;

    @VisibleForTesting
    protected Context mContext;

    @VisibleForTesting
    protected TelephonyManager mTelephonyManager;

    @VisibleForTesting
    protected ANSNetworkScanCtlr mNetworkScanCtlr;

    @VisibleForTesting
    protected SubscriptionManager mSubscriptionManager;
    @VisibleForTesting
    protected List<SubscriptionInfo> mOppSubscriptionInfos;
    private ANSProfileSelectionCallback mProfileSelectionCallback;
    private int mSequenceId;

    public static final String ACTION_SUB_SWITCH =
            "android.intent.action.SUBSCRIPTION_SWITCH_REPLY";

    @VisibleForTesting
    protected SubscriptionManager.OnOpportunisticSubscriptionsChangedListener
            mProfileChangeListener =
            new SubscriptionManager.OnOpportunisticSubscriptionsChangedListener() {
                @Override
                public void onOpportunisticSubscriptionsChanged() {
                    mHandler.sendEmptyMessage(MSG_PROFILE_UPDATE);
                }
            };

    @VisibleForTesting
    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROFILE_UPDATE:
                    updateOpportunisticSubscriptions();
                    checkProfileUpdate();
                    break;
                case MSG_START_PROFILE_SELECTION:
                    logDebug("Msg received for profile update");
                    checkProfileUpdate();
                    break;
                default:
                    log("invalid message");
                    break;
            }
        }
    };

    /**
     * Broadcast receiver to receive intents
     */
    @VisibleForTesting
    protected final BroadcastReceiver mProfileSelectorBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int sequenceId;
                    int subId;
                    String action = intent.getAction();
                    if (!mIsEnabled || action == null) {
                        return;
                    }

                    switch (action) {
                        case ACTION_SUB_SWITCH:
                            sequenceId = intent.getIntExtra("sequenceId",  INVALID_SEQUENCE_ID);
                            subId = intent.getIntExtra("subId",
                                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                            if (sequenceId != mSequenceId) {
                                return;
                            }

                            onSubSwitchComplete(subId);
                            break;
                    }
                }
            };

    /**
     * Network scan callback handler
     */
    @VisibleForTesting
    protected ANSNetworkScanCtlr.NetworkAvailableCallBack mNetworkAvailableCallBack =
            new ANSNetworkScanCtlr.NetworkAvailableCallBack() {
                @Override
                public void onNetworkAvailability(List<CellInfo> results) {
                    /* sort the results according to signal strength level */
                    Collections.sort(results, new Comparator<CellInfo>() {
                        @Override
                        public int compare(CellInfo cellInfo1, CellInfo cellInfo2) {
                            return getSignalLevel(cellInfo1) - getSignalLevel(cellInfo2);
                        }
                    });

                    /* get subscription id for the best network scan result */
                    int subId = getSubId(getMcc(results.get(0)), getMnc(results.get(0)));
                    if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        /* could not find any matching subscriptions */
                        return;
                    }

                    /* if subscription is already active, proceed to data switch */
                    if (mSubscriptionManager.isActiveSubId(subId)) {
                        mProfileSelectionCallback.onProfileSelectionDone();
                    } else {
                        switchToSubscription(subId);
                    }
                }

                @Override
                public void onError(int error) {
                    log("Network scan failed with error " + error);
                }
            };

    /**
     * interface call back to confirm profile selection
     */
    public interface ANSProfileSelectionCallback {

        /**
         * interface call back to confirm profile selection
         */
        void onProfileSelectionDone();
    }

    /**
     * ANSProfileSelector constructor
     * @param c context
     * @param profileSelectionCallback callback to be called once selection is done
     */
    public ANSProfileSelector(Context c, ANSProfileSelectionCallback profileSelectionCallback) {
        init(c, profileSelectionCallback);
        log("ANSProfileSelector init complete");
    }

    private int getSignalLevel(CellInfo cellInfo) {
        if (cellInfo != null) {
            return cellInfo.getCellSignalStrength().getLevel();
        } else {
            return SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
    }

    private String getMcc(CellInfo cellInfo) {
        String mcc = "";
        if (cellInfo instanceof CellInfoGsm) {
            mcc = ((CellInfoGsm) cellInfo).getCellIdentity().getMccString();
        } else if (cellInfo instanceof CellInfoLte) {
            mcc = ((CellInfoLte) cellInfo).getCellIdentity().getMccString();
        } else if (cellInfo instanceof CellInfoWcdma) {
            mcc = ((CellInfoWcdma) cellInfo).getCellIdentity().getMccString();
        }

        return mcc;
    }

    private String getMnc(CellInfo cellInfo) {
        String mnc = "";
        if (cellInfo instanceof CellInfoGsm) {
            mnc = ((CellInfoGsm) cellInfo).getCellIdentity().getMncString();
        } else if (cellInfo instanceof CellInfoLte) {
            mnc = ((CellInfoLte) cellInfo).getCellIdentity().getMncString();
        } else if (cellInfo instanceof CellInfoWcdma) {
            mnc = ((CellInfoWcdma) cellInfo).getCellIdentity().getMncString();
        }

        return mnc;
    }

    private int getSubId(String mcc, String mnc) {
        List<SubscriptionInfo> subscriptionInfos = mOppSubscriptionInfos;
        for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
            if (TextUtils.equals(subscriptionInfo.getMccString(), mcc)
                    && TextUtils.equals(subscriptionInfo.getMncString(), mnc)) {
                return subscriptionInfo.getSubscriptionId();
            }
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private void switchToSubscription(int subId) {
        Intent callbackIntent = new Intent(ACTION_SUB_SWITCH);
        callbackIntent.setClass(mContext, ANSProfileSelector.class);
        callbackIntent.putExtra("sequenceId", getAndUpdateToken());
        callbackIntent.putExtra("subId", subId);

        PendingIntent replyIntent = PendingIntent.getService(mContext,
                1, callbackIntent,
                Intent.FILL_IN_ACTION);
        mSubscriptionManager.switchToSubscription(subId, replyIntent);
    }

    private void switchPreferredData(int subId) {
        mSubscriptionManager.setPreferredData(subId);
    }

    private void onSubSwitchComplete(int subId) {
        mProfileSelectionCallback.onProfileSelectionDone();
    }

    private int getAndUpdateToken() {
        synchronized (mLock) {
            return mSequenceId++;
        }
    }

    private void checkProfileUpdate() {
        List<SubscriptionInfo> subscriptionInfos = mOppSubscriptionInfos;
        if (subscriptionInfos == null) {
            return;
        }

        if (subscriptionInfos.size() > 0) {
            logDebug("opportunistic subscriptions size " + subscriptionInfos.size());

            /* start scan immediately */
            mNetworkScanCtlr.startFastNetworkScan(subscriptionInfos);
        } else if (subscriptionInfos.size() == 0) {
            /* check if no profile */
            mNetworkScanCtlr.stopNetworkScan();
        }
    }

    private boolean isActiveSub(int subId) {
        List<SubscriptionInfo> subscriptionInfos =
                mSubscriptionManager.getActiveSubscriptionInfoList();
        for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
            if (subscriptionInfo.getSubscriptionId() == subId) {
                return true;
            }
        }

        return false;
    }

    public boolean isOpprotunisticSub(int subId) {
        if ((mOppSubscriptionInfos == null) || (mOppSubscriptionInfos.size() == 0)) {
            return false;
        }
        for (SubscriptionInfo subscriptionInfo : mOppSubscriptionInfos) {
            if (subscriptionInfo.getSubscriptionId() == subId) {
                return true;
            }
        }
        return false;
    }

    /**
     * start profile selection procedure
     */
    public void startProfileSelection() {
        synchronized (mLock) {
            if (!mIsEnabled) {
                mIsEnabled = true;
                mHandler.sendEmptyMessage(MSG_START_PROFILE_SELECTION);
            }
        }
    }

    /**
     * select primary profile for data
     */
    public void selectPrimaryProfileForData() {
        mSubscriptionManager.setPreferredData(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    /**
     * select opportunistic profile for data if passing a valid subId.
     * @param subId : opportunistic subId or SubscriptionManager.INVALID_SUBSCRIPTION_ID if
     *              deselecting previously set preference.
     */
    public boolean selectProfileForData(int subId) {
        if ((subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                || (isOpprotunisticSub(subId) && isActiveSub(subId))) {
            mSubscriptionManager.setPreferredData(subId);
            return true;
        } else {
            log("Inactive sub passed for preferred data " + subId);
            return false;
        }
    }

    public int getPreferedData() {
        // Todo: b/117833883
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * stop profile selection procedure
     */
    public void stopProfileSelection() {
        mNetworkScanCtlr.stopNetworkScan();
        synchronized (mLock) {
            mIsEnabled = false;
        }
    }

    @VisibleForTesting
    protected void updateOpportunisticSubscriptions() {
        synchronized (mLock) {
            mOppSubscriptionInfos = mSubscriptionManager.getOpportunisticSubscriptions();
        }
    }

    @VisibleForTesting
    protected void init(Context c, ANSProfileSelectionCallback profileSelectionCallback) {
        mContext = c;
        mSequenceId = START_SEQUENCE_ID;
        mProfileSelectionCallback = profileSelectionCallback;
        mTelephonyManager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mNetworkScanCtlr = new ANSNetworkScanCtlr(mContext, mTelephonyManager,
                mNetworkAvailableCallBack);
        updateOpportunisticSubscriptions();
        /* register for profile update events */
        mSubscriptionManager.addOnOpportunisticSubscriptionsChangedListener(
                AsyncTask.SERIAL_EXECUTOR, mProfileChangeListener);
        /* register for subscription switch intent */
        mContext.registerReceiver(mProfileSelectorBroadcastReceiver,
                new IntentFilter(ACTION_SUB_SWITCH));
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
