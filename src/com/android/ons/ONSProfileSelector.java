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

package com.android.ons;

import static android.telephony.AvailableNetworkInfo.PRIORITY_HIGH;
import static android.telephony.AvailableNetworkInfo.PRIORITY_LOW;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.telephony.AvailableNetworkInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

/**
 * Profile selector class which will select the right profile based upon
 * geographic information input and network scan results.
 */
public class ONSProfileSelector {
    private static final String LOG_TAG = "ONSProfileSelector";
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
    private TelephonyManager mSubscriptionBoundTelephonyManager;

    @VisibleForTesting
    protected ONSNetworkScanCtlr mNetworkScanCtlr;

    @VisibleForTesting
    protected SubscriptionManager mSubscriptionManager;
    @VisibleForTesting
    protected List<SubscriptionInfo> mOppSubscriptionInfos;
    private ONSProfileSelectionCallback mProfileSelectionCallback;
    private int mSequenceId;
    private int mCurrentDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private ArrayList<AvailableNetworkInfo> mAvailableNetworkInfos;

    public static final String ACTION_SUB_SWITCH =
            "android.intent.action.SUBSCRIPTION_SWITCH_REPLY";

    HandlerThread mThread;
    @VisibleForTesting
    protected Handler mHandler;

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
                    logDebug("ACTION_SUB_SWITCH : " + action);
                    if (!mIsEnabled || action == null) {
                        return;
                    }

                    switch (action) {
                        case ACTION_SUB_SWITCH:
                            sequenceId = intent.getIntExtra("sequenceId",  INVALID_SEQUENCE_ID);
                            subId = intent.getIntExtra("subId",
                                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                            logDebug("ACTION_SUB_SWITCH sequenceId: " + sequenceId
                                    + " mSequenceId: " + mSequenceId);
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
    protected ONSNetworkScanCtlr.NetworkAvailableCallBack mNetworkAvailableCallBack =
            new ONSNetworkScanCtlr.NetworkAvailableCallBack() {
                @Override
                public void onNetworkAvailability(List<CellInfo> results) {
                    int subId = retrieveBestSubscription(results);
                    if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        return;
                    }

                    /* stop scanning further */
                    mNetworkScanCtlr.stopNetworkScan();
                    handleNetworkScanResult(subId);
                }

                @Override
                public void onError(int error) {
                    log("Network scan failed with error " + error);
                    if (mIsEnabled && mAvailableNetworkInfos != null
                            && mAvailableNetworkInfos.size() > 0) {
                        handleNetworkScanResult(mAvailableNetworkInfos.get(0).getSubId());
                    }
                }

                private void handleNetworkScanResult(int subId) {
                    /* if subscription is already active, just enable modem */
                    if (mSubscriptionManager.isActiveSubId(subId)) {
                        enableModem(subId, true);
                        mProfileSelectionCallback.onProfileSelectionDone();
                    } else {
                        logDebug("switch to sub:" + subId);
                        switchToSubscription(subId);
                    }
                }
            };

    @VisibleForTesting
    protected SubscriptionManager.OnOpportunisticSubscriptionsChangedListener
            mProfileChangeListener =
            new SubscriptionManager.OnOpportunisticSubscriptionsChangedListener() {
                @Override
                public void onOpportunisticSubscriptionsChanged() {
                    mHandler.sendEmptyMessage(MSG_PROFILE_UPDATE);
                }
            };

    /**
     * interface call back to confirm profile selection
     */
    public interface ONSProfileSelectionCallback {

        /**
         * interface call back to confirm profile selection
         */
        void onProfileSelectionDone();
    }

    class SortSubInfo implements Comparator<SubscriptionInfo>
    {
        // Used for sorting in ascending order of sub id
        public int compare(SubscriptionInfo a, SubscriptionInfo b)
        {
            return a.getSubscriptionId() - b.getSubscriptionId();
        }
    }

    class SortAvailableNetworks implements Comparator<AvailableNetworkInfo>
    {
        // Used for sorting in ascending order of sub id
        public int compare(AvailableNetworkInfo a, AvailableNetworkInfo b)
        {
            return a.getSubId() - b.getSubId();
        }
    }

    class SortAvailableNetworksInPriority implements Comparator<AvailableNetworkInfo>
    {
        // Used for sorting in descending order of priority (ascending order of priority numbers)
        public int compare(AvailableNetworkInfo a, AvailableNetworkInfo b)
        {
            return a.getPriority() - b.getPriority();
        }
    }

    /**
     * ONSProfileSelector constructor
     * @param c context
     * @param profileSelectionCallback callback to be called once selection is done
     */
    public ONSProfileSelector(Context c, ONSProfileSelectionCallback profileSelectionCallback) {
        init(c, profileSelectionCallback);
        log("ONSProfileSelector init complete");
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
        if (cellInfo instanceof CellInfoLte) {
            mcc = ((CellInfoLte) cellInfo).getCellIdentity().getMccString();
        }

        return mcc;
    }

    private String getMnc(CellInfo cellInfo) {
        String mnc = "";
        if (cellInfo instanceof CellInfoLte) {
            mnc = ((CellInfoLte) cellInfo).getCellIdentity().getMncString();
        }

        return mnc;
    }

    private int getSubIdUsingAvailableNetworks(String mcc, String mnc, int priorityLevel) {
        String mccMnc = mcc + mnc;
        for (AvailableNetworkInfo availableNetworkInfo : mAvailableNetworkInfos) {
            if (availableNetworkInfo.getPriority() != priorityLevel) {
                continue;
            }
            for (String availableMccMnc : availableNetworkInfo.getMccMncs()) {
                if (TextUtils.equals(availableMccMnc, mccMnc)) {
                    return availableNetworkInfo.getSubId();
                }
            }
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    public SubscriptionInfo getOpprotunisticSubInfo(int subId) {
        if ((mOppSubscriptionInfos == null) || (mOppSubscriptionInfos.size() == 0)) {
            return null;
        }
        for (SubscriptionInfo subscriptionInfo : mOppSubscriptionInfos) {
            if (subscriptionInfo.getSubscriptionId() == subId) {
                return subscriptionInfo;
            }
        }
        return null;
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

    public boolean hasOpprotunisticSub(List<AvailableNetworkInfo> availableNetworks) {
        if ((availableNetworks == null) || (availableNetworks.size() == 0)) {
            return false;
        }
        if ((mOppSubscriptionInfos == null) || (mOppSubscriptionInfos.size() == 0)) {
            return false;
        }

        for (AvailableNetworkInfo availableNetworkInfo : availableNetworks) {
            if (!isOpprotunisticSub(availableNetworkInfo.getSubId())) {
                return false;
            }
        }
        return true;
    }

    private boolean isAvtiveSub(int subId) {
        return mSubscriptionManager.isActiveSubscriptionId(subId);
    }

    private void switchToSubscription(int subId) {
        Intent callbackIntent = new Intent(ACTION_SUB_SWITCH);
        callbackIntent.setClass(mContext, ONSProfileSelector.class);
        callbackIntent.putExtra("sequenceId", getAndUpdateToken());
        callbackIntent.putExtra("subId", subId);

        PendingIntent replyIntent = PendingIntent.getService(mContext,
                1, callbackIntent,
                Intent.FILL_IN_ACTION);
        mSubscriptionManager.switchToSubscription(subId, replyIntent);
    }

    private void switchPreferredData(int subId) {
        mSubscriptionManager.setPreferredDataSubscriptionId(subId);
        mCurrentDataSubId = subId;
    }

    private void onSubSwitchComplete(int subId) {
        enableModem(subId, true);
        mProfileSelectionCallback.onProfileSelectionDone();
    }

    private int getAndUpdateToken() {
        synchronized (mLock) {
            return mSequenceId++;
        }
    }

    private ArrayList<AvailableNetworkInfo> getFilteredAvailableNetworks(
            ArrayList<AvailableNetworkInfo> availableNetworks,
            List<SubscriptionInfo> subscriptionInfoList) {
        ArrayList<AvailableNetworkInfo> filteredAvailableNetworks =
                new ArrayList<AvailableNetworkInfo>();

        /* instead of checking each element of a list every element of the other, sort them in
           the order of sub id and compare to improve the filtering performance. */
        Collections.sort(subscriptionInfoList, new SortSubInfo());
        Collections.sort(availableNetworks, new SortAvailableNetworks());
        int availableNetworksIndex = 0;
        int subscriptionInfoListIndex = 0;
        SubscriptionInfo subscriptionInfo;
        AvailableNetworkInfo availableNetwork;

        while (availableNetworksIndex < availableNetworks.size()
                && subscriptionInfoListIndex < subscriptionInfoList.size()) {
            subscriptionInfo = subscriptionInfoList.get(subscriptionInfoListIndex);
            availableNetwork = availableNetworks.get(availableNetworksIndex);
            if (subscriptionInfo.getSubscriptionId() == availableNetwork.getSubId()) {
                filteredAvailableNetworks.add(availableNetwork);
                subscriptionInfoListIndex++;
                availableNetworksIndex++;
            } else if (subscriptionInfo.getSubscriptionId() < availableNetwork.getSubId()) {
                subscriptionInfoListIndex++;
            } else {
                availableNetworksIndex++;
            }
        }
        return filteredAvailableNetworks;
    }

    private boolean isSame(ArrayList<AvailableNetworkInfo> availableNetworks1,
            ArrayList<AvailableNetworkInfo> availableNetworks2) {
        if ((availableNetworks1 == null) || (availableNetworks2 == null)) {
            return false;
        }
        return new HashSet<>(availableNetworks1).equals(new HashSet<>(availableNetworks2));
    }

    private void checkProfileUpdate(ArrayList<AvailableNetworkInfo> availableNetworks) {
        if (mOppSubscriptionInfos == null) {
            logDebug("null subscription infos");
            return;
        }
        if (isSame(availableNetworks, mAvailableNetworkInfos)) {
            return;
        }

        stopProfileSelection();
        mIsEnabled = true;
        mAvailableNetworkInfos = availableNetworks;
        /* sort in the order of priority */
        Collections.sort(mAvailableNetworkInfos, new SortAvailableNetworksInPriority());
        logDebug("availableNetworks: " + availableNetworks);

        if (mOppSubscriptionInfos.size() > 0) {
            logDebug("opportunistic subscriptions size " + mOppSubscriptionInfos.size());
            ArrayList<AvailableNetworkInfo> filteredAvailableNetworks =
                    getFilteredAvailableNetworks((ArrayList<AvailableNetworkInfo>)availableNetworks,
                            mOppSubscriptionInfos);
            if ((filteredAvailableNetworks.size() == 1)
                    && ((filteredAvailableNetworks.get(0).getMccMncs() == null)
                    || (filteredAvailableNetworks.get(0).getMccMncs().size() == 0))) {
                /* if subscription is not active, activate the sub */
                if (!mSubscriptionManager.isActiveSubId(filteredAvailableNetworks.get(0).getSubId())) {
                    switchToSubscription(filteredAvailableNetworks.get(0).getSubId());
                }
            } else {
                /* start scan immediately */
                mNetworkScanCtlr.startFastNetworkScan(filteredAvailableNetworks);
            }
        } else if (mOppSubscriptionInfos.size() == 0) {
            /* check if no profile */
            logDebug("stopping scan");
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

    private int retrieveBestSubscription(List<CellInfo> results) {
        /* sort the results according to signal strength level */
        Collections.sort(results, new Comparator<CellInfo>() {
            @Override
            public int compare(CellInfo cellInfo1, CellInfo cellInfo2) {
                return getSignalLevel(cellInfo1) - getSignalLevel(cellInfo2);
            }
        });

        for (int level = PRIORITY_HIGH; level < PRIORITY_LOW; level++) {
            for (CellInfo result : results) {
                /* get subscription id for the best network scan result */
                int subId = getSubIdUsingAvailableNetworks(getMcc(result), getMnc(result), level);
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    return subId;
                }
            }
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private int getActiveOpportunisticSubId() {
        List<SubscriptionInfo> subscriptionInfos =
            mSubscriptionManager.getActiveSubscriptionInfoList();
        for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
            if (subscriptionInfo.isOpportunistic()) {
                return subscriptionInfo.getSubscriptionId();
            }
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private void disableOpportunisticModem() {
        int subId = getActiveOpportunisticSubId();
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            enableModem(subId, false);
        }
    }

    private void enableModem(int subId, boolean enable) {
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            int phoneId = SubscriptionManager.getPhoneId(subId);
            mSubscriptionBoundTelephonyManager.enableModemForSlot(phoneId, enable);
        }
    }

    public boolean containsOpportunisticSubs(ArrayList<AvailableNetworkInfo> availableNetworks) {
        if (mOppSubscriptionInfos == null) {
            logDebug("received null subscription infos");
            return false;
        }

        if (mOppSubscriptionInfos.size() > 0) {
            logDebug("opportunistic subscriptions size " + mOppSubscriptionInfos.size());
            ArrayList<AvailableNetworkInfo> filteredAvailableNetworks =
                    getFilteredAvailableNetworks(
                            (ArrayList<AvailableNetworkInfo>)availableNetworks, mOppSubscriptionInfos);
            if (filteredAvailableNetworks.size() > 0) {
                return true;
            }
        }

        return false;
    }

    public boolean isOpportunisticSubActive() {
        if (mOppSubscriptionInfos == null) {
            logDebug("received null subscription infos");
            return false;
        }

        if (mOppSubscriptionInfos.size() > 0) {
            logDebug("opportunistic subscriptions size " + mOppSubscriptionInfos.size());
            for (SubscriptionInfo subscriptionInfo : mOppSubscriptionInfos) {
                if (mSubscriptionManager.isActiveSubId(subscriptionInfo.getSubscriptionId())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void startProfileSelection(ArrayList<AvailableNetworkInfo> availableNetworks) {
        logDebug("startProfileSelection availableNetworks: " + availableNetworks);
        if (availableNetworks == null || availableNetworks.size() == 0) {
            return;
        }

        Message message = Message.obtain(mHandler, MSG_START_PROFILE_SELECTION,
                availableNetworks);
        message.sendToTarget();
    }

    /**
     * select opportunistic profile for data if passing a valid subId.
     * @param subId : opportunistic subId or SubscriptionManager.DEFAULT_SUBSCRIPTION_ID if
     *              deselecting previously set preference.
     */
    public boolean selectProfileForData(int subId) {
        if ((subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
                || (isOpprotunisticSub(subId) && isActiveSub(subId))) {
            mSubscriptionManager.setPreferredDataSubscriptionId(subId);
            mCurrentDataSubId = subId;
            return true;
        } else {
            log("Inactive sub passed for preferred data " + subId);
            return false;
        }
    }

    public int getPreferredDataSubscriptionId() {
        return mSubscriptionManager.getPreferredDataSubscriptionId();
    }

    /**
     * stop profile selection procedure
     */
    public void stopProfileSelection() {
        logDebug("stopProfileSelection");
        mNetworkScanCtlr.stopNetworkScan();
        disableOpportunisticModem();
        synchronized (mLock) {
            mAvailableNetworkInfos = null;
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
    protected void init(Context c, ONSProfileSelectionCallback profileSelectionCallback) {
        mContext = c;
        mSequenceId = START_SEQUENCE_ID;
        mProfileSelectionCallback = profileSelectionCallback;
        mTelephonyManager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionBoundTelephonyManager = mTelephonyManager.createForSubscriptionId(
                SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        mSubscriptionManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mNetworkScanCtlr = new ONSNetworkScanCtlr(mContext, mSubscriptionBoundTelephonyManager,
                mNetworkAvailableCallBack);
        updateOpportunisticSubscriptions();
        mThread = new HandlerThread(LOG_TAG);
        mThread.start();
        mHandler = new Handler(mThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_PROFILE_UPDATE:
                        synchronized (mLock) {
                            updateOpportunisticSubscriptions();
                        }
                        break;
                    case MSG_START_PROFILE_SELECTION:
                        logDebug("Msg received for profile update");
                        synchronized (mLock) {
                            checkProfileUpdate((ArrayList<AvailableNetworkInfo>) msg.obj);
                        }
                        break;
                    default:
                        log("invalid message");
                        break;
                }
            }
        };
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
