/*
 * SPDX-FileCopyrightText: 2025 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.plugins.connectivityreport

import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

object ASUUtils {

    fun signalStrengthToLevel(signalStrength: SignalStrength?): Int {
        if (signalStrength == null) return 0
        return signalStrength.level
    }

    /**
     * Classify the radio access as standalone 5G ("SA"), non-standalone 5G
     * ("NSA"), or neither (empty string).
     *
     * Per AOSP, [TelephonyManager.NETWORK_TYPE_NR] is only ever reported for
     * SA. NSA stays on NETWORK_TYPE_LTE with NR riding on the secondary
     * carrier, which TelephonyDisplayInfo reports as OVERRIDE_NETWORK_TYPE_NR_NSA.
     */
    fun standaloneLabel(dataNetworkType: Int, overrideNetworkType: Int): String = when {
        dataNetworkType == TelephonyManager.NETWORK_TYPE_NR -> "SA"
        dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE &&
            (overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) -> "NSA"
        else -> ""
    }

    /**
     * Band label for an NR cell.
     *
     * The modem-reported band is authoritative unless the ARFCN contradicts it.
     * n77 and n78 have fully overlapping downlink NR-ARFCN ranges per
     * TS 38.101-1 (n77 620000-680000, n78 620000-653333), so n77 contains all
     * of n78 and the ARFCN alone can never identify n78. Measured on a
     * Nord CE 5: ARFCN 632736 and 634080 both sit inside the n78 range but were
     * misreported as n77 while the ARFCN table was given precedence.
     *
     * [isArfcnConsistent] only rejects a band whose frequency range excludes the
     * ARFCN outright, which is enough to discard the constant [1] that some
     * MediaTek modems report regardless of the real band.
     */
    fun nrBand(identity: CellIdentityNr): String? {
        val reported = identity.bands.firstOrNull { it in 1..90 }?.let { "n$it" }
        return if (reported != null && isArfcnConsistent(reported, identity.nrarfcn)) {
            reported
        } else {
            nrBandFromArfcn(identity.nrarfcn)
        }
    }

    /**
     * True when [band] is plausible for [nrarfcn]. Because n77 and n78 overlap,
     * this accepts either within their shared range and rejects only bands the
     * ARFCN falls outside of.
     */
    private fun isArfcnConsistent(band: String, nrarfcn: Int): Boolean {
        if (nrarfcn == Int.MAX_VALUE || nrarfcn < 0) return true
        return when (band) {
            "n77", "n78" -> nrarfcn in 620_000..680_000
            "n28" -> nrarfcn in 151_600..160_600
            "n41" -> nrarfcn in 499_200..537_999
            "n79" -> nrarfcn in 693_334..733_333
            else -> true
        }
    }

    /**
     * Downlink NR-ARFCN to operating band, per TS 38.101-1. Only a fallback,
     * since n77 covers the whole n78 range. n28 is checked first because its
     * range does not overlap theirs.
     */
    fun nrBandFromArfcn(nrarfcn: Int): String? {
        if (nrarfcn == Int.MAX_VALUE || nrarfcn < 0) return null
        return when (nrarfcn) {
            // n28: 758-803 MHz
            in 151_600..160_600 -> "n28"
            // n77: 3300-4200 MHz (620000-680000), which also covers all of n78.
            in 620_000..680_000 -> "n77"
            // n41: 2496-2690 MHz
            in 499_200..537_999 -> "n41"
            // n79: 4400-5000 MHz
            in 693_334..733_333 -> "n79"
            else -> null
        }
    }

    /** Band label for an LTE cell, using the band number the modem reported. */
    fun lteBand(identity: CellIdentityLte): String? =
        identity.bands.firstOrNull { it > 0 }?.let { "B$it" }

    fun networkTypeToString(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_1xRTT -> "CDMA2000"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
            TelephonyManager.NETWORK_TYPE_IWLAN, TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
            else -> "Unknown"
        }
    }
}
