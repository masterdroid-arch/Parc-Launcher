package com.brightblu.parc.feature.search.extras

import android.provider.Settings

/**
 * One page of the system Settings app that search can open (T102). [keywords] hold the common
 * synonyms people type ("wifi", "internet", "network" for Wi-Fi). [action] is a `Settings.ACTION_*`
 * string (or its literal where the constant is not public); the device may not have the page, so it
 * is only offered after a PackageManager check.
 */
data class PhoneSetting(val title: String, val action: String, val keywords: List<String> = emptyList())

/**
 * The static table behind "Phone settings" search. Pure data: no platform call is made here, nothing
 * is read from the device and nothing is indexed to disk. Order is the tie-break order.
 */
object PhoneSettingsTable {

    /** Every action in [all]; the manifest `<queries>` block must list exactly these (a test checks it). */
    val actions: List<String> get() = all.map { it.action }.distinct()

    val all: List<PhoneSetting> = listOf(
        PhoneSetting("Wi-Fi", Settings.ACTION_WIFI_SETTINGS,
            listOf("wifi", "wi fi", "wireless", "internet", "network", "router")),
        PhoneSetting("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS,
            listOf("pair", "headphones", "earbuds", "speaker", "wireless devices")),
        PhoneSetting("Mobile network", Settings.ACTION_DATA_ROAMING_SETTINGS,
            listOf("mobile data", "cellular", "sim", "roaming", "4g", "5g", "carrier", "apn")),
        PhoneSetting("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            listOf("flight mode", "aeroplane", "offline")),
        PhoneSetting("Hotspot and tethering", "android.settings.TETHER_SETTINGS",
            listOf("hotspot", "tether", "share internet", "portable hotspot")),
        PhoneSetting("VPN", Settings.ACTION_VPN_SETTINGS, listOf("virtual private network", "proxy")),
        PhoneSetting("Data usage", Settings.ACTION_DATA_USAGE_SETTINGS,
            listOf("data saver", "mobile data usage", "traffic", "limit")),
        PhoneSetting("NFC", Settings.ACTION_NFC_SETTINGS, listOf("contactless", "tap to pay", "near field")),
        PhoneSetting("Display", Settings.ACTION_DISPLAY_SETTINGS,
            listOf("screen", "brightness", "dark mode", "dark theme", "screen timeout", "sleep", "font size", "text size", "refresh rate")),
        PhoneSetting("Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS,
            listOf("battery", "power saving", "low power", "save power")),
        PhoneSetting("Battery usage", "android.intent.action.POWER_USAGE_SUMMARY",
            listOf("battery", "power", "drain", "charge", "screen on time")),
        PhoneSetting("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            listOf("memory", "space", "free up space", "disk", "sd card")),
        PhoneSetting("Apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
            listOf("applications", "app info", "uninstall", "force stop", "clear cache")),
        PhoneSetting("Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            listOf("default browser", "default launcher", "default app", "assistant", "home app")),
        PhoneSetting("Home app", Settings.ACTION_HOME_SETTINGS, listOf("launcher", "default home", "home screen")),
        PhoneSetting("Notifications", "android.settings.NOTIFICATION_SETTINGS",
            listOf("alerts", "notification", "do not disturb", "lock screen notifications")),
        PhoneSetting("Notification access", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            listOf("notification listener", "badges")),
        PhoneSetting("Sound", Settings.ACTION_SOUND_SETTINGS,
            listOf("volume", "ringtone", "vibration", "silent", "ring", "media volume", "alarm volume")),
        PhoneSetting("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            listOf("gps", "location services")),
        PhoneSetting("Privacy", Settings.ACTION_PRIVACY_SETTINGS,
            listOf("permissions manager", "camera access", "microphone access", "ads", "clipboard")),
        PhoneSetting("Security", Settings.ACTION_SECURITY_SETTINGS,
            listOf("screen lock", "password", "pin", "fingerprint", "face unlock", "biometrics")),
        PhoneSetting("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS,
            listOf("talkback", "magnification", "color correction", "colour", "hearing", "vision", "large text")),
        PhoneSetting("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            listOf("developer", "usb debugging", "adb", "debug", "animation scale", "wireless debugging")),
        PhoneSetting("Date and time", Settings.ACTION_DATE_SETTINGS,
            listOf("clock", "time zone", "timezone", "24 hour", "automatic time")),
        PhoneSetting("Language", Settings.ACTION_LOCALE_SETTINGS,
            listOf("languages", "region", "locale", "translate", "system language")),
        PhoneSetting("Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS,
            listOf("input method", "typing", "on-screen keyboard", "autocorrect", "spell check")),
        PhoneSetting("Accounts", Settings.ACTION_SYNC_SETTINGS,
            listOf("sync", "google account", "sign in", "email account")),
        PhoneSetting("Cast", Settings.ACTION_CAST_SETTINGS, listOf("screen mirroring", "chromecast", "wireless display")),
        PhoneSetting("Printing", Settings.ACTION_PRINT_SETTINGS, listOf("printer", "print service")),
        PhoneSetting("Screen saver", Settings.ACTION_DREAM_SETTINGS, listOf("daydream", "screensaver", "idle")),
        PhoneSetting("About phone", Settings.ACTION_DEVICE_INFO_SETTINGS,
            listOf("device info", "model", "android version", "build number", "imei", "software update", "serial")),
        PhoneSetting("All settings", Settings.ACTION_SETTINGS, listOf("settings", "preferences", "system settings")),
    )
}
