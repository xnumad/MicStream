package com.micstream;


import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MainService extends Service {
    private final static String TAG = "MainService";

    // This is the object that receives interactions from clients.
    private final IBinder mBinder = new LocalBinder();
    // Objects used for streaming thread
    private AtomicBoolean run = new AtomicBoolean(false);
    private AtomicInteger dataBytes = new AtomicInteger(0);
    private AtomicLong dataBytesResetTime = new AtomicLong(0);
    private Thread thread;
    // the audio recording options
    // Rates to be tested in increasing order, max possible will be used
    private static final int[] RECORDING_RATES = {8000, 11025, 16000, 22050, 32000, 44100};//, 48000, 96000};
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    public static final int PAYLOAD_TYPE_16BIT = 127;
    public static final int PAYLOAD_TYPE_8BIT = 126;
    // the audio recorder
    private AudioRecord recorder;
    // Streaming objects
    String streamDestinationIP;
    int streamDestinationPort;
    int sampleByteSize;
    private static final int PAYLOAD_SIZE = 1000;
    SharedPreferences sharedPreferences;

    public String getStreamDestinationIP() {
        return streamDestinationIP;
    }

    public void setStreamDestinationIP(String streamDestinationIP) {
        this.streamDestinationIP = streamDestinationIP;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(getString(R.string.streamDestinationIP_key), streamDestinationIP);
        editor.apply();
    }

    public int getStreamDestinationPort() {
        return streamDestinationPort;
    }

    public void setStreamDestinationPort(int streamDestinationPort) {
        this.streamDestinationPort = streamDestinationPort;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(getString(R.string.streamDestinationPort_key), streamDestinationPort);
        editor.apply();
    }

    public int getSampleByteSize() { return sampleByteSize; }

    public void setSampleByteSize(int sampleByteSize) {
        this.sampleByteSize = sampleByteSize;
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(getString(R.string.sampleByteSize_key), sampleByteSize);
        editor.apply();
    }

    /**
     *  Compute an estimate of the datarate sent over the network based on
     *  number of bytes sent and time elapsed since last call to this function
     * @return estimated datarate
     */
    public int getCurrentDataRate(){
        int ret = dataBytes.get();
        long time = System.currentTimeMillis();
        dataBytes.set(0);
        int deltaTime =(int)(time - dataBytesResetTime.get());
        dataBytesResetTime.set(time);
        return (1000*ret)/deltaTime;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Create");
        Toast.makeText(this, "Create service", Toast.LENGTH_LONG).show();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        streamDestinationIP = sharedPreferences.getString(getString(R.string.streamDestinationIP_key), "51.15.37.41"); // "192.168.0.252"
        streamDestinationPort = sharedPreferences.getInt(getString(R.string.streamDestinationPort_key), 2222);
        sampleByteSize = sharedPreferences.getInt(getString(R.string.sampleByteSize_key), 2); // Default 16 bits
        this.startStreaming();
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId){
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "Start");
        Notification notification = new NotificationCompat.Builder(this, TAG)
                .setContentTitle("Foreground Service")
                .setContentText("Content text")
                .build();
        startForeground(1, notification);
        if(recorder != null)
            if (!isStreaming())
                startStreaming();
        return Service.START_STICKY;
    }

    public void startStreaming(){
        thread = new Thread(new Runnable() {
            // Surcharge de la méthode run
            public void run() {
                WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiManager.WifiLock wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF  , TAG + ":wifiLock");
                wifiLock.setReferenceCounted(true);
                wifiLock.acquire();
                PowerManager powerManager = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG + ":wakeLock");
                wakeLock.acquire();
                int bufferSize = 0;
                int rate = 0;
                for (int i=RECORDING_RATES.length-1; i>=0; i--)
                {
                    rate = RECORDING_RATES[i];
                    bufferSize = 0;
                    recorder = null;
                    try {
                        int format = (sampleByteSize==2 ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT);
                        bufferSize = AudioRecord.getMinBufferSize(rate, CHANNEL, format);
                        bufferSize = (1+ bufferSize/PAYLOAD_SIZE)*PAYLOAD_SIZE;
                        recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                                rate, CHANNEL, format, bufferSize * 10);
                        Log.d(TAG, "Created AudioRecord, rate :" + rate + ", bufferSize: " + bufferSize);
                    }
                    catch (Exception e) {
                        continue;
                    }
                    // If we arrive here it means that we have a valid recorder
                    break;
                }
                byte[] buffer = new byte[bufferSize];
                DatagramSocket datagramSocket = null;
                DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                try {
                    datagramSocket = new DatagramSocket();
                    datagramPacket.setAddress(InetAddress.getByName(streamDestinationIP));
                    datagramPacket.setPort(streamDestinationPort);
                    Log.d(TAG, "Created DatagramSocket : " + streamDestinationIP + ":" + streamDestinationPort);
                }
                catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
                if (recorder.getState() == AudioRecord.STATE_INITIALIZED)
                    recorder.startRecording();
                short frameNb = 0;
                int sampleNb = 0;
                int payloadType = (sampleByteSize==2 ? PAYLOAD_TYPE_16BIT : PAYLOAD_TYPE_8BIT);
                dataBytes.set(0);
                dataBytesResetTime.set(System.currentTimeMillis());
                while(run.get() && (recorder.getState() == AudioRecord.STATE_INITIALIZED)
                        && (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)){
                    try {
                        int sizeToSend = recorder.read(buffer, 0, buffer.length);
                        int index = 0;
                        while(sizeToSend>0) {
                            int packetBufferSize = Math.min(sizeToSend, PAYLOAD_SIZE);
                            StreamPacket rtp_packet = new StreamPacket((byte) payloadType,
                                    frameNb++, sampleNb, rate,
                                    Arrays.copyOfRange(buffer, index, index + packetBufferSize),
                                    packetBufferSize);
                            byte[] packetBuffer = new byte[rtp_packet.getPacketLength()];
                            rtp_packet.getPacket(packetBuffer);
                            sizeToSend -= packetBufferSize;
                            index += packetBufferSize;
                            sampleNb += packetBufferSize/sampleByteSize;
                            datagramPacket.setData(packetBuffer);
                            if(datagramSocket != null) {
                                datagramSocket.send(datagramPacket);
                                dataBytes.set(dataBytes.get() + packetBuffer.length);
                            }
                        }
                    } catch (Throwable t) {
                        // gérer l'exception et arrêter le traitement
                    }
                }
                if(datagramSocket != null) {
                    if (!datagramSocket.isClosed())
                        datagramSocket.close();
                }
                if(recorder != null) {
                    if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                        recorder.stop();
                        recorder.release();
                    }
                }
                if(wakeLock.isHeld())
                    wakeLock.release();
                if(wifiLock.isHeld())
                    wifiLock.release();
            }
        });
        run.set(true);
        thread.start();
        Toast.makeText(this, "Streaming started", Toast.LENGTH_LONG).show();
    }

    public boolean isStreaming(){
        if (recorder == null)
            return false;
        return thread.isAlive()
                && (recorder.getState() == AudioRecord.STATE_INITIALIZED)
                && (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING);
    }

    public void stopStreaming(){
        /**/
        run.set(false);
        try {
            thread.join();
        } catch (Throwable t) {
            // gérer l'exception et arrêter le traitement
        }
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroy");
        Toast.makeText(this, "Service Stopped", Toast.LENGTH_LONG).show();
        stopStreaming();
    }

    public class LocalBinder extends Binder {
        MainService getService() {
            Log.d(TAG, "Binder");
            return MainService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

}
