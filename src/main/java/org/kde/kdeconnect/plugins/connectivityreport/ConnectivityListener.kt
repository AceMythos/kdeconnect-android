/*
 * SPDX-FileCopyrightText: 2025 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.plugins.connectivityreport

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Registers a listener for changes in connectivity for the device.
 */
@SuppressLint("MissingPermission")
class ConnectivityListener(context: Context) {

    val context : Context = context.applicationContext

    data class SubscriptionState(
        var signalStrength: Int = 0,
        var networkType: String = "Unknown",
        /** Empty string when the SA/NSA distinction is not available. */
        var standalone: String = "",
        /** Operating band label, e.g. "n78". Empty string when not available. */
        var band: String = "",
    ) {
        @RequiresApi(Build.VERSION_CODES.P)
        constructor(tm: TelephonyManager) : this(ASUUtils.signalStrengthToLevel(tm.signalStrength), ASUUtils.networkTypeToString(tm.dataNetworkType))
    }

    interface StateCallback {
        fun statesChanged(states: Map<Int, SubscriptionState>)
    }

    companion object {
        private const val TAG: String = "ConnectivityListener"

        /**
         * Band flips do not reliably raise a PhoneStateListener callback: the
         * signal *level* can stay identical while the phone moves between n78
         * and n28, and the packet is only re-sent when the level changes. Poll
         * so the desktop always sees the current band.
         */
        private const val POLL_INTERVAL_MS = 3_000L

        private var instance: ConnectivityListener? = null
        @JvmStatic
        fun getInstance(context: Context): ConnectivityListener {
            if (instance == null) {
                instance = ConnectivityListener(context)
            }
            return instance!!
        }
    }

    private val connectivityListeners = mutableMapOf<Int?, PhoneStateListener?>()
    private val states = mutableMapOf<Int, SubscriptionState>() // by subscription ID

    // Band tracking. CellInfo/DisplayInfo updates only reach us via callbacks,
    // so the latest of each is cached per subscription and re-evaluated together.
    private val telephonyCallbacks = mutableMapOf<Int, TelephonyCallback>()
    private val latestDisplayInfo = mutableMapOf<Int, TelephonyDisplayInfo>()
    private val latestCellInfo = mutableMapOf<Int, List<CellInfo>>()

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshBands()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val externalListeners = mutableSetOf<StateCallback>()

    private val activeIDs = mutableSetOf<Int>()

    private fun statesChanged() {
        val listenersCopy = synchronized(externalListeners) {
            externalListeners.toList() // copy to prevent ConcurrentModificationException
        }
        for (listener in listenersCopy) {
            listener.statesChanged(states)
        }
    }

    val subscriptionsListener: OnSubscriptionsChangedListener by lazy {
        @RequiresApi(Build.VERSION_CODES.N)
        object : OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                val nextSubs = getActiveSubscriptionIDs().toSet()

                val addedSubs = nextSubs - activeIDs
                val removedSubs = activeIDs - nextSubs

                activeIDs.removeAll(removedSubs)
                activeIDs.addAll(addedSubs)

                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                for (subID in removedSubs) {
                    Log.i(TAG, "Removed subscription ID $subID")
                    try {
                        tm.listen(connectivityListeners[subID], PhoneStateListener.LISTEN_NONE)
                    } catch (_: Exception) {
                        // It seems like the subscription ID is no longer valid by this point, so this might trigger
                    }
                    unregisterTelephonyCallback(tm, subID)
                    connectivityListeners.remove(subID)
                    states.remove(subID)
                    latestDisplayInfo.remove(subID)
                    latestCellInfo.remove(subID)
                    statesChanged()
                }
                for (subID in addedSubs) {
                    val subTm = tm.createForSubscriptionId(subID)
                    Log.i(TAG, "Added subscription ID $subID")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        states[subID] = SubscriptionState(subTm)
                    } else {
                        states[subID] = SubscriptionState()
                    }
                    val listener = createListenerForSubscription(subID)
                    connectivityListeners[subID] = listener
                    subTm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
                    registerTelephonyCallback(subTm, subID)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        statesChanged()
                    }
                }
            }
        }
    }

    fun listenStateChanges(listener: StateCallback) {
        var wasEmpty : Boolean
        synchronized(externalListeners) {
            wasEmpty = externalListeners.isEmpty()
            externalListeners.add(listener)
            listener.statesChanged(states)
        }
        Log.d(TAG, "listeners: ${externalListeners.size}")
        if (wasEmpty) {
            startListening()
        }
    }

    fun cancelActiveListener(listener: StateCallback) {
        var isEmpty : Boolean
        synchronized(externalListeners) {
            externalListeners.remove(listener)
            isEmpty = externalListeners.isEmpty()
        }
        if (isEmpty) {
            stopListening()
        }
    }

    private fun startListening() {
        runOnMainThread {
            pollHandler.removeCallbacks(pollRunnable)
            pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Multi-SIM supported on Nougat+
                val sm = ContextCompat.getSystemService(context, SubscriptionManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    sm?.addOnSubscriptionsChangedListener(context.mainExecutor, subscriptionsListener)
                } else {
                    sm?.addOnSubscriptionsChangedListener(subscriptionsListener)
                }
            } else {
                // Fallback to single SIM
                connectivityListeners[0] = createListenerForSubscription(0)
                states[0] = SubscriptionState()
            }
        }
    }

    private fun stopListening() {
        runOnMainThread {
            pollHandler.removeCallbacks(pollRunnable)
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            for (subID in telephonyCallbacks.keys.toList()) {
                unregisterTelephonyCallback(tm, subID)
            }
            latestDisplayInfo.clear()
            latestCellInfo.clear()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm = ContextCompat.getSystemService(context, SubscriptionManager::class.java)
                sm?.removeOnSubscriptionsChangedListener(subscriptionsListener)
            }
            for (subID in connectivityListeners.keys) {
                Log.i(TAG, "Removed subscription ID $subID")
                tm.listen(connectivityListeners[subID], PhoneStateListener.LISTEN_NONE)
            }
            connectivityListeners.clear()
            states.clear()
            activeIDs.clear()
        }
    }

    private fun runOnMainThread(r: Runnable) {
        Handler(Looper.getMainLooper()).post(r)
    }

    /**
     * Listen for the callbacks that expose band information: cell changes give
     * us the serving cell, display info gives us the SA/NSA override type.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerTelephonyCallback(subTm: TelephonyManager, subID: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val callback = object : TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.DisplayInfoListener {

            override fun onCellInfoChanged(cellInfo: List<CellInfo>) {
                latestCellInfo[subID] = cellInfo
                updateBandState(subTm, subID)
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                latestDisplayInfo[subID] = telephonyDisplayInfo
                updateBandState(subTm, subID)
            }
        }
        telephonyCallbacks[subID] = callback
        // The cell info listener requires fine location on API 31+. Registering
        // without it throws SecurityException, which would otherwise propagate
        // out of onSubscriptionsChanged and kill the process.
        try {
            subTm.registerTelephonyCallback(context.mainExecutor, callback)
        } catch (e: SecurityException) {
            telephonyCallbacks.remove(subID)
            Log.w(TAG, "Cannot listen for cell info on sub $subID: ${e.message}")
        }
    }

    private fun unregisterTelephonyCallback(tm: TelephonyManager, subID: Int) {
        val callback = telephonyCallbacks.remove(subID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                tm.createForSubscriptionId(subID).unregisterTelephonyCallback(callback)
            } catch (_: Exception) {
                // Subscription may already be gone; nothing useful to do here.
            }
        }
    }

    /**
     * Read cell info for every active subscription and recompute the band and
     * SA/NSA label.
     *
     * Uses the synchronous [TelephonyManager.getAllCellInfo] rather than
     * [TelephonyManager.requestCellInfoUpdate]. On this device the async variant
     * consistently delivered an empty list (logged as count=0) even while
     * dumpsys showed a registered NR cell, so the band came back empty. The
     * synchronous read returns whatever the modem currently has and does not
     * depend on a callback firing.
     *
     * requestCellInfoUpdate is still used once at registration time to prime
     * the cache, since that is what triggers the modem to report cells at all.
     */
    private fun refreshBands() {
        if (states.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        for (subID in states.keys.toList()) {
            val subTm = tm.createForSubscriptionId(subID)
            val cells = try {
                subTm.allCellInfo
            } catch (e: SecurityException) {
                Log.w(TAG, "getAllCellInfo denied on sub $subID: ${e.message}")
                null
            }
            // Only overwrite the cache with a non-empty result. Android throttles
            // cell info reads and returns an empty list when polled too often, so
            // replacing the cache unconditionally wiped out the last good data.
            if (cells != null && cells.isNotEmpty()) {
                latestCellInfo[subID] = cells
            }
            updateBandState(subTm, subID)
        }
    }

    /** Compact per-cell description for logcat, e.g. "Nr(arfcn=634080,bands=78,reg=true)". */
    private fun describeCells(cells: List<CellInfo>): String = cells.joinToString(",") { cell ->
        when (val id = cell.cellIdentity) {
            is CellIdentityNr -> "Nr(arfcn=${id.nrarfcn},bands=${id.bands.joinToString("/")},reg=${cell.isRegistered})"
            is CellIdentityLte -> "Lte(earfcn=${id.earfcn},bands=${id.bands.joinToString("/")},reg=${cell.isRegistered})"
            else -> "Other(${id.javaClass.simpleName},reg=${cell.isRegistered})"
        }
    }

    /**
     * Read the cached cell info and display info, derive [SubscriptionState.band]
     * and [SubscriptionState.standalone], and fire [statesChanged] on a real change.
     *
     * Band is reported as "n28", "B40", or for non-standalone "n78+B40" so the
     * desktop can see both the 5G layer and the LTE anchor it is billed against.
     */
    private fun updateBandState(subTm: TelephonyManager, subID: Int) {
        val state = states[subID] ?: return

        val cells = latestCellInfo[subID].orEmpty()
        val nrCell = cells.filterIsInstance<CellInfoNr>().firstOrNull { it.isRegistered }
            ?: cells.filterIsInstance<CellInfoNr>().firstOrNull()
        val lteCell = cells.filterIsInstance<CellInfoLte>().firstOrNull()
        val nrIdentity = nrCell?.cellIdentity as? CellIdentityNr
        val lteIdentity = lteCell?.cellIdentity as? CellIdentityLte

        // Read dataNetworkType once and derive both the base label and the
        // SA/NSA label from it. They were previously read at different moments,
        // so networkType could stay "LTE" from an earlier period while
        // standalone was already "SA".
        val dataNetworkType = subTm.dataNetworkType

        val networkType = ASUUtils.networkTypeToString(dataNetworkType)

        val standalone = ASUUtils.standaloneLabel(
            dataNetworkType,
            latestDisplayInfo[subID]?.overrideNetworkType ?: TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE,
        )

        val nrBand = nrIdentity?.let { ASUUtils.nrBand(it) }
        val lteBand = lteIdentity?.let { ASUUtils.lteBand(it) }

        val band = when {
            nrBand != null && lteBand != null -> "$nrBand+$lteBand"
            nrBand != null -> nrBand
            lteBand != null -> lteBand
            else -> ""
        }

        if (networkType != state.networkType || standalone != state.standalone || band != state.band) {
            Log.d(TAG, "sub $subID: $networkType $standalone $band")
            state.networkType = networkType
            state.standalone = standalone
            state.band = band
            statesChanged()
        }
    }

    private fun createListenerForSubscription(subID: Int): PhoneStateListener {
        return object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                val state = states[subID]
                if (state != null) {
                    val newStrength = ASUUtils.signalStrengthToLevel(signalStrength)
                    if (newStrength != state.signalStrength) {
                        state.signalStrength = newStrength
                        statesChanged()
                    }
                }
            }

            override fun onDataConnectionStateChanged(ignore: Int, networkType: Int) {
                val state = states[subID]
                if (state != null) {
                    val newNetworkType = ASUUtils.networkTypeToString(networkType)
                    if (newNetworkType != state.networkType) {
                        state.networkType = newNetworkType
                        statesChanged()
                    }
                }
            }
        }
    }

    /**
     * Get all subscriptionIDs (SIM cards) of the device
     */
    @Throws(SecurityException::class)
    fun getActiveSubscriptionIDs(): List<Int> {
        val subscriptionManager = ContextCompat.getSystemService(context, SubscriptionManager::class.java)
        if (subscriptionManager == null) {
            Log.w(TAG, "Could not get SubscriptionManager")
            return emptyList()
        }
        val subscriptionInfos = subscriptionManager.activeSubscriptionInfoList
        if (subscriptionInfos == null) {
            // This happens when there is no SIM card inserted
            Log.w(TAG, "Could not get SubscriptionInfos")
            return emptyList()
        }
        return subscriptionInfos.map { it.subscriptionId }
    }

}
