package com.fluxtv.app

import android.content.Context
import com.google.home.DeviceType
import com.google.home.FactoryConstants
import com.google.home.FactoryRegistry
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsState
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.OnOff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Google Home devices through Google's Home APIs: anything with on/off (lights, plugs, wall switches…) and
 * brightness where the device supports it. Signing in happens in GoogleHomeSignInActivity.
 */
object GoogleHomeProvider {

    @Volatile private var homeClient: HomeClient? = null

    @JvmStatic
    fun client(context: Context): HomeClient {
        homeClient?.let { return it }
        synchronized(this) {
            homeClient?.let { return it }
            // Every device type and trait, so wall switches show up whatever type their maker reports.
            val registry = FactoryRegistry(traits = FactoryConstants.allTraits, types = FactoryConstants.allTypes)
            val c = Home.getClient(context.applicationContext, HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = registry))
            homeClient = c
            return c
        }
    }

    @JvmStatic fun included(): Boolean = true

    @JvmStatic fun signedIn(): Boolean = Store.get("gh_signed_in") == "1"

    @JvmStatic fun status(): String = if (signedIn()) "Signed in" else "Not signed in"

    @JvmStatic fun signOut() { Store.put("gh_signed_in", "") }

    // ------------------------------------------------------------ devices

    private val devicesById = java.util.concurrent.ConcurrentHashMap<String, HomeDevice>()

    /** The device's first part that can be switched (and its brightness control, if any). */
    private suspend fun controls(hd: HomeDevice): Pair<OnOff?, LevelControl?> {
        val types: Set<DeviceType> = withTimeout(10000) { hd.types().first() }
        var onOff: OnOff? = null
        var level: LevelControl? = null
        for (t in types) {
            val o = runCatching { t.trait(OnOff) }.getOrNull()
            if (o != null && onOff == null) {
                onOff = o
                level = runCatching { t.trait(LevelControl) }.getOrNull()
            }
        }
        return Pair(onOff, level)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun devices(): List<SmartHome.Device> = runBlocking {
        if (!signedIn()) return@runBlocking emptyList<SmartHome.Device>()
        val home = client(App.ctx)
        val state = withTimeout(15000) { home.hasPermissions().first() }
        if (state == PermissionsState.NOT_GRANTED) {
            Store.put("gh_signed_in", "")
            throw Exception("Google Home access was removed. Connect again in Settings → Smart home.")
        }
        val out = ArrayList<SmartHome.Device>()
        for (hd in withTimeout(20000) { home.devices().list() }) {
            val (onOff, level) = runCatching { controls(hd) }.getOrDefault(Pair(null, null))
            if (onOff == null) continue   // cameras, speakers, sensors… aren't switches
            val d = SmartHome.Device()
            d.id = "gh:" + hd.id.id
            d.name = hd.name
            d.room = runCatching { hd.room()?.name }.getOrNull() ?: ""
            val typeNames = runCatching { hd.types().first().joinToString { it.javaClass.simpleName } }.getOrDefault("")
            d.kind = if (typeNames.contains("Light") && !typeNames.contains("Switch")) SmartHome.LIGHT else SmartHome.SWITCH
            if (typeNames.contains("Dimm") || typeNames.contains("Light")) d.kind = SmartHome.LIGHT
            d.on = onOff.onOff == true
            d.state = if (d.on) "on" else "off"
            if (level != null) {
                val cur = level.currentLevel?.toInt() ?: 0
                d.level = if (d.on) Math.round(cur / 254.0 * 100).toInt().coerceIn(1, 100) else 0
            }
            devicesById[d.id] = hd
            out.add(d)
        }
        out
    }

    // ------------------------------------------------------------ commands

    @JvmStatic
    @Throws(Exception::class)
    fun setOn(d: SmartHome.Device, on: Boolean) {
        runBlocking {
            val hd = devicesById[d.id] ?: throw Exception("Device not found; reopen the panel")
            val t = controls(hd).first ?: throw Exception("This device can't be switched")
            if (on) t.on() else t.off()
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun setLevel(d: SmartHome.Device, level: Int) {
        runBlocking {
            val hd = devicesById[d.id] ?: throw Exception("Device not found; reopen the panel")
            val (onOff, lc) = controls(hd)
            if (level <= 0) {
                onOff?.off()
                return@runBlocking
            }
            if (lc == null) {
                onOff?.on()
                return@runBlocking
            }
            val raw = Math.round(level / 100.0 * 254).toInt().coerceIn(1, 254).toUByte()
            if (lc.supports(LevelControl.Command.MoveToLevelWithOnOff)) {
                lc.moveToLevelWithOnOff(raw, null, LevelControlTrait.OptionsBitmap(), LevelControlTrait.OptionsBitmap())
            } else {
                onOff?.on()
                lc.moveToLevel(raw, null, LevelControlTrait.OptionsBitmap(), LevelControlTrait.OptionsBitmap())
            }
        }
    }
}
