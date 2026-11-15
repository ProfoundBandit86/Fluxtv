package com.fluxtv.app;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/** Lets the player open smb:// recordings directly from a network share (with seeking). */
@androidx.media3.common.util.UnstableApi
public final class SmbDataSource extends BaseDataSource {

    /** Uses SMB for smb:// links and the normal data source for everything else. */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory fallback;

        public Factory(DataSource.Factory fallback) {
            this.fallback = fallback;
        }

        @Override
        public DataSource createDataSource() {
            return new Switching(fallback.createDataSource());
        }
    }

    private Uri uri;
    private SmbFile file;
    private SmbRandomAccessFile raf;
    private long remaining;
    private boolean opened;

    public SmbDataSource() {
        super(true);
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        try {
            file = new SmbFile(uri.toString(), Smb.context());
            raf = new SmbRandomAccessFile(file, "r");
            long len = raf.length();
            raf.seek(spec.position);
            remaining = spec.length != C.LENGTH_UNSET ? spec.length : len - spec.position;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        opened = true;
        transferStarted(spec);
        return remaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int n = raf.read(buffer, offset, (int) Math.min(length, remaining));
        if (n < 0) return C.RESULT_END_OF_INPUT;
        remaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        try {
            if (raf != null) raf.close();
            if (file != null) file.close();
        } finally {
            raf = null;
            file = null;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    /** Picks SMB or the default source per link. */
    private static final class Switching implements DataSource {
        private final DataSource normal;
        private final SmbDataSource smb = new SmbDataSource();
        private DataSource current;

        Switching(DataSource normal) {
            this.normal = normal;
        }

        @Override
        public void addTransferListener(TransferListener l) {
            normal.addTransferListener(l);
            smb.addTransferListener(l);
        }

        @Override
        public long open(DataSpec spec) throws IOException {
            current = "smb".equalsIgnoreCase(spec.uri.getScheme()) ? smb : normal;
            return current.open(spec);
        }

        @Override
        public int read(byte[] b, int o, int l) throws IOException {
            return current.read(b, o, l);
        }

        @Override
        public Uri getUri() {
            return current == null ? null : current.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return current == null ? java.util.Collections.emptyMap() : current.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            if (current != null) current.close();
        }
    }
}
