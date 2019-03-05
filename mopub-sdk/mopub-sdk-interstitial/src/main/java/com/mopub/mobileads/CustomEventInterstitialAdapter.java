// Copyright 2018-2019 Twitter, Inc.
// Licensed under the MoPub SDK License Agreement
// http://www.mopub.com/legal/sdk-license-agreement/

package com.mopub.mobileads;

import android.content.Context;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.mopub.ads.Proxy;
import com.mopub.common.AdReport;
import com.mopub.common.Constants;
import com.mopub.common.Preconditions;
import com.mopub.common.VisibleForTesting;
import com.mopub.common.logging.MoPubLog;
import com.mopub.mobileads.CustomEventInterstitial;
import com.mopub.mobileads.CustomEventInterstitial.CustomEventInterstitialListener;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.factories.CustomEventInterstitialFactory;

import java.util.Map;
import java.util.TreeMap;

import static com.mopub.common.DataKeys.AD_REPORT_KEY;
import static com.mopub.common.DataKeys.BROADCAST_IDENTIFIER_KEY;

import static com.mopub.common.logging.MoPubLog.SdkLogEvent.CUSTOM;
import static com.mopub.common.logging.MoPubLog.SdkLogEvent.CUSTOM_WITH_THROWABLE;
import static com.mopub.mobileads.MoPubErrorCode.ADAPTER_NOT_FOUND;
import static com.mopub.mobileads.MoPubErrorCode.NETWORK_TIMEOUT;
import static com.mopub.mobileads.MoPubErrorCode.UNSPECIFIED;

public class CustomEventInterstitialAdapter implements CustomEventInterstitialListener {
    public static final int DEFAULT_INTERSTITIAL_TIMEOUT_DELAY = Constants.THIRTY_SECONDS_MILLIS;

    private final MoPubInterstitial mMoPubInterstitial;
    private boolean mInvalidated;
    private CustomEventInterstitialAdapterListener mCustomEventInterstitialAdapterListener;
    private CustomEventInterstitial mCustomEventInterstitial;
    private Context mContext;
    private Map<String, Object> mLocalExtras;
    private Map<String, String> mServerExtras;
    private long mBroadcastIdentifier;
    private final Handler mHandler;
    private final Runnable mTimeout;
    private Proxy mProxy;

    public CustomEventInterstitialAdapter(@NonNull final MoPubInterstitial moPubInterstitial,
            @NonNull final String className,
            @NonNull final Map<String, String> serverExtras,
            long broadcastIdentifier,
            @Nullable AdReport adReport) {
        Preconditions.checkNotNull(serverExtras);
        mHandler = new Handler();
        mMoPubInterstitial = moPubInterstitial;
        mBroadcastIdentifier = broadcastIdentifier;
        mContext = mMoPubInterstitial.getActivity();
        mTimeout = new Runnable() {
            @Override
            public void run() {
                MoPubLog.log(CUSTOM, "CustomEventInterstitialAdapter() failed with code " +
                        NETWORK_TIMEOUT.getIntCode() + " and message " + NETWORK_TIMEOUT);
                onInterstitialFailed(NETWORK_TIMEOUT);
                invalidate();
            }
        };

        MoPubLog.log(CUSTOM,  "Attempting to invoke custom event: " + className);
        try {
            mCustomEventInterstitial = CustomEventInterstitialFactory.create(className);
        } catch (Exception exception) {
            MoPubLog.log(CUSTOM_WITH_THROWABLE, "CustomEventInterstitialFactory.create() " +
                    "failed with exception", exception);
            mMoPubInterstitial.onCustomEventInterstitialFailed(ADAPTER_NOT_FOUND);
            return;
        }

        mServerExtras = new TreeMap<String, String>(serverExtras);
        mLocalExtras = mMoPubInterstitial.getLocalExtras();
        if (mMoPubInterstitial.getLocation() != null) {
            mLocalExtras.put("location", mMoPubInterstitial.getLocation());
        }
        mLocalExtras.put(BROADCAST_IDENTIFIER_KEY, broadcastIdentifier);
        mLocalExtras.put(AD_REPORT_KEY, adReport);
    }

    void loadInterstitial() {
        if (isInvalidated() || mCustomEventInterstitial == null) {
            return;
        }
        MoPubLog.log(CUSTOM, "loadInterstitial()");

        mHandler.postDelayed(mTimeout, getTimeoutDelayMilliseconds());

        // Custom event classes can be developed by any third party and may not be tested.
        // We catch all exceptions here to prevent crashes from untested code.
        try {
            mCustomEventInterstitial.loadInterstitial(mContext, this, mLocalExtras, mServerExtras);
        } catch (Exception e) {
            onInterstitialFailed(MoPubErrorCode.INTERNAL_ERROR);
        }
    }

    void showInterstitial() {
        if (isInvalidated() || mCustomEventInterstitial == null) {
            return;
        }
        MoPubLog.log(CUSTOM, "showInterstitial()");


        //todo bojo bullshit usuje na separate networks proxi gone for now
        //We use proxy activity for some ad networks no mate
        if(mCustomEventInterstitial.usesProxy() || false){
            if (mProxy == null) {
                mProxy = new Proxy();
            }
            mProxy.startProxyActivity(mContext,mCustomEventInterstitial);
            return;
        }

        // Custom event classes can be developed by any third party and may not be tested.
        // We catch all exceptions here to prevent crashes from untested code.
        try {
            mCustomEventInterstitial.showInterstitial();
        } catch (Exception e) {
            MoPubLog.log(CUSTOM, "showInterstitial() failed with code " +
                    MoPubErrorCode.INTERNAL_ERROR.getIntCode() + " and message " +
                    MoPubErrorCode.INTERNAL_ERROR);
            onInterstitialFailed(MoPubErrorCode.INTERNAL_ERROR);
        }
    }

    void invalidate() {
        if (mCustomEventInterstitial != null) {

            // Custom event classes can be developed by any third party and may not be tested.
            // We catch all exceptions here to prevent crashes from untested code.
            try {
                mCustomEventInterstitial.onInvalidate();
            } catch (Exception e) {
                MoPubLog.log(CUSTOM,  "Invalidating a custom event interstitial threw an exception.", e);
            }
        }

        mCustomEventInterstitial = null;
        mContext = null;
        mServerExtras = null;
        mLocalExtras = null;
        mCustomEventInterstitialAdapterListener = null;
        final WebViewCacheService.Config config =
                WebViewCacheService.popWebViewConfig(mBroadcastIdentifier);
        if (config != null) {
            config.getWebView().destroy();
        }
        mInvalidated = true;
    }

    boolean isInvalidated() {
        return mInvalidated;
    }

    void setAdapterListener(CustomEventInterstitialAdapterListener listener) {
        mCustomEventInterstitialAdapterListener = listener;
    }

    boolean isAutomaticImpressionAndClickTrackingEnabled() {
        final CustomEventInterstitial customEventInterstitial = mCustomEventInterstitial;
        if (customEventInterstitial == null) {
            return true;
        }

        return customEventInterstitial.isAutomaticImpressionAndClickTrackingEnabled();
    }

    private void cancelTimeout() {
        mHandler.removeCallbacks(mTimeout);
    }

    private int getTimeoutDelayMilliseconds() {
        if (mMoPubInterstitial == null ) {
            return DEFAULT_INTERSTITIAL_TIMEOUT_DELAY;
        }

        return mMoPubInterstitial.getAdTimeoutDelay(DEFAULT_INTERSTITIAL_TIMEOUT_DELAY);
    }

    interface CustomEventInterstitialAdapterListener {
        void onCustomEventInterstitialLoaded();
        void onCustomEventInterstitialFailed(MoPubErrorCode errorCode);
        void onCustomEventInterstitialShown();
        void onCustomEventInterstitialClicked();
        void onCustomEventInterstitialImpression();
        void onCustomEventInterstitialDismissed();
    }

    /*
     * CustomEventInterstitial.Listener implementation
     */
    @Override
    public void onInterstitialLoaded() {
        if (isInvalidated()) {
            return;
        }

        MoPubLog.log(CUSTOM, "onInterstitialLoaded()");

        cancelTimeout();

        if (mCustomEventInterstitialAdapterListener != null) {
            mCustomEventInterstitialAdapterListener.onCustomEventInterstitialLoaded();
        }
    }

    @Override
    public void onInterstitialFailed(MoPubErrorCode errorCode) {
        if (isInvalidated()) {
            return;
        }

        if (errorCode == null) {
            errorCode = UNSPECIFIED;
        }

        MoPubLog.log(CUSTOM, "onInterstitialFailed() failed with code " +
                errorCode.getIntCode() + " and message " +
                errorCode);

        if (mCustomEventInterstitialAdapterListener != null) {
            cancelTimeout();
            mCustomEventInterstitialAdapterListener.onCustomEventInterstitialFailed(errorCode);
        }
    }

    @Override
    public void onInterstitialShown() {
        if (isInvalidated()) {
            return;
        }

        MoPubLog.log(CUSTOM, "onInterstitialShown()");

        if (mCustomEventInterstitialAdapterListener != null) {
            mCustomEventInterstitialAdapterListener.onCustomEventInterstitialShown();
        }
    }

    @Override
    public void onInterstitialClicked() {
        if (isInvalidated()) {
            return;
        }

        if (mCustomEventInterstitialAdapterListener != null) {
            mCustomEventInterstitialAdapterListener.onCustomEventInterstitialClicked();
        }
    }

    @Override
    public void onInterstitialImpression() {
        if (isInvalidated()) {
            return;
        }

        if (mCustomEventInterstitialAdapterListener != null) {
            mCustomEventInterstitialAdapterListener.onCustomEventInterstitialImpression();
        }
    }

    @Override
    public void onLeaveApplication() {
        onInterstitialClicked();
    }

    @Override
    public void onInterstitialDismissed() {
        if (isInvalidated()) {
            return;
        }

        if (mCustomEventInterstitialAdapterListener != null) {
            mCustomEventInterstitialAdapterListener.onCustomEventInterstitialDismissed();
        }

        if(mProxy != null) {
            mProxy.Finish();
        }
    }

    @VisibleForTesting
    void setProxy(Proxy proxy){
        mProxy = proxy;
    }

    @Deprecated
    void setCustomEventInterstitial(CustomEventInterstitial interstitial) {
        mCustomEventInterstitial = interstitial;
    }
}
