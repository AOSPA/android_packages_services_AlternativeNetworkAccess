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

import static com.android.ans.ANSServiceStateMonitor.SERVICE_STATE_BAD;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.ISub;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
public class ANSServiceStateMonitorTest extends ANSBaseTest {
    private static final String TAG = "ANSServiceStateMonitorTest";
    MyANSServiceStateMonitor mServiceStateMonitor;
    private boolean mIsCallBackReady;
    private int mSubId;
    private int mState;
    private Looper mLooper;

    private class MyANSServiceStateMonitor extends ANSServiceStateMonitor {
        public MyANSServiceStateMonitor(Context c,
                ANSServiceStateMonitor.ANSServiceMonitorCallback serviceMonitorCallback) {
            super(c, serviceMonitorCallback);
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp("ANSTest");
        mLooper = null;
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        if (mLooper != null) {
            mLooper.quit();
            mLooper.getThread().join();
        }

    }

    @Test @SmallTest
    public void testIsFatalFailCause() {
        boolean ret = ANSServiceStateMonitor.isFatalFailCause("INVALID_TRANSACTION_ID");
        assertFalse(ret);

        ret = ANSServiceStateMonitor.isFatalFailCause("OPERATOR_BARRED");
        assertTrue(ret);
    }


    @Test
    public void testOutOfService() {
        mIsCallBackReady = false;
        /* service monitor callback will get called for service state change on a particular subId. */
        ANSServiceStateMonitor.ANSServiceMonitorCallback serviceMonitorCallback =
                new ANSServiceStateMonitor.ANSServiceMonitorCallback() {
                    @Override
                    public void onServiceMonitorUpdate(int subId, int state) {
                        if (!mIsCallBackReady) {
                            return;
                        }
                        Rlog.d(TAG,"testOutOfService subId: " + subId + " state: "
                                + ANSServiceStateMonitor.getStateString(state));
                        mSubId = subId;
                        mState = state;
                        setReady(true);
                    }
                };

        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mServiceStateMonitor =
                        new MyANSServiceStateMonitor(mContext, serviceMonitorCallback);
                mServiceStateMonitor.startListeningForNetworkConditionChange(10);
                mServiceStateMonitor.getPhoneStateListener(10);
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();

            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing out of service, should get onServiceMonitorUpdate invoked
        // ANSServiceStateMonitor.SERVICE_STATE_NO_SERVICE
        ServiceState serviceState = new ServiceState();
        serviceState.setDataRegState(ServiceState.STATE_OUT_OF_SERVICE);
        mIsCallBackReady = true;
        mServiceStateMonitor.getPhoneStateListener(10).onServiceStateChanged(serviceState);
        waitUntilReady();
        assertEquals(10, mSubId);
        assertEquals(ANSServiceStateMonitor.SERVICE_STATE_NO_SERVICE, mState);
    }

    @Test
    public void testInService() {
        mIsCallBackReady = false;
        ANSServiceStateMonitor.ANSServiceMonitorCallback serviceMonitorCallback =
                new ANSServiceStateMonitor.ANSServiceMonitorCallback() {
                    @Override
                    public void onServiceMonitorUpdate(int subId, int state) {
                        if (!mIsCallBackReady) {
                            return;
                        }
                        Rlog.d(TAG,"testInService subId: " + subId + " state: "
                                + ANSServiceStateMonitor.getStateString(state));
                        mSubId = subId;
                        mState = state;
                        setReady(true);
                    }
                };

        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mServiceStateMonitor = new MyANSServiceStateMonitor(mContext,
                        serviceMonitorCallback);
                mServiceStateMonitor.startListeningForNetworkConditionChange(10);
                mServiceStateMonitor.getPhoneStateListener(10);
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();

            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing in good network condition, should get onServiceMonitorUpdate invoked
        // ANSServiceStateMonitor.SERVICE_STATE_GOOD
        ServiceState serviceState = new ServiceState();
        serviceState.setDataRegState(ServiceState.STATE_IN_SERVICE);
        mIsCallBackReady = true;
        mServiceStateMonitor.getPhoneStateListener(10).onServiceStateChanged(serviceState);
        waitUntilReady();
        assertEquals(10, mSubId);
        assertEquals(ANSServiceStateMonitor.SERVICE_STATE_GOOD, mState);
    }

    @Test
    public void testbadSignalStrength() {
        mIsCallBackReady = false;
        ANSServiceStateMonitor.ANSServiceMonitorCallback serviceMonitorCallback =
                new ANSServiceStateMonitor.ANSServiceMonitorCallback() {
                    @Override
                    public void onServiceMonitorUpdate(int subId, int state) {
                        if (!mIsCallBackReady) {
                            return;
                        }
                        Rlog.d(TAG,"testbadSignalStrength subId: " + subId + " state: "
                                + ANSServiceStateMonitor.getStateString(state));
                        mSubId = subId;
                        mState = state;
                        setReady(true);
                    }
                };

        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mServiceStateMonitor = new MyANSServiceStateMonitor(mContext,
                        serviceMonitorCallback);
                mServiceStateMonitor.startListeningForNetworkConditionChange(10);
                mServiceStateMonitor.getPhoneStateListener(10);
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();

            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing bad signal strength, should get onServiceMonitorUpdate invoked
        // ANSServiceStateMonitor.SERVICE_STATE_BAD
        SignalStrength signalStrength = new SignalStrength(-1, -1, 0, 0, 0, 0, 0,
                -140, -140, 0, 0, 0, 0);
        mServiceStateMonitor.getPhoneStateListener(10).onSignalStrengthsChanged(signalStrength);
        ServiceState serviceState = new ServiceState();
        serviceState.setDataRegState(ServiceState.STATE_IN_SERVICE);
        Rlog.d(TAG, serviceState.toString());
        mIsCallBackReady = true;
        mServiceStateMonitor.getPhoneStateListener(10).onServiceStateChanged(serviceState);
        waitUntilReady();
        assertEquals(10, mSubId);
        assertEquals(ANSServiceStateMonitor.SERVICE_STATE_BAD, mState);
    }

    @Test
    public void testGoodSignalStrength() {
        mIsCallBackReady = false;
        ANSServiceStateMonitor.ANSServiceMonitorCallback serviceMonitorCallback =
                new ANSServiceStateMonitor.ANSServiceMonitorCallback() {
                    @Override
                    public void onServiceMonitorUpdate(int subId, int state) {
                        Rlog.d(TAG,"testGoodSignalStrength subId: " + subId + " state: "
                                + ANSServiceStateMonitor.getStateString(state)
                                + "mIsCallBackReady: " + mIsCallBackReady);
                        if (!mIsCallBackReady) {
                            return;
                        }
                        mSubId = subId;
                        mState = state;
                        setReady(true);
                    }
                };

        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mServiceStateMonitor = new MyANSServiceStateMonitor(mContext,
                        serviceMonitorCallback);
                mServiceStateMonitor.startListeningForNetworkConditionChange(10);
                mServiceStateMonitor.getPhoneStateListener(10);
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();

            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;

        // Testing good signal strength, should get onServiceMonitorUpdate invoked
        // ANSServiceStateMonitor.SERVICE_STATE_GOOD
        SignalStrength signalStrength = new SignalStrength(30, 0, 0, 0, 0, 0, 0, 30,
                -95, -95, 131, 0, 0);
        mServiceStateMonitor.getPhoneStateListener(10).onSignalStrengthsChanged(signalStrength);
        ServiceState serviceState = new ServiceState();
        serviceState.setDataRegState(ServiceState.STATE_IN_SERVICE);
        Rlog.d(TAG, serviceState.toString());
        mIsCallBackReady = true;
        mServiceStateMonitor.getPhoneStateListener(10).onServiceStateChanged(serviceState);
        waitUntilReady();
        assertEquals(10, mSubId);
        assertEquals(ANSServiceStateMonitor.SERVICE_STATE_GOOD, mState);
    }
}
