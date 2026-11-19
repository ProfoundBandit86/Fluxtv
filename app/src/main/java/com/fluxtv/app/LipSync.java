package com.fluxtv.app;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * Lip sync: when the sound reaches you later than the picture (Bluetooth speakers, soundbars, receivers that add
 * their own delay), the picture is held back by the same amount. Remembered per sound output (TV speakers,
 * soundbar on HDMI ARC, each Bluetooth device) and optionally per show; both add up.
 *
 * Media3 has no audio-delay setting, so the player's audio clock is offset: the picture follows the audio
 * clock, and reporting the clock "late" by N ms makes each frame appear N ms later.
 */
final class LipSync {

    private LipSync() { }

    /** How late the sound is, in ms (what the picture is held back by). Read by the audio clock. */
    static volatile long lateMs;
    private static String showKey;

    // ------------------------------------------------------------ which output

    /** A key for the sound output in use: "bt:<name>", "arc", "usb" or "tv". */
    static String deviceKey(Context c) {
        AudioDeviceInfo d = output(c);
        if (d == null) return "tv";
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "bt:" + d.getProductName();
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
                return "arc";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "usb";
            default:
                return "tv";
        }
    }

    static String deviceName(Context c) {
        String k = deviceKey(c);
        if (k.startsWith("bt:")) return "Bluetooth · " + k.substring(3);
        switch (k) {
            case "arc": return "Soundbar / receiver (HDMI ARC)";
            case "usb": return "USB audio";
            default: return "TV speakers";
        }
    }

    /** The output most likely in use: Bluetooth if connected, then ARC, USB, else the TV. */
    @Nullable
    private static AudioDeviceInfo output(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo best = null;
            int bestRank = 0;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType(), rank;
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER) rank = 4;
                else if (t == AudioDeviceInfo.TYPE_HDMI_ARC || t == AudioDeviceInfo.TYPE_HDMI_EARC) rank = 3;
                else if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) rank = 2;
                else rank = 1;
                if (rank > bestRank) { best = d; bestRank = rank; }
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------ saved amounts

    static int deviceMs(Context c) {
        return num("lipsync_dev:" + deviceKey(c));
    }

    static void setDeviceMs(Context c, int ms) {
        Store.put("lipsync_dev:" + deviceKey(c), ms == 0 ? "" : String.valueOf(ms));
        refresh(c);
    }

    static int showMs() {
        return showKey == null ? 0 : num("lipsync_show:" + showKey);
    }

    static void setShowMs(Context c, int ms) {
        if (showKey == null) return;
        Store.put("lipsync_show:" + showKey, ms == 0 ? "" : String.valueOf(ms));
        refresh(c);
    }

    static boolean hasShow() {
        return showKey != null;
    }

    /** Called when playback starts: show = the show or movie (null for Live TV), so its own amount applies. */
    static void use(Context c, @Nullable String show) {
        showKey = show == null || show.isEmpty() ? null : show;
        refresh(c);
    }

    static void refresh(Context c) {
        lateMs = deviceMs(c) + showMs();
    }

    private static int num(String key) {
        try { return Integer.parseInt(Store.get(key)); } catch (Exception e) { return 0; }
    }

    /** "In sync" / "Sound 120 ms earlier" / "Sound 50 ms later". */
    static String label(int ms) {
        if (ms == 0) return "In sync";
        return "Sound " + Math.abs(ms) + " ms " + (ms > 0 ? "earlier" : "later");
    }

    // ------------------------------------------------------------ the audio clock

    /** Wraps the player's audio output so its clock runs lateMs behind (the picture waits for the sound). */
    static AudioSink wrap(AudioSink sink) {
        return new ForwardingAudioSink(sink) {
            @Override
            public long getCurrentPositionUs(boolean sourceEnded) {
                long p = super.getCurrentPositionUs(sourceEnded);
                long late = lateMs;
                if (p == AudioSink.CURRENT_POSITION_NOT_SET || late == 0) return p;
                return Math.max(0, p - late * 1000);
            }
        };
    }
}
