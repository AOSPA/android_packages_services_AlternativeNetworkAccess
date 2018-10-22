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
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

public class ANSServiceStateEvaluatorTest extends ANSBaseTest {
    private SSMEvaluator mANSServiceStateEvaluator;
    private MyANSServiceStateMonitor mVoiceSubMonitor;
    private MyANSServiceStateMonitor mDataSubMonitor;
    private int mDataSubId;
    private int mVoiceSubId;
    private boolean mCallbackInvoked;
    private Object mLock2 = new Object();
    private boolean isNotified = false;
    private Looper mLooper;
    ANSServiceStateMonitor.ANSServiceMonitorCallback mANSServiceMonitorCallback;
    ANSServiceStateMonitor.PhoneStateListenerImpl mPhoneStateListener;

    private class MyANSServiceStateMonitor extends ANSServiceStateMonitor {
        ANSServiceStateMonitor.ANSServiceMonitorCallback mANSServiceMonitorCallbackOverride;
        public MyANSServiceStateMonitor(Context c,
                ANSServiceStateMonitor.ANSServiceMonitorCallback serviceMonitorCallback) {
            super(c, serviceMonitorCallback);
            mANSServiceMonitorCallbackOverride = serviceMonitorCallback;
        }
    }

    public class MyANSServiceEvaluatorCallback
            implements ANSServiceStateEvaluator.ANSServiceEvaluatorCallback {
        public void onBadDataService() {
            mCallbackInvoked = true;
            setReady(true);
        }
    }

    private class SSMEvaluator extends ANSServiceStateEvaluator {
        SSMEvaluator(Context c,
                ANSServiceStateEvaluator.ANSServiceEvaluatorCallback ANSServiceEvaluatorCallback) {
            super(c, ANSServiceEvaluatorCallback);
        }

        protected void init(Context c, ANSServiceEvaluatorCallback ANSServiceEvaluatorCallback) {
            super.init(c, ANSServiceEvaluatorCallback);
            mANSServiceMonitorCallback = mServiceMonitorCallback;
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
    public void testBadService() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSServiceStateEvaluator = new SSMEvaluator(mContext,
                        new MyANSServiceEvaluatorCallback());
                mANSServiceStateEvaluator.startEvaluation(5, 6);
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();

            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mCallbackInvoked = false;

        // Testing startEvaluation, should get onBadDataService invoked.
        ((SSMEvaluator)(mANSServiceStateEvaluator)).mDataServiceWaitTimer.onAlarm();
        mANSServiceMonitorCallback.onServiceMonitorUpdate(6,
                ANSServiceStateMonitor.SERVICE_STATE_GOOD);
        mANSServiceMonitorCallback.onServiceMonitorUpdate(5,
                ANSServiceStateMonitor.SERVICE_STATE_NO_SERVICE);
        waitUntilReady();
        assertTrue(mCallbackInvoked);
    }


    @Test
    public void testStopEvaluation() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mANSServiceStateEvaluator = new SSMEvaluator(mContext,
                        new MyANSServiceEvaluatorCallback());
                mANSServiceStateEvaluator.startEvaluation(5, 6);
                mLooper = Looper.myLooper();
                setReady(true);
                Looper.loop();
            }
        }).start();

        // Wait till initialization is complete.
        waitUntilReady();
        mReady = false;
        mCallbackInvoked = false;

        // Testing stopEvaluation, should not get onBadDataService invoked.
        ((SSMEvaluator)(mANSServiceStateEvaluator)).mDataServiceWaitTimer.onAlarm();
        mANSServiceStateEvaluator.stopEvaluation();
        mANSServiceMonitorCallback.onServiceMonitorUpdate(6,
                ANSServiceStateMonitor.SERVICE_STATE_GOOD);
        mANSServiceMonitorCallback.onServiceMonitorUpdate(5,
                ANSServiceStateMonitor.SERVICE_STATE_NO_SERVICE);
        waitUntilReady(100);
        assertFalse(mCallbackInvoked);
    }
}
