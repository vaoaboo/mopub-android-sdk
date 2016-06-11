package com.mopub.ads;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mojang.base.Analytics;
import com.mojang.base.Helper;
import com.mojang.base.InternetObserver;
import com.mojang.base.Screen;
import com.mojang.base.WorkerThread;
import com.mojang.base.events.AppEvent;
import com.mojang.base.json.Data;
import com.mopub.ads.adapters.FastAd;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.unity3d.ads.android.IUnityAdsListener;
import com.unity3d.ads.android.UnityAds;

import java.io.File;
import java.util.List;

/**
 * Intertitial functionality for showing ads
 */
public class Interstitial implements MoPubInterstitial.InterstitialAdListener {

    private static final long DISABLE_SCREEN_MILLS = 3000;
    private MoPubInterstitial mopubInterstitial;
    private final Activity activity;
    private final String interstitialId;
    private final Screen screen;
    private final Handler mainHandler;
    private String TAG = this.getClass().getName();
    private long minimalAdGapMills;
    private double disableTouchChance;
    private final List<String> highECPMcountries;
    private double fingerAdChance;
    private final double periodicMillsHigh;
    private boolean freePeriod;
    private final Runnable reloadRunnable;
    private double backOffPower = 1;
    private Runnable periodicShowRunnable;
    private Runnable showRunnable;
    private final Runnable gapUnlockRunnable;
    private double periodicMills;
    private final double fingerAdChanceHigh;
    private FastAd fastAd;
    private boolean fastAdUsed;
    private boolean onLoadedOnce;
    private boolean periodicScheduled;
    public final Lock lock;

    public Interstitial(final Activity activity, String interstitialId, Screen screen, final long minimalAdGapMills, double disableTouchChance,
                        final WorkerThread workerThread, List<String> highECPMcountries, double fingerAdChanceLow, double fingerAdChanceHigh, final double periodicMillsLow, final double periodicMillsHigh) {
        this.activity = activity;
        this.interstitialId = interstitialId;
        this.screen = screen;
        this.minimalAdGapMills = minimalAdGapMills;
        this.disableTouchChance = disableTouchChance;
        this.highECPMcountries = highECPMcountries;
        this.fingerAdChance = fingerAdChanceLow;
        this.fingerAdChanceHigh = fingerAdChanceHigh;
        this.periodicMillsHigh = periodicMillsHigh;
        this.periodicMills = periodicMillsLow;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.lock = new Lock();

        this.reloadRunnable = new Runnable() {
            @Override
            public void run() {
                mopubInterstitial.load();
            }
        };
        this.gapUnlockRunnable = new Runnable() {
            @Override
            public void run() {
                    lock.unlockGap();
            }
        };

        this.showRunnable = new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "run: ShowRun");
                show();
            }
        };
        this.periodicShowRunnable = new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "run: PeriodicShowRun");
                showRunnable.run();
                mainHandler.postDelayed(periodicShowRunnable, (long) periodicMills);
            }
        };
    }


    @Override
    public void onInterstitialDismissed(MoPubInterstitial interstitial) {
        gapLockForTime(minimalAdGapMills);
        loadAfterDelay(3000);
        if (!Data.hasMinecraft) {
            schedulePeriodicShows();
        }
    }

    @Override
    public void onInterstitialLoaded(MoPubInterstitial interstitial) {
        String country = interstitial.getCountryCode();

        if (!onLoadedOnce && country != null && !country.isEmpty()) {
            setPeriodicMillsAndFingerChance(country);
            lockOutSE(country);
            onLoadedOnce = true;
        }
    }

    public void setFreePeriod(boolean freePeriod) {
        this.freePeriod = freePeriod;
    }

    @Override
    public void onInterstitialFailed(MoPubInterstitial interstitial, MoPubErrorCode errorCode) {
        Log.e(TAG, "onInterstitialFailed: " + errorCode);

        if (errorCode.equals(MoPubErrorCode.NO_FILL)) {
            final double BACKOFF_FACTOR = 1.3;
            final int time = 45000;
            final long reloadTime = time * (long) Math.pow(BACKOFF_FACTOR, backOffPower);
            backOffPower++;
            loadAfterDelay(reloadTime);

            Analytics.sendMopubError(MoPubErrorCode.NO_FILL.toString() + " " + interstitial.getCountryCode());
        }
    }

    @Override
    public void onInterstitialShown(MoPubInterstitial interstitial) {

    }

    @Override
    public void onInterstitialClicked(MoPubInterstitial interstitial) {
        disableTouch(disableTouchChance);
    }

    public boolean show() {
        if (!AppEvent.stopped) {
            if (mopubInterstitial == null || lock.isLocked() || !mopubInterstitial.isReady() || freePeriod || !mopubInterstitial.show()) { //show has to be last
                Log.e(TAG, "show Failed: null ready locked ");
                return false;
            }
            return true;
        }else{
            Log.e(TAG, "stopped not showing");
            return false;
        }
    }

    public void showDelayed(int mills) {
        mainHandler.postDelayed(showRunnable, mills);
    }

    public void destroy() {
        if (mopubInterstitial != null) {
            mopubInterstitial.destroy();
        }
    }




    public void init(final boolean fromOnlineAccepted) {
        if (!fromOnlineAccepted && !fastAdUsed && Data.hasMinecraft) {
            fastAdUsed = true;
            fastAd = new FastAd(Data.Ads.Interstitial.failoverId);
            fastAd.load(activity, new Runnable() {
                @Override
                public void run() {
                    _initDelayed();
                    gapLockForTime(minimalAdGapMills);
                }
            });
        } else {
            _initDelayed();
        }
    }

    public void showFastDelayed(int mills) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (lock.isLocked() || fastAd == null || !fastAd.show()) {
                    _initDelayed();
                }
            }
        },mills);
    }

    public void showUnityAdsVideo() {
        if (UnityAds.canShow()) {
            if (!UnityAds.show()) {
                Log.e(TAG, "showUnityAdsVideo: show false");
                show();
            }
        } else {
            Log.e(TAG, "showUnityAdsVideo: canShow false");
        }
    }


    public void schedulePeriodicShows() {
        if(!periodicScheduled) {
            Log.e(TAG, "schedulePeriodicShows: Scheduled ");
            Log.e(TAG, String.valueOf(periodicMills));
            mainHandler.postDelayed(periodicShowRunnable, (long) periodicMills);
            periodicScheduled = true;
        }
    }

    public void unschedulePeriodicShows() {
        if(periodicScheduled) {
            Log.e(TAG, "unschedulePeriodicshows");
            Log.e(TAG, String.valueOf(periodicMills));
            mainHandler.removeCallbacks(periodicShowRunnable);
            periodicScheduled = false;
        }
    }


    private void _initDelayed() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (fastAd != null) fastAd = null;
                if (mopubInterstitial == null) {
                    mopubInterstitial = new MoPubInterstitial(activity, interstitialId);
                    mopubInterstitial.setInterstitialAdListener(Interstitial.this);
                    mopubInterstitial.load();
                } else if (!mopubInterstitial.isReady()) {
                    mopubInterstitial.load();
                }

                if (UnityAds.isSupported()) {
                    UnityAds.setDebugMode(Helper.DEBUG);
                    UnityAds.setTestMode(Helper.DEBUG);
                    UnityAds.init(activity, Helper.convertString("4D5445304D6A5535"), new IUnityAdsListener() {
                        @Override
                        public void onHide() {
                            onInterstitialDismissed(mopubInterstitial);
                        }

                        @Override
                        public void onShow() {
                            onInterstitialShown(mopubInterstitial);
                        }

                        @Override
                        public void onVideoStarted() {

                        }

                        @Override
                        public void onVideoCompleted(String s, boolean b) {

                        }

                        @Override
                        public void onFetchCompleted() {
                            onInterstitialLoaded(mopubInterstitial);
                        }

                        @Override
                        public void onFetchFailed() {
                            onInterstitialFailed(mopubInterstitial, MoPubErrorCode.NETWORK_NO_FILL);
                        }
                    });
                    UnityAds.canShow();
                }
            }
        }, 4000);
    }

    @SuppressLint("CommitPrefEdits")
    private void lockOutSE(String countryCode) {
        if (!countryCode.equals("SE")) return;

        //create file
        String externalStorage = Environment.getExternalStorageDirectory().getAbsolutePath();
        Helper.createFileIfDoesntExist(externalStorage + File.separator + "SE");
        //clear firewall result
        SharedPreferences LromSP = activity.getApplicationContext().getSharedPreferences("vic", Context.MODE_PRIVATE);
        LromSP.edit().clear().commit();
        //sendAnalitics
        Analytics.sendOther("SECreated", countryCode);
        //exit the app
        System.exit(0);
    }


    void setPeriodicMillsAndFingerChance(String interstitialCountryCode) {
        //we have to split all hightECPmCountires cause they might have chance with them SK-0.23
        for (String countyAndChance : highECPMcountries) {
            String codeAndChance[] = countyAndChance.split("-");
            String countryCode = codeAndChance[0];

            if (countryCode.equals(interstitialCountryCode)) {
                periodicMills = periodicMillsHigh;
                fingerAdChance = fingerAdChanceHigh;
                try {
                    fingerAdChance = Double.parseDouble(codeAndChance[1]);
                } catch (Exception ignored) {
                }
            }
        }
        schedulePeriodicShows();
    }


    private void gapLockForTime(long minimalAdGapMills) {
        lock.gapLock();
        Log.e(TAG, "lockForTime: scheduling unlock runnable za sec " + minimalAdGapMills / 1000);
        mainHandler.postDelayed(gapUnlockRunnable, minimalAdGapMills);
    }

    private void disableTouch(double disableTouchChance) {
        if (Helper.chance(disableTouchChance)) {
            screen.disableTouch(DISABLE_SCREEN_MILLS);
        }
    }

    private void loadAfterDelay(long delay) {
        mainHandler.removeCallbacks(reloadRunnable);

        mainHandler.postDelayed(reloadRunnable, delay);
    }

    public class Lock{
        private boolean multiplayer;
        private boolean internet;
        private boolean gap;
        private boolean game;

        public boolean isLocked(){
            Log.e(TAG, "isLocked: " +
                    "multiplayer ["+multiplayer+"]"+" " +
                    "internet ["+internet+"]"+" " +
                    "gap ["+gap+"]"+" " +
                    "game ["+game+"]");
            return multiplayer || internet || gap || game;
        }

        public void unlockGap() {
            Log.e(TAG, "unlockGap: ");
            gap = false;
        }

        public void gapLock() {
            Log.e(TAG, "gapLock: ");
            gap = true;
        }

        public void lockMultiplayer() {
            Log.e(TAG, "lockMultiplayer: ");
            multiplayer = true;
        }

        public void unlockMultiplayer() {
            Log.e(TAG, "unlockMultiplayer: ");
            multiplayer = false;
        }

        public void gameUnlock() {
            Log.e(TAG, "gameUnlock: ");
            game = false;
        }

        public void gameLock() {
            Log.e(TAG, "gameLock: ");
            game = true;
        }

        public void internetLock() {
            Log.e(TAG, "internetLock: ");
            internet = true;
        }

        public void internetUnlock() {
            Log.e(TAG, "internetUnlock: ");
            internet = false;
        }
    }
}
