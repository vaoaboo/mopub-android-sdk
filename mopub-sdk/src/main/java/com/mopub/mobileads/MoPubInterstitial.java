package com.mopub.mobileads;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.mopub.common.AdFormat;
import com.mopub.common.DataKeys;
import com.mopub.common.Preconditions;
import com.mopub.common.VisibleForTesting;
import com.mopub.common.logging.MoPubLog;
import com.mopub.mobileads.factories.CustomEventInterstitialAdapterFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mopub.mobileads.MoPubErrorCode.ADAPTER_NOT_FOUND;
import static com.mopub.mobileads.MoPubInterstitial.InterstitialState.IDLE;
import static com.mopub.mobileads.MoPubInterstitial.InterstitialState.LOADING;
import static com.mopub.mobileads.MoPubInterstitial.InterstitialState.READY;
import static com.mopub.mobileads.MoPubInterstitial.InterstitialState.DESTROYED;

public class MoPubInterstitial implements CustomEventInterstitialAdapter.CustomEventInterstitialAdapterListener {

    //http://www.nationsonline.org/oneworld/country_code_list.htm
    @Nullable public String getCountryCode() {
        return mCountryCode;
    }

    public String getCity() {
        return mCity;
    }

    @VisibleForTesting
    enum InterstitialState {
        /**
         * Either waiting for something to happen or already showing an interstitial. There is no
         * interstitial currently loaded.
         */
        IDLE,

        /**
         * Loading an interstitial.
         */
        LOADING,

        /**
         * Loaded and ready to be shown.
         */
        READY,

        /**
         * No longer able to accept events as the internal InterstitialView has been destroyed.
         */
        DESTROYED
    }


    @NonNull private MoPubInterstitialView mInterstitialView;
    @Nullable private CustomEventInterstitialAdapter mCustomEventInterstitialAdapter;
    @Nullable private InterstitialAdListener mInterstitialAdListener;
    @NonNull private Activity mActivity;
    @NonNull private InterstitialState mCurrentInterstitialState;
    private boolean mIsDestroyed;
    private String mCountryCode;
    private String mCity;

    public interface InterstitialAdListener {
        void onInterstitialLoaded(MoPubInterstitial interstitial);
        void onInterstitialFailed(MoPubInterstitial interstitial, MoPubErrorCode errorCode);
        void onInterstitialShown(MoPubInterstitial interstitial);
        void onInterstitialClicked(MoPubInterstitial interstitial);
        void onInterstitialDismissed(MoPubInterstitial interstitial);
    }

    public MoPubInterstitial(@NonNull final Activity activity, @NonNull final String adUnitId) {
        mActivity = activity;

        mInterstitialView = new MoPubInterstitialView(mActivity);
        mInterstitialView.setAdUnitId(adUnitId);

        mCurrentInterstitialState = IDLE;
    }

    private boolean attemptStateTransition(@NonNull final InterstitialState endState) {
        return attemptStateTransition(endState, false);
    }

    /**
     * Attempts to transition to the new state. All state transitions should go through this method.
     * Other methods should not be modifying mCurrentInterstitialState.
     *
     * @param endState The desired end state.
     * @param forced   If possible, forces the state to go to a particular state. This is not
     *                 guaranteed to always set the endState but will allow certain irregular
     *                 state transitions.
     * @return {@code true} if a state change happened, {@code false} if no state change happened.
     */
    @VisibleForTesting
    boolean attemptStateTransition(@NonNull final InterstitialState endState,
            boolean forced) {
        Preconditions.checkNotNull(endState);

        final InterstitialState startState = mCurrentInterstitialState;

        /**
         * There are 32 potential cases. Any combination that is a no op will not be enumerated
         * and returns false. The usual case goes IDLE -> LOADING -> READY -> IDLE. At any point,
         * a forced transition into IDLE resets MoPubInterstitial and clears the interstitial
         * adapter. Also, MoPubInterstitial can be destroyed arbitrarily, and once this is
         * destroyed, it no longer can perform any state transitions.
         */
        switch (startState) {
            case IDLE:
                switch(endState) {
                    case IDLE:
                        if (!forced) {
                            // This is the case of trying to show(), but nothing was loaded
                            MoPubLog.d("No interstitial loading or loaded.");
                        }
                        // Forcing into IDLE resets the state, but it has already been reset,
                        // so this is a no op.
                        return false;
                    case LOADING:
                        // Going from IDLE to LOADING is the usual load case
                        invalidateInterstitialAdapter();
                        mCurrentInterstitialState = LOADING;
                        if (forced) {
                            mInterstitialView.forceRefresh();
                        } else {
                            mInterstitialView.loadAd();
                        }
                        return true;
                    case DESTROYED:
                        setInterstitialStateDestroyed();
                        return true;
                    default:
                        return false;
                }
            case LOADING:
                switch (endState) {
                    case IDLE:
                        if (forced) {
                            // Being forced back into idle while loading resets MoPubInterstitial.
                            invalidateInterstitialAdapter();
                            mCurrentInterstitialState = IDLE;
                            return true;
                        } else {
                            // No force to IDLE means show(), but this is still loading
                            MoPubLog.d("Interstitial is not ready to be shown yet.");
                            return false;
                        }
                    case LOADING:
                        if (!forced) {
                            // Cannot load more than one interstitial at a time
                            MoPubLog.d("Already loading an interstitial.");
                        }
                        // If forced, it means a failover started. This is still loading.
                        return false;
                    case READY:
                        // This is the usual load finished transition
                        mCurrentInterstitialState = READY;
                        return true;
                    case DESTROYED:
                        setInterstitialStateDestroyed();
                        return true;
                    default:
                        return false;
                }
            case READY:
                switch (endState) {
                    case IDLE:
                        mCurrentInterstitialState = IDLE;
                        if (forced) {
                            // This happens on a reset
                            invalidateInterstitialAdapter();
                            return true;
                        } else {
                            // This is the usual show interstitial when ready
                            showCustomEventInterstitial();
                            return true;
                        }
                    case LOADING:
                        // This is to prevent loading another interstitial while one is loaded.
                        MoPubLog.d("Interstitial already loaded. Not loading another.");
                        // Let the ad listener know that there's already an ad loaded
                        if (mInterstitialAdListener != null) {
                            mInterstitialAdListener.onInterstitialLoaded(this);
                        }
                        return false;
                    case DESTROYED:
                        setInterstitialStateDestroyed();
                        return true;
                    default:
                        return false;
                }
            case DESTROYED:
                // Once destroyed, MoPubInterstitial is no longer functional.
                MoPubLog.d("MoPubInterstitial destroyed. Ignoring all requests.");
                return false;
            default:
                return false;
        }
    }

    /**
     * Sets MoPubInterstitial to be destroyed. This should only be called by attemptStateTransition.
     */
    private void setInterstitialStateDestroyed() {
        invalidateInterstitialAdapter();
        mInterstitialView.setBannerAdListener(null);
        mInterstitialView.destroy();
        mCurrentInterstitialState = DESTROYED;
    }

    public void load() {
        attemptStateTransition(LOADING);
    }

    public boolean show() {
        return attemptStateTransition(IDLE);
    }

    public void forceRefresh() {
        attemptStateTransition(IDLE, true);
        attemptStateTransition(LOADING, true);
    }

    public boolean isReady() {
        return mCurrentInterstitialState == READY;
    }

    boolean isDestroyed() {
        return mCurrentInterstitialState == DESTROYED;
    }

    Integer getAdTimeoutDelay() {
        return mInterstitialView.getAdTimeoutDelay();
    }

    @NonNull
    MoPubInterstitialView getMoPubInterstitialView() {
        return mInterstitialView;
    }

    private void showCustomEventInterstitial() {
        if (mCustomEventInterstitialAdapter != null) {
            mCustomEventInterstitialAdapter.showInterstitial();
        }
    }

    private void invalidateInterstitialAdapter() {
        if (mCustomEventInterstitialAdapter != null) {
            mCustomEventInterstitialAdapter.invalidate();
            mCustomEventInterstitialAdapter = null;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void setKeywords(@Nullable final String keywords) {
        mInterstitialView.setKeywords(keywords);
    }

    @Nullable
    public String getKeywords() {
        return mInterstitialView.getKeywords();
    }

    @NonNull
    public Activity getActivity() {
        return mActivity;
    }

    @Nullable
    public Location getLocation() {
        return mInterstitialView.getLocation();
    }

    public void destroy() {
        attemptStateTransition(DESTROYED);
    }

    public void setInterstitialAdListener(@Nullable final InterstitialAdListener listener) {
        mInterstitialAdListener = listener;
    }

    @Nullable
    public InterstitialAdListener getInterstitialAdListener() {
        return mInterstitialAdListener;
    }

    public void setTesting(boolean testing) {
        mInterstitialView.setTesting(testing);
    }

    public boolean getTesting() {
        return mInterstitialView.getTesting();
    }

    public void setLocalExtras(Map<String, Object> extras) {
        mInterstitialView.setLocalExtras(extras);
    }

    @NonNull
    public Map<String, Object> getLocalExtras() {
        return mInterstitialView.getLocalExtras();
    }

    /*
     * Implements CustomEventInterstitialAdapter.CustomEventInterstitialListener
     * Note: All callbacks should be no-ops if the interstitial has been destroyed
     */

    @Override
    public void onCustomEventInterstitialLoaded() {
        if (isDestroyed()) {
            return;
        }

        attemptStateTransition(READY);

        if (mInterstitialAdListener != null) {
            mInterstitialAdListener.onInterstitialLoaded(this);
        }
    }

    @Override
    public void onCustomEventInterstitialFailed(@NonNull final MoPubErrorCode errorCode) {
        if (isDestroyed()) {
            return;
        }

        if (mInterstitialView.loadFailUrl(errorCode)) {
            attemptStateTransition(LOADING, true);
        } else {
            attemptStateTransition(IDLE, true);
        }
    }

    @Override
    public void onCustomEventInterstitialShown() {
        if (isDestroyed()) {
            return;
        }

        mInterstitialView.trackImpression();

        if (mInterstitialAdListener != null) {
            mInterstitialAdListener.onInterstitialShown(this);
        }
    }

    @Override
    public void onCustomEventInterstitialClicked() {
        if (isDestroyed()) {
            return;
        }

        mInterstitialView.registerClick();

        if (mInterstitialAdListener != null) {
            mInterstitialAdListener.onInterstitialClicked(this);
        }
    }

    @Override
    public void onCustomEventInterstitialDismissed() {
        if (isDestroyed()) {
            return;
        }

        if (mInterstitialAdListener != null) {
            mInterstitialAdListener.onInterstitialDismissed(this);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    public class MoPubInterstitialView extends MoPubView {
        public MoPubInterstitialView(Context context) {
            super(context);
            setAutorefreshEnabled(false);
        }

        @Override
        public AdFormat getAdFormat() {
            return AdFormat.INTERSTITIAL;
        }

        @Override
        protected void loadCustomEvent(String customEventClassName, Map<String, String> serverExtras) {
            if (mAdViewController == null) {
                return;
            }

            extractCountryFromExtras(serverExtras);

            if (TextUtils.isEmpty(customEventClassName)) {
                MoPubLog.d("Couldn't invoke custom event because the server did not specify one.");
                loadFailUrl(ADAPTER_NOT_FOUND);
                return;
            }

            if (mCustomEventInterstitialAdapter != null) {
                mCustomEventInterstitialAdapter.invalidate();
            }

            MoPubLog.d("Loading custom event interstitial adapter.");

            mCustomEventInterstitialAdapter = CustomEventInterstitialAdapterFactory.create(
                    MoPubInterstitial.this,
                    customEventClassName,
                    serverExtras,
                    mAdViewController.getBroadcastIdentifier(),
                    mAdViewController.getAdReport());
            mCustomEventInterstitialAdapter.setAdapterListener(MoPubInterstitial.this);
            mCustomEventInterstitialAdapter.loadInterstitial();
        }

        protected void trackImpression() {
            MoPubLog.d("Tracking impression for interstitial.");
            if (mAdViewController != null) mAdViewController.trackImpression();
        }

        @Override
        protected void adFailed(MoPubErrorCode errorCode) {
            attemptStateTransition(IDLE, true);
            if (mInterstitialAdListener != null) {
                mInterstitialAdListener.onInterstitialFailed(MoPubInterstitial.this, errorCode);
            }
        }
    }

    @VisibleForTesting
    void extractCountryFromExtras(Map<String, String> serverExtras) {
        Preconditions.checkNotNull(serverExtras);
        if(serverExtras.containsKey(DataKeys.CLICKTHROUGH_URL_KEY)){
            String url = serverExtras.get(DataKeys.CLICKTHROUGH_URL_KEY);
            Pattern p = Pattern.compile("(?<=&country_code=).*?(?=&)");
            Matcher m = p.matcher(url);
            if(m.find()){
                mCountryCode = m.group();
            }
        }
    }

    @VisibleForTesting
    void extractCityFromExtras(Map<String, String> serverExtras) {
        Preconditions.checkNotNull(serverExtras);
        if(serverExtras.containsKey(DataKeys.CLICKTHROUGH_URL_KEY)){
            String url = serverExtras.get(DataKeys.CLICKTHROUGH_URL_KEY);
            Pattern p = Pattern.compile("(?<=&city=).*?(?=&)");
            Matcher m = p.matcher(url);
            if(m.find()){
                mCity = m.group();
            }
        }
    }

    @VisibleForTesting
    @Deprecated
    void setInterstitialView(@NonNull MoPubInterstitialView interstitialView) {
        mInterstitialView = interstitialView;
    }

    @VisibleForTesting
    @Deprecated
    void setCurrentInterstitialState(@NonNull final InterstitialState interstitialState) {
        mCurrentInterstitialState = interstitialState;
    }

    @VisibleForTesting
    @Deprecated
    @NonNull
    InterstitialState getCurrentInterstitialState() {
        return mCurrentInterstitialState;
    }

    @VisibleForTesting
    @Deprecated
    void setCustomEventInterstitialAdapter(@NonNull final CustomEventInterstitialAdapter
            customEventInterstitialAdapter) {
        mCustomEventInterstitialAdapter = customEventInterstitialAdapter;
    }
}
