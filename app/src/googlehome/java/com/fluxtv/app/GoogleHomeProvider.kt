package com.fluxtv.app

import android.content.Context
import com.google.home.FactoryRegistry
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsState
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Google Home devices through Google's Home APIs (lights, plugs and switches with on/off and brightness).
 * Signing in happens in GoogleHomeSignInActivity (Google's own consent screen, inside Google Play services).
 */
object GoogleHomeProvider {

    @Volatile private var homeClient: HomeClient? = null

    @JvmStatic
    fun client(context: Context): HomeClient {
        homeClient?.let { return it }
        synchronized(this) {
            homeClient?.let { return it }
            val registry = FactoryRegistry(
                types = listOf(OnOffLightDevice, DimmableLightDevice, ColorTemperatureLightDevice, ExtendedColorLightDevice, OnOffPluginUnitDevice),
                traits = listOf(OnOff, LevelControl),
            )
            val c = Home.getClient(context.applicationContext, HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = registry))
            homeClient = c
            return c
        }
    }

    @JvmStatic fun included(): Boolean = true

    @JvmStatic
    fun signedIn(): Boolean = Store.get("gh_signed_in") == "1"

    @JvmStatic
    fun status(): String = if (signedIn()) "Signed in" else "Not signed in"

    @JvmStatic
    fun signOut() {
        Store.put("gh_signed_in", "")
    }

    // ------------------------------------------------------------ devices

    private val devicesById = java.util.concurrent.ConcurrentHashMap<String, HomeDevice>()

    @JvmStatic
    @Throws(Exception::class)
    fun devices(): List<SmartHome.Device> = runBlocking {
        if (!signedIn()) return@runBlocking emptyList<SmartHome.Device>()
        val home = client(App.ctx)
        val state = withTimeout(15000) { home.hasPermissions().first() }
        if (state != PermissionsState.GRANTED) {
            Store.put("gh_signed_in", "")
            throw Exception("Google Home access was removed. Sign in again in Settings → Smart home.")
        }
        val rooms = HashMap<String, String>()
        runCatching {
            for (s in home.structures().list()) for (r in s.rooms().list()) rooms[r.id.id] = r.name
        }
        val out = ArrayList<SmartHome.Device>()
        for (hd in withTimeout(20000) { home.devices().list() }) {
            if (!hd.has(OnOff)) continue
            val d = SmartHome.Device()
            d.id = "gh:" + hd.id.id
            d.name = hd.name
            d.room = runCatching { hd.roomId?.id?.let { rooms[it] } }.getOrNull() ?: ""
            d.kind = if (hd.has(OnOffPluginUnitDevice)) SmartHome.SWITCH else SmartHome.LIGHT
            val onOff = onOffOf(hd)
            d.on = onOff?.onOff == true
            d.state = if (d.on) "on" else "off"
            d.available = onOff != null
            val level = levelOf(hd)
            if (level != null) {
                val cur = level.currentLevel?.toInt() ?: 0
                d.level = if (d.on) Math.round(cur / 254.0 * 100).toInt().coerceIn(1, 100) else 0
            }
            devicesById[d.id] = hd
            out.add(d)
        }
        out
    }

    private suspend fun onOffOf(hd: HomeDevice): OnOff? =
        hd.type(DimmableLightDevice).firstOrNull()?.standardTraits?.onOff
            ?: hd.type(ColorTemperatureLightDevice).firstOrNull()?.standardTraits?.onOff
            ?: hd.type(ExtendedColorLightDevice).firstOrNull()?.standardTraits?.onOff
            ?: hd.type(OnOffLightDevice).firstOrNull()?.standardTraits?.onOff
            ?: hd.type(OnOffPluginUnitDevice).firstOrNull()?.standardTraits?.onOff

    private suspend fun levelOf(hd: HomeDevice): LevelControl? =
        hd.type(DimmableLightDevice).firstOrNull()?.standardTraits?.levelControl
            ?: hd.type(ColorTemperatureLightDevice).firstOrNull()?.standardTraits?.levelControl
            ?: hd.type(ExtendedColorLightDevice).firstOrNull()?.standardTraits?.levelControl

    // ------------------------------------------------------------ commands

    @JvmStatic
    @Throws(Exception::class)
    fun setOn(d: SmartHome.Device, on: Boolean) = runBlocking {
        val hd = devicesById[d.id] ?: throw Exception("Device not found; reopen the panel")
        val t = onOffOf(hd) ?: throw Exception("This device can't be switched")
        if (on) t.on() else t.off()
        Unit
    }

    @JvmStatic
    @Throws(Exception::class)
    fun setLevel(d: SmartHome.Device, level: Int) = runBlocking {
        val hd = devicesById[d.id] ?: throw Exception("Device not found; reopen the panel")
        if (level <= 0) {
            onOffOf(hd)?.off()
            return@runBlocking Unit
        }
        val lc = levelOf(hd) ?: throw Exception("This light can't be dimmed")
        val raw = Math.round(level / 100.0 * 254).toInt().coerceIn(1, 254)
        if (lc.supports(LevelControl.Command.MoveToLevelWithOnOff)) {
            lc.moveToLevelWithOnOff(
                level = raw.toUByte(),
                transitionTime = null,
                optionsMask = LevelControlTrait.OptionsBitmap(),
                optionsOverride = LevelControlTrait.OptionsBitmap(),
            )
        } else {
            onOffOf(hd)?.on()
            lc.moveToLevel(
                level = raw.toUByte(),
                transitionTime = null,
                optionsMask = LevelControlTrait.OptionsBitmap(),
                optionsOverride = LevelControlTrait.OptionsBitmap(),
            )
        }
        Unit
    }
}
