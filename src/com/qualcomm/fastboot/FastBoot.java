/* packages/apps/FastPowerOn/src/com/qualcomm/fastboot/FastBoot.java
**
** Copyright 2006, The Android Open Source Project
** Copyright (c) 2012, The Linux Foundation. All Rights Reserved.
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.qualcomm.fastboot;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.ServiceManager;
import android.os.ServiceManagerNative;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemSelectedListener;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import android.database.ContentObserver;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.HttpResponse;
import org.apache.http.impl.client.DefaultHttpClient;

import android.media.AudioManager;

public class FastBoot extends Activity {
    private static final String TAG = "FastBoot";
    private static FastBoot mFastBoot;
    private Context mFastBootContext;

    // shared pref name to check what airplane mode was before we modify it, in
    // order to restore it accurately after we return.
    private static final String SHARED_PREF_NAME_PRE_AIRPLANE = "preAirplaneMode";
    // shared pref key to check what airplane mode was before we modify it, in
    // order to restore it accurately after we return.
    private static final String SHARED_PREF_KEY_PRE_AIRPLANE = "PRE_AIRPLANE_MODE";
    // key for internal broadcast transactions - communication between
    // localservice and FastBoot ( FPO ) class.
    private static final String KEY_INTERNAL_BROADCAST_FINISH = "FinishActivity";

    // key for internal broadcasts between PowerOffBroadcastReceiver and
    // localservice.
    public static final String KEY_INTERNAL_BROADCAST_POWER_ON =
                                            "fastPowerOnResumeFromDeepSleep";

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.connectivity);

        Log.d(TAG, "Activity onCreate");
        mFastBoot = this;
        IntentFilter finishFilter = new IntentFilter();
        finishFilter.addAction(KEY_INTERNAL_BROADCAST_FINISH);
        registerReceiver(mCommunicateReceiver, finishFilter);
        startService(new Intent(this, localSerice.class));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Ensure that our process is killed. Android keeps processes in memory
        // until it decides. We need exit for sure.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    public static void restoreAirplaneMode(Context context) {
        SharedPreferences mPreAirplaneMode = context.getSharedPreferences(SHARED_PREF_NAME_PRE_AIRPLANE, MODE_PRIVATE);
        if (mPreAirplaneMode.getInt(SHARED_PREF_KEY_PRE_AIRPLANE, -1) != 0)
            return;
        Log.d(TAG, "restore airplane mode to previous status");
        // Should put the value to the database first, then send broadcast using action "ACTION_AIRPLANE_MODE_CHANGED"
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0);
        Intent intentAirplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intentAirplane.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intentAirplane.putExtra("state", false);
        context.sendBroadcast(intentAirplane);

        SharedPreferences.Editor editor = mPreAirplaneMode.edit();
        editor.putInt(SHARED_PREF_KEY_PRE_AIRPLANE, -1);
        editor.commit();
    }

    protected BroadcastReceiver mCommunicateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO Auto-generated method stub
            String action = intent.getAction();
            if (action.equals(KEY_INTERNAL_BROADCAST_FINISH)) {
                unregisterReceiver(mCommunicateReceiver);
                stopService(new Intent(FastBoot.this, localSerice.class));
                mFastBoot.finish();
            }
        }
    };


    public static class localSerice extends Service {
        private PowerManager mPm = null;
        private static boolean powerOn = false;
        private static final int SEND_AIRPLANE_MODE_BROADCAST = 1;
        private static final int SEND_BOOT_COMPLETED_BROADCAST = 2;
        private static boolean sendBroadcastDone = false;
        private ActivityManager mActivityManager = null;
        private HandlerThread mHandlerThread;
        private Handler mHandler;
        Thread sendBroadcastThread = null;
        String systemLevelProcess[] = {
            "android.process.acore",
            "android.process.media",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.deskclock",
            "sys.DeviceHealth",
            "system",
        };

        @Override
        public void onCreate() {
            super.onCreate();
            Log.d(TAG, "Activity onCreate");
            mPm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            mHandlerThread = new HandlerThread(TAG);
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper(), mHandlerCallback);

            new Thread() {
                @Override
                public void run(){
                    Log.d(TAG, "fast power off");
                    powerOffSystem();
                    IntentFilter powerButtonFilter = new IntentFilter();
                    powerButtonFilter.addAction(KEY_INTERNAL_BROADCAST_POWER_ON);
                    registerReceiver(mPowerOffReceiver, powerButtonFilter);
                }
            }.start();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        private Handler.Callback mHandlerCallback = new Handler.Callback() {
            /** {@inheritDoc}
             * @return */
            public boolean handleMessage(Message msg) {
                Log.d(TAG, "handleMessage begin in " + SystemClock.elapsedRealtime());
                switch (msg.what) {
                    case SEND_AIRPLANE_MODE_BROADCAST:
                        Log.d(TAG, "Set airplane mode begin in**** " + SystemClock.elapsedRealtime() + ", airplane mode : " + msg.arg1);
                        Intent intentAirplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                        intentAirplane.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                        intentAirplane.putExtra("state", msg.arg1 == 1);
                        sendOrderedBroadcast(intentAirplane, null, sendBroadcasResult, mHandler, 0, null, null);
                        break;
                    case SEND_BOOT_COMPLETED_BROADCAST:
                        Log.d(TAG, "Send bootCompleted begin in " + SystemClock.elapsedRealtime());
                        Intent intentBoot = new Intent(Intent.ACTION_BOOT_COMPLETED);
                        sendOrderedBroadcast(intentBoot, null, sendBroadcasResult, mHandler, 0, null, null);
                        break;
                    default:
                        sendBroadcastDone = true;
                        return false;
                }
                return true;
            }
        };

        BroadcastReceiver sendBroadcasResult = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Send Broadcast finish in " + SystemClock.elapsedRealtime());
                sendBroadcastDone = true;
            }
        };

        public void onPowerEvent(String msg) {
            // TODO: To be done
            if ("usb".equals(msg)) {
                Log.e(TAG, "observer fastboot usb event, power off the phone");
                Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
                intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mFastBoot.startActivity(intent);
            } else if ("resume".equals(msg)){
                Log.e(TAG, "observer fastboot resume event, fast power on");
                powerOnSystem(mFastBoot);
                Intent iFinish = new Intent(KEY_INTERNAL_BROADCAST_FINISH);
                sendBroadcast(iFinish);
            }
        }

        private void powerOffSystem() {
            //notify music application to pause music before kill the application
            sendBecomingNoisyIntent();
            SystemProperties.set("service.bootanim.exit", "0");
            SystemProperties.set("ctl.start", "bootanim");
            enterAirplaneMode();
            KillProcess();
            SystemClock.sleep(1000);
            mPm.goToSleep(SystemClock.uptimeMillis());
        }

        private void powerOnSystem(Context context) {
            PowerManager.WakeLock wl = mPm.newWakeLock(
                                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK|
                                        PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                        mFastBoot.getClass().getName());
            SystemProperties.set("service.bootanim.exit", "0");
            SystemProperties.set("ctl.start", "bootanim");
            wl.acquire();
            SystemClock.sleep(5000);
            SystemProperties.set("ctl.stop", "bootanim");
            mPm.wakeUp(SystemClock.uptimeMillis());
            restoreAirplaneMode(context);
            wl.release();
        }

        //send broadcast to music application to pause music
        private void sendBecomingNoisyIntent() {
            sendBroadcast(new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        }

        private void KillProcess() {
            List<ActivityManager.RunningAppProcessInfo> appProcessList = null;

            appProcessList = mActivityManager.getRunningAppProcesses();

            for (ActivityManager.RunningAppProcessInfo appProcessInfo : appProcessList) {
                int pid = appProcessInfo.pid;
                int uid = appProcessInfo.uid;
                String processName = appProcessInfo.processName;

                if (isKillableProcess(processName)) {
                    //mActivityManager.killBackgroundProcesses(processName);
                    Log.d(TAG, "process '" + processName + "' will be killed");
                    mActivityManager.forceStopPackage(processName);
                }
            }
        }

        private boolean isKillableProcess(String packageName) {
            for (String processName : systemLevelProcess) {
                if (processName.equals(packageName)) {
                    return false;
                }
            }
            String currentProcess = getApplicationInfo().processName;
            if (currentProcess.equals(packageName)) {
                return false;
            }

            // couldn't kill the live wallpaper process, if kill it, the system will set the wallpaper as the default.
            WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
            if (info != null && !TextUtils.isEmpty(packageName)
                    && packageName.equals(info.getPackageName())) {
                return false;
            }

            // couldn't kill the IME process.
            String currentInputMethod = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (!TextUtils.isEmpty(currentInputMethod)
                    && currentInputMethod.startsWith(packageName)) {
                return false;
            }
            return true;
        }

        private void sendBootCompleted(boolean wait) {
            synchronized (this) {
                sendBroadcastDone = false;
                mHandler.sendMessage(Message.obtain(mHandler, SEND_BOOT_COMPLETED_BROADCAST));
                while (wait && !sendBroadcastDone) {
                    SystemClock.sleep(100);
                }
                sendBroadcastDone = false;
            }
        }

        private void enterAirplaneMode() {
            SharedPreferences mPreAirplaneMode = getSharedPreferences(SHARED_PREF_NAME_PRE_AIRPLANE, MODE_PRIVATE);
            if (Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
                return;
            }

            SharedPreferences.Editor editor = mPreAirplaneMode.edit();
            editor.putInt(SHARED_PREF_KEY_PRE_AIRPLANE, 0);
            editor.commit();
            Settings.Global.putInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 1);

            sendBroadcastDone = false;
            mHandler.sendMessage( Message.obtain(mHandler, SEND_AIRPLANE_MODE_BROADCAST, 1, 0));
            while (!sendBroadcastDone) {
                SystemClock.sleep(100);
            }
            sendBroadcastDone = false;
        }

        private void enableShowLogo( boolean on ) {
            String disableStr = (on ? "1" : "0" );
            SystemProperties.set( "hw.showlogo.enable" , disableStr );
        }

        protected BroadcastReceiver mPowerOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(KEY_INTERNAL_BROADCAST_POWER_ON)) {
                    unregisterReceiver(mPowerOffReceiver);
                    onPowerEvent("resume");
                }
            }
        };
    }
}
