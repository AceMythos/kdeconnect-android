/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class BatteryPlugin : Plugin() {
    private val batteryInfo = NetworkPacket(PACKET_TYPE_BATTERY)

    /**
     * The latest battery information about the linked device. Will be null if the linked device
     * has not sent us any such information yet.
     *
     *
     * See [DeviceBatteryInfo] for info on which fields we expect to find.
     *
     *
     * @return the most recent packet received from the remote device. May be null
     */
    var remoteBatteryInfo: DeviceBatteryInfo? = null
        private set

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_battery)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_battery_desc)

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        var isLowBattery = false
        var thresholdEvent = THRESHOLD_EVENT_NONE

        override fun onReceive(context: Context, batteryIntent: Intent) {
            if (batteryIntent.action != Intent.ACTION_BATTERY_CHANGED) {
                thresholdEvent = when (batteryIntent.action) {
                    Intent.ACTION_BATTERY_LOW if !isLowBattery -> THRESHOLD_EVENT_BATTERY_LOW
                    else -> THRESHOLD_EVENT_NONE
                }

                isLowBattery = when (batteryIntent.action) {
                    Intent.ACTION_BATTERY_OKAY -> false
                    Intent.ACTION_BATTERY_LOW -> true
                    else -> isLowBattery
                }

                // Intents with action ACTION_BATTERY_OKAY and ACTION_BATTERY_LOW do not have extras
                // Wait for next ACTION_BATTERY_CHANGED (which should come after) to send the threshold event.
                return
            }

            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

            val currentCharge = level * 100 / scale
            val isCharging = 0 != plugged

            // Additive telemetry understood by the patched desktop daemon. Older
            // peers ignore unknown keys, so the battery protocol stays compatible.
            val temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            val technology = batteryIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty()
            val cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                batteryIntent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            } else {
                -1
            }

            if (isCharging != batteryInfo.getBoolean("isCharging")
                || currentCharge != batteryInfo.getInt("currentCharge")
                || thresholdEvent != batteryInfo.getInt("thresholdEvent")
                || plugged != batteryInfo.getInt("plugged", -1)
                || temperature != batteryInfo.getInt("temperature", Int.MIN_VALUE)
                || status != batteryInfo.getInt("status", -1)
                || health != batteryInfo.getInt("health", -1)
                || technology != batteryInfo.getString("technology")
                || cycleCount != batteryInfo.getInt("cycleCount", -1)
            ) {
                batteryInfo["currentCharge"] = currentCharge
                batteryInfo["isCharging"] = isCharging
                batteryInfo["thresholdEvent"] = thresholdEvent
                batteryInfo["plugged"] = plugged
                batteryInfo["temperature"] = temperature
                batteryInfo["status"] = status
                batteryInfo["health"] = health
                batteryInfo["technology"] = technology
                batteryInfo["cycleCount"] = cycleCount
                device.sendPacket(batteryInfo)

                // We just send a possible threshold event so reset it so we not create notifications on each change
                thresholdEvent = THRESHOLD_EVENT_NONE
            }
        }
    }

    override fun onCreate() {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        val currentState = context.registerReceiver(receiver, intentFilter)
        receiver.onReceive(context, currentState)
    }

    override fun onDestroy() {
        // It's okay to call this only once, even though we registered it for two filters
        context.unregisterReceiver(receiver)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (PACKET_TYPE_BATTERY != np.type) {
            return false
        }
        remoteBatteryInfo = DeviceBatteryInfo.fromPacket(np)
        device.onPluginsChanged()
        return true
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_BATTERY)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_BATTERY)

    companion object {
        const val PACKET_TYPE_BATTERY = "kdeconnect.battery"

        // keep these fields in sync with kdeconnect-kded:BatteryPlugin.h:ThresholdBatteryEvent
        private const val THRESHOLD_EVENT_NONE = 0
        private const val THRESHOLD_EVENT_BATTERY_LOW = 1

        fun isLowBattery(info: DeviceBatteryInfo): Boolean {
            return info.thresholdEvent == THRESHOLD_EVENT_BATTERY_LOW
        }
    }
}
