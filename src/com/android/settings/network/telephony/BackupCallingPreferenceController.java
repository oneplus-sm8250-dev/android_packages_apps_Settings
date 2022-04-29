/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settings.network.telephony;

import android.content.Context;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.android.settings.R;
import com.android.settings.network.SubscriptionUtil;
import com.android.settings.network.ims.WifiCallingQueryImsState;

import com.qti.extphone.ExtTelephonyManager;
import com.qti.extphone.ServiceCallback;

import java.util.List;
import java.util.Objects;

/**
 * Preference controller for "Backup Calling"
 **/
public class BackupCallingPreferenceController extends TelephonyTogglePreferenceController {

    private static final String LOG_TAG = "BackupCallingPrefCtrl";

    private Preference mPreference;
    private Context mContext;
    private ExtTelephonyManager mExtTelephonyManager;
    private boolean mServiceConnected = false;

    /**
     * Class constructor of backup calling.
     *
     * @param context of settings
     * @param key assigned within UI entry of XML file
     **/
    public BackupCallingPreferenceController(Context context, String key) {
        super(context, key);
        mContext = context.getApplicationContext();
        mExtTelephonyManager = ExtTelephonyManager.getInstance(mContext);
        mExtTelephonyManager.connectService(mExtTelManagerServiceCallback);
    }

    private ServiceCallback mExtTelManagerServiceCallback = new ServiceCallback() {

        // Since ExtTelephony service is called from TelephonyComponentFactory,
        // onConnected() is called even before mExtTelephonyManager.connectService
        // as per ExtTelephonyManager#connectService()
        @Override
        public void onConnected() {
            Log.d(LOG_TAG, "mExtTelManagerServiceCallback: service connected");
            mServiceConnected = true;
        }

        @Override
        public void onDisconnected() {
            Log.d(LOG_TAG, "mExtTelManagerServiceCallback: service disconnected");
            mServiceConnected = false;
        }
    };

    /**
     * Initialization based on given subscription id.
     *
     * @param subId is the subscription id
     * @return this instance after initialization
     **/
    public BackupCallingPreferenceController init(int subId) {
        mSubId = subId;
        return this;
    }

    @Override
    public int getAvailabilityStatus(int subId) {
        if (!hasBackupCallingFeature(subId)) {
            return CONDITIONALLY_UNAVAILABLE;
        }
        List<SubscriptionInfo> subIdList = getActiveSubscriptionList();
        SubscriptionInfo subInfo = getSubscriptionInfoFromList(subIdList, subId);
        if (subInfo == null) {  // given subId is not actives
            return CONDITIONALLY_UNAVAILABLE;
        }
        return (subIdList.size() == 1) ? AVAILABLE : CONDITIONALLY_UNAVAILABLE;
    }

    /**
     * Implementation of abstract methods
     **/
    public boolean setChecked(boolean isChecked) {
        ImsMmTelManager imsMmTelMgr = getImsMmTelManager(mSubId);
        if (imsMmTelMgr == null) {
            return false;
        }
        try {
            imsMmTelMgr.setCrossSimCallingEnabled(isChecked);
        } catch (ImsException exception) {
            Log.w(LOG_TAG, "fail to change cross SIM calling configuration: " + isChecked,
                    exception);
            return false;
        }
        return true;
    }

    /**
     * Implementation of abstract methods
     **/
    public boolean isChecked() {
        ImsMmTelManager imsMmTelMgr = getImsMmTelManager(mSubId);
        if (imsMmTelMgr == null) {
            return false;
        }
        try {
            return imsMmTelMgr.isCrossSimCallingEnabled();
        } catch (ImsException exception) {
            Log.w(LOG_TAG, "fail to get cross SIM calling configuration", exception);
        }
        return false;
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        if ((preference == null) || (!(preference instanceof SwitchPreference))) {
            return;
        }
        SubscriptionInfo subInfo = getSubscriptionInfoFromActiveList(mSubId);

        mPreference = preference;

        final SwitchPreference switchPreference = (SwitchPreference) preference;
        switchPreference.setChecked((subInfo != null) ? isChecked() : false);

        updateSummary(getLatestSummary(subInfo));
    }

    private String getLatestSummary(SubscriptionInfo subInfo) {
        return Objects.toString((subInfo == null) ? null
                : SubscriptionUtil.getUniqueSubscriptionDisplayName(subInfo, mContext), "");
    }

    private void updateSummary(String displayName) {
        Preference preference = mPreference;
        if (preference == null) {
            return;
        }
        String summary = displayName;
        String finalText = String.format(
                getResourcesForSubId().getString(R.string.backup_calling_setting_summary),
                summary)
                .toString();
        preference.setSummary(finalText);
    }

    private boolean hasBackupCallingFeature(int subscriptionId) {
        return isCrossSimEnabledByPlatform(mContext, subscriptionId);
    }

    protected boolean isCrossSimEnabledByPlatform(Context context, int subscriptionId) {
        if (!mServiceConnected) {
            Log.d(LOG_TAG, "ExtTelephony service is not connected");
            return false;
        }

        try {
            if (!mExtTelephonyManager.isEpdgOverCellularDataSupported(
                    SubscriptionManager.getPhoneId(subscriptionId))) {
                Log.d(LOG_TAG, "Not supported by platform. subId = " + subscriptionId);
                return false;
            }
        } catch(RemoteException ex) {
            Log.d(LOG_TAG, "isEpdgOverCellularDataSupported Exception" + ex);
            return false;
        }

        // TODO : Change into API which created for accessing
        //        com.android.ims.ImsManager#isCrossSimEnabledByPlatform()
        if ((new WifiCallingQueryImsState(context, subscriptionId)).isWifiCallingSupported()) {
            PersistableBundle bundle = getCarrierConfigForSubId(subscriptionId);
            return (bundle != null) && bundle.getBoolean(
                    CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
                    false /*default*/);
        }
        Log.d(LOG_TAG, "Not supported by framework. subId = " + subscriptionId);
        return false;
    }

    private ImsMmTelManager getImsMmTelManager(int subId) {
        if (!SubscriptionManager.isUsableSubscriptionId(subId)) {
            return null;
        }
        ImsManager imsMgr = mContext.getSystemService(ImsManager.class);
        return (imsMgr == null) ? null : imsMgr.getImsMmTelManager(subId);
    }

    private List<SubscriptionInfo> getActiveSubscriptionList() {
        SubscriptionManager subscriptionManager =
                mContext.getSystemService(SubscriptionManager.class);
        return SubscriptionUtil.getActiveSubscriptions(subscriptionManager);
    }

    private SubscriptionInfo getSubscriptionInfoFromList(
            List<SubscriptionInfo> subInfoList, int subId) {
        for (SubscriptionInfo subInfo : subInfoList) {
            if ((subInfo != null) && (subInfo.getSubscriptionId() == subId)) {
                return subInfo;
            }
        }
        return null;
    }

    private SubscriptionInfo getSubscriptionInfoFromActiveList(int subId) {
        if (!SubscriptionManager.isUsableSubscriptionId(subId)) {
            return null;
        }
        return getSubscriptionInfoFromList(getActiveSubscriptionList(), subId);
    }
}
