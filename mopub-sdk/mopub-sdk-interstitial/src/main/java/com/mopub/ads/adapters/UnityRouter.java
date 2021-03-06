package com.mopub.ads.adapters;

import android.app.Activity;
import android.text.TextUtils;

import com.mopub.common.MoPub;
import com.mopub.common.logging.MoPubLog;
import com.mopub.common.privacy.ConsentStatus;
import com.mopub.common.privacy.PersonalInfoManager;
import com.mopub.mobileads.MoPubErrorCode;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.mediation.IUnityAdsExtendedListener;
import com.unity3d.ads.metadata.MediationMetaData;
import com.unity3d.ads.metadata.MetaData;

import java.util.HashMap;
import java.util.Map;

public class UnityRouter {
    private static String sCurrentPlacementId;
    private static final String GAME_ID_KEY = "gameId";
    private static final String ZONE_ID_KEY = "zoneId";
    private static final String PLACEMENT_ID_KEY = "placementId";
    private static final UnityAdsListener sUnityAdsListener = new UnityAdsListener();
    private static Map<String, IUnityAdsExtendedListener> mUnityAdsListeners = new HashMap<>();

    static boolean initUnityAds(Map<String, String> serverExtras, Activity launcherActivity) {

        // Pass the user consent from the MoPub SDK to Unity Ads as per GDPR
        PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();

        if (personalInfoManager != null) {
            ConsentStatus consentStatus = personalInfoManager.getPersonalInfoConsentStatus();

            if(consentStatus == ConsentStatus.EXPLICIT_YES || consentStatus == ConsentStatus.EXPLICIT_NO) {
                MetaData gdprMetaData = new MetaData(launcherActivity.getApplicationContext());

                // Set if the user has explicitly said yes or no
                if(consentStatus == ConsentStatus.EXPLICIT_YES) {
                    gdprMetaData.set("gdpr.consent", true);
                }
                else {
                    gdprMetaData.set("gdpr.consent", false);
                }
                
                gdprMetaData.commit();
            }
        }

        String gameId = serverExtras.get(GAME_ID_KEY);
        if (gameId == null || gameId.isEmpty()) {
            MoPubLog.e("gameId is missing or entered incorrectly in the MoPub UI");
            return false;
        }

        MediationMetaData mediationMetaData = new MediationMetaData(launcherActivity);
        mediationMetaData.setName("MoPub");
        mediationMetaData.setVersion(MoPub.SDK_VERSION);
        mediationMetaData.commit();

        UnityAds.initialize(launcherActivity, gameId, sUnityAdsListener);
        return true;
    }

    static String placementIdForServerExtras(Map<String, String> serverExtras, String defaultPlacementId) {
        String placementId = null;
        if (serverExtras.containsKey(PLACEMENT_ID_KEY)) {
            placementId = serverExtras.get(PLACEMENT_ID_KEY);
        } else if (serverExtras.containsKey(ZONE_ID_KEY)) {
            placementId = serverExtras.get(ZONE_ID_KEY);
        }
        return TextUtils.isEmpty(placementId) ? defaultPlacementId : placementId;
    }

    static void showAd(Activity activity, String placementId) {
        sCurrentPlacementId = placementId;
        UnityAds.show(activity, placementId);
    }

    static void addListener(String placementId, IUnityAdsExtendedListener unityListener) {
        mUnityAdsListeners.put(placementId, unityListener);
    }

    static void removeListener(String placementId) {
        mUnityAdsListeners.remove(placementId);
    }

    private static class UnityAdsListener implements IUnityAdsExtendedListener {
        @Override
        public void onUnityAdsReady(String placementId) {
            IUnityAdsExtendedListener listener = mUnityAdsListeners.get(placementId);
            if (listener != null) {
                listener.onUnityAdsReady(placementId);
            }
        }

        @Override
        public void onUnityAdsStart(String placementId) {
            IUnityAdsExtendedListener listener = mUnityAdsListeners.get(placementId);
            if (listener != null) {
                listener.onUnityAdsStart(placementId);
            }
        }

        @Override
        public void onUnityAdsFinish(String placementId, UnityAds.FinishState finishState) {
            IUnityAdsExtendedListener listener = mUnityAdsListeners.get(placementId);
            if (listener != null) {
                listener.onUnityAdsFinish(placementId, finishState);
            }
        }

        @Override
        public void onUnityAdsClick(String placementId) {
            IUnityAdsExtendedListener listener = mUnityAdsListeners.get(placementId);
            if (listener != null) {
                listener.onUnityAdsClick(placementId);
            }
        }

        // @Override
        public void onUnityAdsPlacementStateChanged(String placementId, UnityAds.PlacementState oldState, UnityAds.PlacementState newState) {
        }

        @Override
        public void onUnityAdsError(UnityAds.UnityAdsError unityAdsError, String message) {
            IUnityAdsExtendedListener listener = mUnityAdsListeners.get(sCurrentPlacementId);
            if (listener != null) {
                listener.onUnityAdsError(unityAdsError, message);
            }
        }
    }

    static final class UnityAdsUtils {
        static MoPubErrorCode getMoPubErrorCode(UnityAds.UnityAdsError unityAdsError) {
            MoPubErrorCode errorCode;
            switch (unityAdsError) {
                case VIDEO_PLAYER_ERROR:
                    errorCode = MoPubErrorCode.VIDEO_PLAYBACK_ERROR;
                    break;
                case INVALID_ARGUMENT:
                    errorCode = MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR;
                    break;
                case INTERNAL_ERROR:
                    errorCode = MoPubErrorCode.NETWORK_INVALID_STATE;
                    break;
                default:
                    errorCode = MoPubErrorCode.NETWORK_NO_FILL;
                    break;
            }
            return errorCode;
        }
    }

    static class UnityAdsException extends RuntimeException {
        private final UnityAds.UnityAdsError errorCode;

        public UnityAdsException(UnityAds.UnityAdsError errorCode, String detailFormat, Object... args) {
            this(errorCode, String.format(detailFormat, args));
        }

        public UnityAdsException(UnityAds.UnityAdsError errorCode, String detailMessage) {
            super(detailMessage);
            this.errorCode = errorCode;
        }

        public UnityAds.UnityAdsError getErrorCode() {
            return errorCode;
        }
    }
}
