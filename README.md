# KDE Connect - Android app

KDE Connect is a multi-platform app that allows your devices to communicate (eg: your phone and your computer).

---

## ⚠️ This fork has one patch — read this first

The **Telephony integration** plugin now reports **5G SA/NSA and the band**,
not just a coarse radio type. That is the only change from upstream.

Consumer: [cosmic-internet-speed-monitor](https://github.com/AceMythos/cosmic-internet-speed-monitor)

### What you get

```
"5G SA n78"     ← standalone 5G on n78
"5G SA n28"     ← standalone 5G on n28
"LTE NSA B40"   ← 5G riding on an LTE anchor (billed as 4G)
"LTE B40"       ← plain 4G
```

Format is `<rat> [<sa|nsa>] [<band>]`. Every part after `<rat>` is optional,
so an older client still sees a valid string.

The desktop KDE Connect passes this string straight through, so **the desktop
needs no patch and no matching version.**

### Why it was needed

Upstream returns only a radio technology. Android reports `NETWORK_TYPE_NR`
**only for SA** and reports **NSA as LTE**, so nothing downstream could tell
5G standalone from 5G non-standalone, or say which band the phone is on.

### Build the APK

Needs JDK 17+, the Android SDK, and `compileSdk 37` / `build-tools 37.0.0`.

```sh
git clone https://github.com/AceMythos/kdeconnect-android
cd kdeconnect-android
./gradlew assembleDebug
```

Output: `build/outputs/apk/debug/kdeconnect-android-debug.apk`

It installs as `org.kde.kdeconnect_tp.**debug**`, so it runs **alongside** the
Play version. Your existing pairing is untouched.

```sh
adb install -r build/outputs/apk/debug/kdeconnect-android-debug.apk
```

Then open **"Debug KDE Connect"** → grant **Phone** *and* **Location** → turn on
**Telephony integration** in its settings.

> **Location is required, not optional.** `TelephonyCallback.CellInfoListener`
> needs `ACCESS_FINE_LOCATION` alongside `READ_PHONE_STATE` on API 31+. Without
> it the listener throws `SecurityException` and the app dies on startup.

### Known limitation — the band is often blank

Android frequently refuses to hand cell info to a backgrounded, non-default
app. On some OEM builds (confirmed on Android 16 / OxygenOS 16) both
`requestCellInfoUpdate()` and `getAllCellInfo()` return **zero cells**, even
while `dumpsys` clearly shows a registered cell with a valid band.

When that happens:

- `band` is reported **empty**
- the **SA/NSA label is still correct** — it comes from `dataNetworkType`,
  which needs no extra permission
- an empty result **never overwrites** the last known cells, so a throttled
  read cannot wipe good data

There is no workaround from inside the app. Making KDE Connect the default
dialer or SMS app may lift it; that has not been tested.

### Files touched

| File | Change |
|---|---|
| `ASUUtils.kt` | band resolution, SA/NSA classification |
| `ConnectivityListener.kt` | cell info polling, `networkType` refresh |
| `ConnectivityReportPlugin.kt` | packs the extra fields into the packet |

Nothing outside `plugins/connectivityreport/` is modified.

---

## Upstream documentation

## (Some) Features
- **Shared clipboard**: copy and paste between your phone and your computer (or any other device).
- **Notification sync**: Read and reply to your Android notifications from the desktop.
- **Share files and URLs** instantly from one device to another.
- **Multimedia remote control**: Use your phone as a remote for Linux media players.
- **Virtual touchpad**: Use your phone screen as your computer's touchpad and keyboard.

All this without wires, over the already existing Wi-Fi network, and using TLS encryption.

## About this app

This is a native Android port of the KDE Connect Qt app. You will find a more complete readme about KDE Connect [here](https://invent.kde.org/network/kdeconnect-kde/).

## How to install this app

You can install this app from the [Play Store](https://play.google.com/store/apps/details?id=org.kde.kdeconnect_tp) as well as [F-Droid](https://f-droid.org/repository/browse/?fdid=org.kde.kdeconnect_tp). Note you will also need to install the [desktop app](https://invent.kde.org/network/kdeconnect-kde) for it to work.

## Contributing

A lot of useful information, including how to get started working on KDE Connect and how to connect with the current developers, is on our [KDE Community Wiki page](https://community.kde.org/KDEConnect)

For bug reporting, please use [KDE's Bugzilla](https://bugs.kde.org). Please do not use the issue tracker in GitLab since we want to keep everything in one place.

To contribute patches, use [KDE Connect's Gitlab](https://invent.kde.org/network/kdeconnect-android/).
On Gitlab (as well as on our [old Phabricator](https://phabricator.kde.org/tag/kde_connect/)) you can find a task list with stuff to do and links to other relevant resources.
It is a good idea to also subscribe to the [KDE Connect mailing list](https://mail.kde.org/mailman/listinfo/kdeconnect).

Please know that all translations for all KDE apps are handled by the [localization team](https://l10n.kde.org/). If you would like to submit a translation, that should be done by working with the proper team for that language.

## License
[GNU GPL v2](https://www.gnu.org/licenses/gpl-2.0.html) and [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html)

If you are reading this from GitHub, you should know that this is just a mirror of the [KDE Project repo](https://invent.kde.org/network/kdeconnect-android/).
