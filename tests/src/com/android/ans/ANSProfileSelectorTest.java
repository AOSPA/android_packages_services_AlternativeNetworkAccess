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
 * limitations under the License
 */
package com.android.ans;

import static org.junit.Assert.*;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.NetworkScan;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class ANSProfileSelectorTest extends ANSBaseTest {
    private MyANSProfileSelector mANSProfileSelector;
    private boolean testFailed;
    private boolean mCallbackInvoked;
    private int mDataSubId;
    ANSServiceStateMonitor.PhoneStateListenerImpl mPhoneStateListener;
    @Mock
    ANSNetworkScanCtlr mANSNetworkScanCtlr;
    private Looper mLooper;
    private static final String TAG = "ANSProfileSelectorTest";

    MyANSProfileSelector.ANSProfileSelectionCallback mANSProfileSelectionCallback =
            new MyANSProfileSelector.ANSProfileSelectionCallback() {
        public void onProfileSelectionDone(int dataSubId, int voiceSubId) {
            Rlog.d(TAG, "onProfileSelectionDone " + dataSubId + " " + voiceSubId);
            mCallbackInvoked = true;
            setReady(true);
        }
    };

    public class MyANSProfileSelector extends ANSProfileSelector {
        public ANSServiceStateMonitor.ANSServiceMonitorCallback mServiceMonitorCallbackCpy;
        public SubscriptionManager.OnOpportunisticSubscriptionsChangedListener mProfileChngLstnrCpy;
        public BroadcastReceiver mProfileSelectorBroadcastReceiverCpy;
        public ANSNetworkScanCtlr.NetworkAvailableCallBack mNetworkAvailableCallBackCpy;

        public MyANSProfileSelector(Context c,
                MyANSProfileSelector.ANSProfileSelectionCallback aNSProfileSelectionCallback) {
            super(c, aNSProfileSelectionCallback);
        }

        public void triggerProfileUpdate() {
            mHandler.sendEmptyMessage(1);
        }

        protected void init(Context c,
                MyANSProfileSelector.ANSProfileSelectionCallback aNSProfileSelectionCallback) {
            super.init(c, aNSProfileSelectionCallback);
            this.mSubscriptionManager = ANSProfileSelectorTest.this.mSubscriptionManager;
            mServiceMonitorCallbackCpy = mServiceMonitorCallback;
            mProfileChngLstnrCpy = mProfileChangeListener;
            mProfileSelectorBroadcastReceiverCpy = mProfileSelectorBroadcastReceiver;
            mNetworkAvailableCallBackCpy = mNetworkAvailableCallBack;
            mNetworkScanCtlr = mANSNetworkScanCtlr;
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp("ANSTest");
        mLooper = null;
        MockitoAnnotations.initMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        if (mLooper != null) {
            mLooper.quit();
            mLooper.getThread().join();
        }
    }

    @Test
    public void testStartProfileSelectionWithNoOpportunisticSub() {
        List<CellInfo> results2 = new ArrayList<CellInfo>();
        CellIdentityLte cellIdentityLte = new CellIdentityLte(310, 210, 1, 1, 1);
        CellInfoLte cellInfoLte = new CellInfoLte();
        cellInfoLte.setCellIdentity(cellIdentityLte);
        results2.add((CellInfo)cellInfoLte);

        mReady = false;
        mCallbackInvoked = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSProfileSelector = new MyANSProfileSelector(mContext,
                        mANSProfileSelectionCallback);
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();
            }
        }).start();

        doReturn(true).when(mANSNetworkScanCtlr).startFastNetworkScan(anyObject());
        doReturn(null).when(mSubscriptionManager).getOpportunisticSubscriptions();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing startProfileSelection without any oppotunistic data.
        // should not get any callback invocation.
        mANSProfileSelector.startProfileSelection();
        waitUntilReady(100);
        assertFalse(mCallbackInvoked);
    }

    @Test
    public void testStartProfileSelectionSuccess() {
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<SubscriptionInfo>();
        SubscriptionInfo subscriptionInfo = new SubscriptionInfo(5, "", 1, "TMO", "TMO", 1, 1,
                "123", 1, null, "310", "210", "", false, null, "1");
        SubscriptionInfo subscriptionInfo2 = new SubscriptionInfo(5, "", 1, "TMO", "TMO", 1, 1,
                "123", 1, null, "310", "211", "", false, null, "1");
        subscriptionInfoList.add(subscriptionInfo);

        List<CellInfo> results2 = new ArrayList<CellInfo>();
        CellIdentityLte cellIdentityLte = new CellIdentityLte(310, 210, 1, 1, 1);
        CellInfoLte cellInfoLte = new CellInfoLte();
        cellInfoLte.setCellIdentity(cellIdentityLte);
        results2.add((CellInfo)cellInfoLte);

        mReady = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSProfileSelector = new MyANSProfileSelector(mContext,
                        new MyANSProfileSelector.ANSProfileSelectionCallback() {
                    public void onProfileSelectionDone(int dataSubId, int voiceSubId) {
                        Rlog.d(TAG, "onProfileSelectionDone " + dataSubId + " " + voiceSubId);
                        mDataSubId = dataSubId;
                        setReady(true);
                    }
                });
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();
            }
        }).start();

        doReturn(subscriptionInfoList).when(mSubscriptionManager).getOpportunisticSubscriptions();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;
        mDataSubId = -1;

        // Testing startProfileSelection with oppotunistic sub.
        // On success onProfileSelectionDone must get invoked.
        mANSProfileSelector.startProfileSelection();
        assertFalse(mReady);
        mANSProfileSelector.mNetworkAvailableCallBackCpy.onNetworkAvailability(results2);
        Intent callbackIntent = new Intent(MyANSProfileSelector.ACTION_SUB_SWITCH);
        callbackIntent.putExtra("sequenceId", 1);
        callbackIntent.putExtra("subId", 5);
        assertFalse(mReady);
        mANSProfileSelector.mProfileSelectorBroadcastReceiverCpy.onReceive(mContext, callbackIntent);
        assertFalse(mReady);
        mANSProfileSelector.mServiceMonitorCallbackCpy.onServiceMonitorUpdate(5,
                ANSServiceStateMonitor.SERVICE_STATE_GOOD);
        waitUntilReady();
        assertEquals(5, mDataSubId);
    }
}
