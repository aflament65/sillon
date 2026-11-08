package fr.aflament.sillon;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* Décodeur Android (M4A, FLAC, OGG, Opus, WAV, MP3…) : envoie du son 16 bits stéréo au « Sink » */
final class AudioDecoder {
    static long decode(Context ctx, Uri uri, PcmTools.Sink sink, PcmTools.Prog prog) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int ti = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { ti = i; fmt = f; break; }
            }
            if (ti < 0) throw new IOException("Aucune piste audio dans ce fichier");
            ex.selectTrack(ti);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            sink.format(rate);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inDone = false;
            short[] st = new short[0];
            double last = -1;
            while (true) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = dec.dequeueOutputBuffer(bi, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    enc = of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    sink.format(rate);
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && bi.size > 0) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        ob.order(ByteOrder.nativeOrder());
                        int frames;
                        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                            java.nio.FloatBuffer fb = ob.asFloatBuffer();
                            frames = fb.remaining() / ch;
                            if (st.length < frames * 2) st = new short[frames * 2];
                            for (int k = 0; k < frames; k++) {
                                float l = fb.get(k * ch), r = ch > 1 ? fb.get(k * ch + 1) : l;
                                st[2 * k] = (short) Math.max(-32768, Math.min(32767, Math.round(l * 32767)));
                                st[2 * k + 1] = (short) Math.max(-32768, Math.min(32767, Math.round(r * 32767)));
                            }
                        } else {
                            java.nio.ShortBuffer sb = ob.asShortBuffer();
                            frames = sb.remaining() / ch;
                            if (st.length < frames * 2) st = new short[frames * 2];
                            for (int k = 0; k < frames; k++) {
                                short l = sb.get(k * ch), r = ch > 1 ? sb.get(k * ch + 1) : l;
                                st[2 * k] = l; st[2 * k + 1] = r;
                            }
                        }
                        if (frames > 0) sink.block(st, frames);
                        if (prog != null && durUs > 0) {
                            double p = Math.min(1, bi.presentationTimeUs / (double) durUs);
                            if (p - last >= 0.005) { last = p; prog.p(p); }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            sink.finish();
            return durUs;
        } catch (IOException e) {
            sink.abort();
            throw e;
        } catch (Exception e) {
            sink.abort();
            throw new IOException(e.getMessage() != null ? e.getMessage() : "Décodage impossible", e);
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception e) { }
            ex.release();
        }
    }
}
