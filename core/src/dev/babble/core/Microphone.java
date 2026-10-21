package dev.babble.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * 16 kHz mono capture in 20 ms frames. Prefers OpenAL (LWJGL, already loaded by Minecraft, and
 * reliable with PulseAudio/PipeWire), falls back to javax.sound.
 */
public abstract class Microphone implements AutoCloseable {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME = SAMPLE_RATE / 50; // 20 ms

    /** Blocks until a full frame is available; returns false if the device died. */
    public abstract boolean read(float[] frame) throws InterruptedException;

    public abstract String describe();

    @Override
    public abstract void close();

    /** Capture device names for the settings screen (OpenAL's list, else javax.sound mixers). */
    public static java.util.List<String> listDevices() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            OpenAl.ensureAlc();
            List<String> names = org.lwjgl.openal.ALUtil.getStringList(0L, org.lwjgl.openal.ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
            if (names != null) out.addAll(names);
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, new AudioFormat(SAMPLE_RATE, 16, 1, true, false));
                for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                    if (AudioSystem.getMixer(mi).isLineSupported(info)) out.add(mi.getName());
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static Microphone open(String backend, String device) {
        boolean wantAl = !"javasound".equalsIgnoreCase(backend);
        boolean wantJs = !"openal".equalsIgnoreCase(backend);
        if (wantAl) {
            try {
                return new OpenAl(device);
            } catch (Throwable t) {
                Log.info("OpenAL capture unavailable (" + t + ")" + (wantJs ? ", trying javax.sound" : ""));
            }
        }
        if (wantJs) {
            try {
                return new JavaSound(device);
            } catch (Throwable t) {
                Log.error("javax.sound capture unavailable", t);
            }
        }
        throw new IllegalStateException("No microphone could be opened");
    }

    static final class OpenAl extends Microphone {
        private final long dev;
        private final ByteBuffer buf;
        private final String name;

        OpenAl(String device) {
            ensureAlc();
            try {
                List<String> names = org.lwjgl.openal.ALUtil.getStringList(0L, org.lwjgl.openal.ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
                if (names != null) Log.info("OpenAL capture devices: " + names);
            } catch (Throwable ignored) {
            }
            String want = device == null || device.isEmpty() ? null : device;
            long d = org.lwjgl.openal.ALC11.alcCaptureOpenDevice(want, SAMPLE_RATE, org.lwjgl.openal.AL10.AL_FORMAT_MONO16, SAMPLE_RATE * 2);
            if (d == 0 && want != null) {
                Log.info("OpenAL device '" + want + "' not found, using default");
                d = org.lwjgl.openal.ALC11.alcCaptureOpenDevice((CharSequence) null, SAMPLE_RATE, org.lwjgl.openal.AL10.AL_FORMAT_MONO16, SAMPLE_RATE * 2);
            }
            if (d == 0) throw new IllegalStateException("alcCaptureOpenDevice failed");
            dev = d;
            String n = org.lwjgl.openal.ALC10.alcGetString(d, org.lwjgl.openal.ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
            name = "OpenAL: " + (n == null ? "default" : n);
            buf = ByteBuffer.allocateDirect(FRAME * 2).order(ByteOrder.nativeOrder());
            org.lwjgl.openal.ALC11.alcCaptureStart(dev);
        }

        /** Minecraft creates ALC when its sound engine starts; outside the game (tests) we do it. */
        static void ensureAlc() {
            try {
                org.lwjgl.openal.ALC.getFunctionProvider();
            } catch (RuntimeException notCreated) {
                org.lwjgl.openal.ALC.create();
            }
        }

        @Override
        public boolean read(float[] frame) throws InterruptedException {
            while (org.lwjgl.openal.ALC10.alcGetInteger(dev, org.lwjgl.openal.ALC11.ALC_CAPTURE_SAMPLES) < FRAME) {
                if (org.lwjgl.openal.ALC10.alcGetError(dev) != org.lwjgl.openal.ALC10.ALC_NO_ERROR) return false;
                Thread.sleep(4);
            }
            buf.clear();
            org.lwjgl.openal.ALC11.alcCaptureSamples(dev, buf, FRAME);
            for (int i = 0; i < FRAME; i++) frame[i] = buf.getShort(i * 2) / 32768f;
            return true;
        }

        @Override
        public String describe() {
            return name;
        }

        @Override
        public void close() {
            org.lwjgl.openal.ALC11.alcCaptureStop(dev);
            org.lwjgl.openal.ALC11.alcCaptureCloseDevice(dev);
        }
    }

    static final class JavaSound extends Microphone {
        private final TargetDataLine line;
        private final byte[] bytes = new byte[FRAME * 2];
        private final String name;

        JavaSound(String device) throws Exception {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            TargetDataLine l = null;
            String n = "default";
            if (device != null && !device.isEmpty()) {
                for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                    Mixer m = AudioSystem.getMixer(mi);
                    if (mi.getName().contains(device) && m.isLineSupported(info)) {
                        l = (TargetDataLine) m.getLine(info);
                        n = mi.getName();
                        break;
                    }
                }
            }
            if (l == null) l = (TargetDataLine) AudioSystem.getLine(info);
            l.open(fmt, FRAME * 2 * 10);
            l.start();
            line = l;
            name = "javax.sound: " + n;
        }

        @Override
        public boolean read(float[] frame) {
            int off = 0;
            while (off < bytes.length) {
                int r = line.read(bytes, off, bytes.length - off);
                if (r <= 0) return false;
                off += r;
            }
            for (int i = 0; i < FRAME; i++) {
                frame[i] = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8)) / 32768f;
            }
            return true;
        }

        @Override
        public String describe() {
            return name;
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }
}
