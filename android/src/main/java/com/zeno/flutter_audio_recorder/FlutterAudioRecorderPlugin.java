package com.zeno.flutter_audio_recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

/** FlutterAudioRecorderPlugin */
public class FlutterAudioRecorderPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler,
        ActivityAware, PluginRegistry.RequestPermissionsResultListener {

  private static final String LOG_NAME = "AndroidAudioRecorder";
  private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 200;
  private static final byte RECORDER_BPP = 16;

  private MethodChannel channel;
  private Context context;
  private Activity activity;
  private int mSampleRate = 16000;
  private AudioRecord mRecorder = null;
  private String mFilePath;
  private String mExtension;
  private int bufferSize = 1024;
  private FileOutputStream mFileOutputStream = null;
  private String mStatus = "unset";
  private double mPeakPower = -120;
  private double mAveragePower = -120;
  private Thread mRecordingThread = null;
  private long mDataSize = 0;
  private MethodChannel.Result _result;

  @Override
  public void onAttachedToEngine(FlutterPluginBinding binding) {
    context = binding.getApplicationContext();
    channel = new MethodChannel(binding.getBinaryMessenger(), "flutter_audio_recorder");
    channel.setMethodCallHandler(this);
  }

  @Override
  public void onDetachedFromEngine(FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
  }

  @Override
  public void onAttachedToActivity(ActivityPluginBinding binding) {
    activity = binding.getActivity();
    binding.addRequestPermissionsResultListener(this);
  }

  @Override
  public void onDetachedFromActivity() {
    activity = null;
  }

  @Override
  public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  @Override
  public void onMethodCall(MethodCall call, MethodChannel.Result result) {
    _result = result;
    switch (call.method) {
      case "hasPermissions":
        handleHasPermission();
        break;
      case "init":
        handleInit(call, result);
        break;
      case "current":
        handleCurrent(result);
        break;
      case "start":
        handleStart(result);
        break;
      case "pause":
        handlePause(result);
        break;
      case "resume":
        handleResume(result);
        break;
      case "stop":
        handleStop(result);
        break;
      default:
        result.notImplemented();
    }
  }

  private boolean hasRecordPermission() {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
  }

  private void handleHasPermission() {
    if (hasRecordPermission()) {
      Log.d(LOG_NAME, "Permission granted");
      _result.success(true);
    } else {
      ActivityCompat.requestPermissions(activity,
              new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE},
              PERMISSIONS_REQUEST_RECORD_AUDIO);
    }
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
      boolean granted = true;
      for (int result : grantResults) {
        if (result != PackageManager.PERMISSION_GRANTED) {
          granted = false;
        }
      }
      if (_result != null) {
        _result.success(granted);
      }
      return true;
    }
    return false;
  }

  private void handleInit(MethodCall call, MethodChannel.Result result) {
    resetRecorder();
    mSampleRate = call.argument("sampleRate");
    mFilePath = call.argument("path");
    mExtension = call.argument("extension");
    bufferSize = AudioRecord.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    mStatus = "initialized";

    HashMap<String, Object> initResult = new HashMap<>();
    initResult.put("duration", 0);
    initResult.put("path", mFilePath);
    initResult.put("audioFormat", mExtension);
    initResult.put("peakPower", mPeakPower);
    initResult.put("averagePower", mAveragePower);
    initResult.put("isMeteringEnabled", true);
    initResult.put("status", mStatus);
    result.success(initResult);
  }

  private void handleCurrent(MethodChannel.Result result) {
    HashMap<String, Object> currentResult = new HashMap<>();
    currentResult.put("duration", getDuration() * 1000);
    currentResult.put("path", mStatus.equals("stopped") ? mFilePath : getTempFilename());
    currentResult.put("audioFormat", mExtension);
    currentResult.put("peakPower", mPeakPower);
    currentResult.put("averagePower", mAveragePower);
    currentResult.put("isMeteringEnabled", true);
    currentResult.put("status", mStatus);
    result.success(currentResult);
  }

  private void handleStart(MethodChannel.Result result) {
    mRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, mSampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

    try {
      mFileOutputStream = new FileOutputStream(getTempFilename());
    } catch (FileNotFoundException e) {
      result.error("file_error", "Cannot open temp file", null);
      return;
    }

    mRecorder.startRecording();
    mStatus = "recording";
    startThread();
    result.success(null);
  }

  private void startThread() {
    mRecordingThread = new Thread(this::processAudioStream, "AudioProcessingThread");
    mRecordingThread.start();
  }

  private void handlePause(MethodChannel.Result result) {
    mStatus = "paused";
    mPeakPower = -120;
    mAveragePower = -120;
    mRecorder.stop();
    mRecordingThread = null;
    result.success(null);
  }

  private void handleResume(MethodChannel.Result result) {
    mStatus = "recording";
    mRecorder.startRecording();
    startThread();
    result.success(null);
  }

  private void handleStop(MethodChannel.Result result) {
    if (mStatus.equals("stopped")) {
      result.success(null);
      return;
    }

    mStatus = "stopped";
    mRecorder.stop();
    mRecorder.release();

    try {
      mFileOutputStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    }

    copyWaveFile(getTempFilename(), mFilePath);
    deleteTempFile();

    HashMap<String, Object> stopResult = new HashMap<>();
    stopResult.put("duration", getDuration() * 1000);
    stopResult.put("path", mFilePath);
    stopResult.put("audioFormat", mExtension);
    stopResult.put("peakPower", mPeakPower);
    stopResult.put("averagePower", mAveragePower);
    stopResult.put("isMeteringEnabled", true);
    stopResult.put("status", mStatus);
    result.success(stopResult);
  }

  private void processAudioStream() {
    byte[] buffer = new byte[bufferSize];
    while (mStatus.equals("recording")) {
      mRecorder.read(buffer, 0, buffer.length);
      mDataSize += buffer.length;
      updatePowers(buffer);
      try {
        mFileOutputStream.write(buffer);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private void updatePowers(byte[] bdata) {
    short[] data = byte2short(bdata);
    if (data.length == 0 || mStatus.equals("paused") || mStatus.equals("stopped")) {
      mAveragePower = -120;
    } else {
      double factor = 0.25;
      mAveragePower = 20 * Math.log10(Math.abs(data[data.length - 1]) / 32768.0) * factor;
    }
    mPeakPower = mAveragePower;
  }

  private void deleteTempFile() {
    File file = new File(getTempFilename());
    if (file.exists()) file.delete();
  }

  private String getTempFilename() {
    return mFilePath + ".temp";
  }

  private short[] byte2short(byte[] bData) {
    short[] out = new short[bData.length / 2];
    ByteBuffer.wrap(bData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
    return out;
  }

  private void resetRecorder() {
    mPeakPower = -120;
    mAveragePower = -120;
    mDataSize = 0;
  }

  private int getDuration() {
    return (int) (mDataSize / (mSampleRate * 2));
  }

  private void copyWaveFile(String inFilename, String outFilename) {
    try (FileInputStream in = new FileInputStream(inFilename);
         FileOutputStream out = new FileOutputStream(outFilename)) {
      long totalAudioLen = in.getChannel().size();
      long byteRate = RECORDER_BPP * mSampleRate * 1 / 8;
      long totalDataLen = totalAudioLen + 36;

      writeWaveHeader(out, totalAudioLen, totalDataLen, mSampleRate, 1, byteRate);

      byte[] data = new byte[bufferSize];
      int bytesRead;
      while ((bytesRead = in.read(data)) != -1) {
        out.write(data, 0, bytesRead);
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void writeWaveHeader(FileOutputStream out, long totalAudioLen,
                               long totalDataLen, int sampleRate, int channels, long byteRate) throws IOException {
    byte[] header = new byte[44];

    header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
    header[4] = (byte) (totalDataLen & 0xff);
    header[5] = (byte) ((totalDataLen >> 8) & 0xff);
    header[6] = (byte) ((totalDataLen >> 16) & 0xff);
    header[7] = (byte) ((totalDataLen >> 24) & 0xff);
    header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
    header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
    header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
    header[20] = 1; header[21] = 0;
    header[22] = (byte) channels; header[23] = 0;
    header[24] = (byte) (sampleRate & 0xff);
    header[25] = (byte) ((sampleRate >> 8) & 0xff);
    header[26] = (byte) ((sampleRate >> 16) & 0xff);
    header[27] = (byte) ((sampleRate >> 24) & 0xff);
    header[28] = (byte) (byteRate & 0xff);
    header[29] = (byte) ((byteRate >> 8) & 0xff);
    header[30] = (byte) ((byteRate >> 16) & 0xff);
    header[31] = (byte) ((byteRate >> 24) & 0xff);
    header[32] = 1; header[33] = 0;
    header[34] = RECORDER_BPP; header[35] = 0;
    header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
    header[40] = (byte) (totalAudioLen & 0xff);
    header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
    header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
    header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

    out.write(header, 0, 44);
  }
}
