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

import android.os.Looper;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.NetworkScan;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class ANSNetworkScanCtlrTest extends ANSBaseTest {
    private ANSNetworkScanCtlr mANSNetworkScanCtlr;
    private NetworkScan mNetworkScan;
    private List<CellInfo> mResults;
    private int mError;
    private boolean mCallbackInvoked;
    private Looper mLooper;

    @Before
    public void setUp() throws Exception {
        super.setUp("ANSTest");
        mLooper = null;
        mNetworkScan = new NetworkScan(1, 1);
        doReturn(mNetworkScan).when(mMockTelephonyManager).requestNetworkScan(anyObject(), anyObject());
    }

    @After
    public void tearDown() throws Exception {
        if (mLooper != null) {
            mLooper.quit();
            mLooper.getThread().join();
        }
        super.tearDown();
    }

    @Test
    public void testStartFastNetworkScan() {
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<SubscriptionInfo>();
        SubscriptionInfo subscriptionInfo = new SubscriptionInfo(1, "", 1, "TMO", "TMO", 1, 1,
                "123", 1, null, "310", "210", "", false, null, "1");
        subscriptionInfoList.add(subscriptionInfo);
        List<CellInfo> expectedResults = new ArrayList<CellInfo>();
        CellIdentityLte cellIdentityLte = new CellIdentityLte(310, 210, 1, 1, 1);
        CellInfoLte cellInfoLte = new CellInfoLte();
        cellInfoLte.setCellIdentity(cellIdentityLte);
        expectedResults.add((CellInfo)cellInfoLte);
        mReady = false;

        // initializing ANSNetworkScanCtlr
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSNetworkScanCtlr = new ANSNetworkScanCtlr(mContext, mMockTelephonyManager,
                        new ANSNetworkScanCtlr.NetworkAvailableCallBack() {
                        @Override
                        public void onNetworkAvailability(List<CellInfo> results) {
                            mResults = results;
                            setReady(true);
                        }

                        public void onError(int error) {
                            setReady(true);
                        }
                    });

                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();
            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing startFastNetworkScan, onNetworkAvailability should be called with expectedResults
        mANSNetworkScanCtlr.startFastNetworkScan(subscriptionInfoList);
        mANSNetworkScanCtlr.mNetworkScanCallback.onResults(expectedResults);
        waitUntilReady(100);
        assertEquals(expectedResults, mResults);
    }

    @Test
    public void testStartFastNetworkScanFail() {
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<SubscriptionInfo>();
        SubscriptionInfo subscriptionInfo = new SubscriptionInfo(1, "", 1, "TMO", "TMO", 1, 1,
                "123", 1, null, "310", "210", "", false, null, "1");
        subscriptionInfoList.add(subscriptionInfo);
        List<CellInfo> expectedResults = new ArrayList<CellInfo>();
        CellIdentityLte cellIdentityLte = new CellIdentityLte(310, 210, 1, 1, 1);
        CellInfoLte cellInfoLte = new CellInfoLte();
        cellInfoLte.setCellIdentity(cellIdentityLte);
        expectedResults.add((CellInfo)cellInfoLte);
        mReady = false;
        mError = NetworkScan.SUCCESS;

        // initializing ANSNetworkScanCtlr
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSNetworkScanCtlr = new ANSNetworkScanCtlr(mContext, mMockTelephonyManager,
                        new ANSNetworkScanCtlr.NetworkAvailableCallBack() {
                        @Override
                        public void onNetworkAvailability(List<CellInfo> results) {
                            setReady(true);
                        }

                        @Override
                        public void onError(int error) {
                            mError = error;
                            setReady(true);
                        }
                    });

                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();

            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing startFastNetworkScan, onError should be called with ERROR_INVALID_SCAN
        mANSNetworkScanCtlr.startFastNetworkScan(subscriptionInfoList);
        mANSNetworkScanCtlr.mNetworkScanCallback.onError(NetworkScan.ERROR_INVALID_SCAN);
        waitUntilReady(100);
        assertEquals(NetworkScan.ERROR_INVALID_SCAN, mError);
    }

    @Test
    public void testStartSlowNetworkScan() {
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<SubscriptionInfo>();
        SubscriptionInfo subscriptionInfo = new SubscriptionInfo(1, "", 1, "TMO", "TMO", 1, 1,
                "123", 1, null, "310", "210", "", false, null, "1");
        subscriptionInfoList.add(subscriptionInfo);
        List<CellInfo> expectedResults = new ArrayList<CellInfo>();
        CellIdentityLte cellIdentityLte = new CellIdentityLte(310, 210, 1, 1, 1);
        CellInfoLte cellInfoLte = new CellInfoLte();
        cellInfoLte.setCellIdentity(cellIdentityLte);
        expectedResults.add((CellInfo)cellInfoLte);
        mReady = false;

        // initializing ANSNetworkScanCtlr
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSNetworkScanCtlr = new ANSNetworkScanCtlr(mContext, mMockTelephonyManager,
                        new ANSNetworkScanCtlr.NetworkAvailableCallBack() {
                        @Override
                        public void onNetworkAvailability(List<CellInfo> results) {
                            mResults = results;
                            setReady(true);
                        }

                        public void onError(int error) {
                            setReady(true);
                        }
                    });

                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();

            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing startSlowNetworkScan, onNetworkAvailability should be called with expectedResults
        mANSNetworkScanCtlr.startSlowNetworkScan(subscriptionInfoList);
        mANSNetworkScanCtlr.mNetworkScanCallback.onResults(expectedResults);
        waitUntilReady(100);
        assertEquals(expectedResults, mResults);
    }

    @Test
    public void testStopNetworkScan() {
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<SubscriptionInfo>();
        SubscriptionInfo subscriptionInfo = new SubscriptionInfo(1, "", 1, "TMO", "TMO", 1, 1,
                "123", 1, null, "310", "210", "", false, null, "1");
        subscriptionInfoList.add(subscriptionInfo);
        List<CellInfo> expectedResults = new ArrayList<CellInfo>();
        CellIdentityLte cellIdentityLte = new CellIdentityLte(310, 210, 1, 1, 1);
        CellInfoLte cellInfoLte = new CellInfoLte();
        cellInfoLte.setCellIdentity(cellIdentityLte);
        expectedResults.add((CellInfo)cellInfoLte);
        mCallbackInvoked = false;
        mReady = false;

        // initializing ANSNetworkScanCtlr
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSNetworkScanCtlr = new ANSNetworkScanCtlr(mContext, mMockTelephonyManager,
                        new ANSNetworkScanCtlr.NetworkAvailableCallBack() {
                        @Override
                        public void onNetworkAvailability(List<CellInfo> results) {
                            mCallbackInvoked = true;
                            setReady(true);
                        }

                        public void onError(int error) {
                            mCallbackInvoked = true;
                        }
                    });

                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();
            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing stopNetworkScan, should not get any callback invocation after stopNetworkScan.
        mANSNetworkScanCtlr.startSlowNetworkScan(subscriptionInfoList);
        mANSNetworkScanCtlr.stopNetworkScan();
        mANSNetworkScanCtlr.mNetworkScanCallback.onResults(expectedResults);
        waitUntilReady(100);
        assertFalse(mCallbackInvoked);
    }
}
