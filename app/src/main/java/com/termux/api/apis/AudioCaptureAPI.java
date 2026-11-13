package com.termux.api.apis;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the device's internal (playback) audio to a WAV file using
 * MediaProjection + AudioPlaybackCapture (Android 10 / API 29+).
 *
 * Usage from Termux (a one time "Start now" consent dialog is shown by Android):
 *   am start -n com.termux.api/.apis.AudioCaptureAPI\$CaptureActivity --es cmd start --es file /sdcard/out.wav
 *   am start -n com.termux.api/.apis.AudioCaptureAPI\$CaptureActivity --es cmd stop
 *
 * Optional extras: --ei srate 44100  --ei channels 2  --ei limit SECONDS
 */
public class AudioCaptureAPI {

    private static final String LOG_TAG = "AudioCaptureAPI";

    public static final String CMD_START = "start";
    public static final String CMD_STOP = "stop";

    /**
     * Transparent activity that asks for RECORD_AUDIO and the MediaProjection consent,
     * then hands the result to {@link CaptureService}. Also used to stop a capture.
     */
    public static class CaptureActivity extends Activity {

        private static final int REQ_PERMISSION = 2001;
        private static final int REQ_PROJECTION = 2002;

        private String file;
        private int srate;
        private int channels;
        private int limit;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Intent in = getIntent();
            String cmd = in.getStringExtra("cmd");
            if (cmd == null) cmd = CMD_START;

            if (CMD_STOP.equals(cmd)) {
                Intent stop = new Intent(this, CaptureService.class);
                stop.setAction(CMD_STOP);
                startService(stop);
                finish();
                return;
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(this, "Audio capture needs Android 10 or newer", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            if (CaptureService.isCapturing) {
                Toast.makeText(this, "Audio capture already running", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            file = in.getStringExtra("file");
            srate = in.getIntExtra("srate", 44100);
            channels = in.getIntExtra("channels", 2) == 1 ? 1 : 2;
            limit = in.getIntExtra("limit", 0);

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSION);
            } else {
                requestProjection();
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode == REQ_PERMISSION) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    requestProjection();
                } else {
                    Toast.makeText(this, "RECORD_AUDIO permission denied", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }

        private void requestProjection() {
            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == REQ_PROJECTION) {
                if (resultCode == RESULT_OK && data != null) {
                    Intent svc = new Intent(this, CaptureService.class);
                    svc.setAction(CMD_START);
                    svc.putExtra("resultCode", resultCode);
                    svc.putExtra("data", data);
                    svc.putExtra("file", file);
                    svc.putExtra("srate", srate);
                    svc.putExtra("channels", channels);
                    svc.putExtra("limit", limit);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                } else {
                    Toast.makeText(this, "Audio capture permission denied", Toast.LENGTH_LONG).show();
                }
                finish();
            }
        }
    }

    /**
     * Foreground service (type mediaProjection) that does the actual capturing.
     */
    public static class CaptureService extends Service {

        private static final String CHANNEL_ID = "termux_audio_capture";
        private static final int NOTIFICATION_ID = 7421;

        static volatile boolean isCapturing = false;

        private volatile boolean running = false;
        private Thread captureThread;
        private MediaProjection projection;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String action = intent == null ? null : intent.getAction();

            if (CMD_STOP.equals(action)) {
                if (captureThread != null) {
                    running = false;
                } else {
                    stopSelf();
                }
                return START_NOT_STICKY;
            }

            if (!CMD_START.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }

            // Must be called quickly after startForegroundService()
            startForegroundCompat();

            if (isCapturing || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                fail("Audio capture already running or not supported");
                return START_NOT_STICKY;
            }

            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("data");
            String file = intent.getStringExtra("file");
            final int srate = intent.getIntExtra("srate", 44100);
            final int channels = intent.getIntExtra("channels", 2) == 1 ? 1 : 2;
            final int limit = intent.getIntExtra("limit", 0);

            if (data == null) {
                fail("Missing MediaProjection data");
                return START_NOT_STICKY;
            }

            try {
                MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = mpm.getMediaProjection(resultCode, data);
                if (projection == null) {
                    fail("Could not get MediaProjection");
                    return START_NOT_STICKY;
                }
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        running = false;
                    }
                }, mainHandler);

                AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

                int channelMask = channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
                AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(srate)
                    .setChannelMask(channelMask)
                    .build();

                int minBuf = AudioRecord.getMinBufferSize(srate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) minBuf = 8192;

                final AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    record.release();
                    fail("AudioRecord failed to initialize");
                    return START_NOT_STICKY;
                }

                final File outFile = new File(file != null ? file : getDefaultFilename());
                if (outFile.exists()) {
                    record.release();
                    fail("File already exists: " + outFile.getName());
                    return START_NOT_STICKY;
                }

                final long limitMs = limit > 0 ? limit * 1000L : 0;
                running = true;
                isCapturing = true;

                captureThread = new Thread(() -> {
                    long total = 0;
                    RandomAccessFile raf = null;
                    try {
                        raf = new RandomAccessFile(outFile, "rw");
                        writeWavHeader(raf, 0, srate, channels);
                        byte[] buf = new byte[8192];
                        record.startRecording();
                        long start = System.currentTimeMillis();
                        while (running) {
                            int n = record.read(buf, 0, buf.length);
                            if (n > 0) {
                                raf.write(buf, 0, n);
                                total += n;
                            } else if (n < 0) {
                                break;
                            }
                            if (limitMs > 0 && System.currentTimeMillis() - start >= limitMs) break;
                        }
                    } catch (Throwable t) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Capture error", t);
                    } finally {
                        try { record.stop(); } catch (Throwable ignored) { }
                        try { record.release(); } catch (Throwable ignored) { }
                        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
                        if (raf != null) {
                            try {
                                writeWavHeader(raf, total, srate, channels);
                                raf.close();
                            } catch (IOException e) {
                                Logger.logStackTraceWithMessage(LOG_TAG, "Finalize WAV error", e);
                            }
                        }
                        isCapturing = false;
                        running = false;
                        captureThread = null;
                        final String path = outFile.getAbsolutePath();
                        mainHandler.post(() -> {
                            Toast.makeText(getApplicationContext(), "Audio saved: " + path, Toast.LENGTH_LONG).show();
                            stopForeground(true);
                            stopSelf();
                        });
                    }
                }, "TermuxAudioCapture");
                captureThread.start();

                Toast.makeText(this, "Audio capture started: " + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Capture start error", t);
                isCapturing = false;
                fail("Capture start error: " + t.getMessage());
            }

            return START_NOT_STICKY;
        }

        private void fail(String message) {
            Logger.logError(LOG_TAG, message);
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
            try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
            stopForeground(true);
            stopSelf();
        }

        private void startForegroundCompat() {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        }

        private Notification buildNotification() {
            Intent stopIntent = new Intent(this, CaptureActivity.class);
            stopIntent.putExtra("cmd", CMD_STOP);
            stopIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Audio capture", NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            return b.setContentTitle("Termux:API audio capture")
                .setContentText("Recording device audio. Tap to stop.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        }

        private static String getDefaultFilename() {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
            return Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/TermuxAudioCapture_" + df.format(new Date()) + ".wav";
        }

        private static void writeWavHeader(RandomAccessFile raf, long dataLen, int srate, int channels)
                throws IOException {
            long byteRate = (long) srate * channels * 2;
            ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
            bb.putInt((int) (36 + dataLen));
            bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
            bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
            bb.putInt(16);
            bb.putShort((short) 1);
            bb.putShort((short) channels);
            bb.putInt(srate);
            bb.putInt((int) byteRate);
            bb.putShort((short) (channels * 2));
            bb.putShort((short) 16);
            bb.put("data".getBytes(StandardCharsets.US_ASCII));
            bb.putInt((int) dataLen);
            raf.seek(0);
            raf.write(bb.array());
        }

        @Override
        public void onDestroy() {
            running = false;
            super.onDestroy();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}