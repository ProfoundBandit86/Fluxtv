package com.fluxtv.app;

import android.content.Context;

import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

/** One place that builds the player, so movies, Live TV and recordings all get the same codec support. */
public final class Players {

    private Players() { }

    public static ExoPlayer create(Context c) {
        // Follow http→https and cross-host redirects (common with IPTV load balancers and debrid links).
        androidx.media3.datasource.DefaultHttpDataSource.Factory http = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("FluxTV/1.0 (Android TV)")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        // fluxiptv:// (MAC portal channels and replays) becomes a fresh stream link on the loading thread.
        androidx.media3.datasource.DataSource.Factory data = new androidx.media3.datasource.ResolvingDataSource.Factory(
                new SmbDataSource.Factory(new androidx.media3.datasource.DefaultDataSource.Factory(c, http)),
                spec -> {
                    String u = spec.uri.toString();
                    if (!u.startsWith("fluxiptv://")) return spec;
                    try {
                        return spec.withUri(android.net.Uri.parse(Iptv.resolve(u)));
                    } catch (Exception e) {
                        throw new java.io.IOException(e.getMessage(), e);
                    }
                });
        // Play as much as possible in-app:
        //  - the box's own decoders first (hardware, and HDMI passthrough to the receiver for Dolby/DTS),
        //  - FFmpeg software audio when the box can't decode or pass a format through (DTS, TrueHD, E-AC3…),
        //  - if a hardware decoder fails to start, try the next one rather than giving up.
        //  - the sound output is wrapped for lip sync (LipSync: the picture waits for late sound).
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c) {
            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                                                             boolean enableAudioTrackPlaybackParams) {
                androidx.media3.exoplayer.audio.AudioSink sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams);
                return sink == null ? null : LipSync.wrap(sink);
            }
        }
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        androidx.media3.extractor.DefaultExtractorsFactory extractors = new androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS);
        // Start playing after 1.5 s of video instead of 2.5 s (faster channel changes and starts); a bit more after a stall.
        androidx.media3.exoplayer.DefaultLoadControl load = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
                .build();
        return new ExoPlayer.Builder(c, renderers)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(data, extractors))
                .setLoadControl(load)
                .build();
    }
}
