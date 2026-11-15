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
        androidx.media3.datasource.DataSource.Factory data = new SmbDataSource.Factory(
                new androidx.media3.datasource.DefaultDataSource.Factory(c, http));
        // Play as much as possible in-app:
        //  - the box's own decoders first (hardware, and HDMI passthrough to the receiver for Dolby/DTS),
        //  - FFmpeg software audio when the box can't decode or pass a format through (DTS, TrueHD, E-AC3…),
        //  - if a hardware decoder fails to start, try the next one rather than giving up.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(c)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        androidx.media3.extractor.DefaultExtractorsFactory extractors = new androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS);
        return new ExoPlayer.Builder(c, renderers)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(data, extractors))
                .build();
    }
}
