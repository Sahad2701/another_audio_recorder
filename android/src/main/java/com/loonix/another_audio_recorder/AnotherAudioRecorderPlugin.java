package com.loonix.another_audio_recorder;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodChannel;

/**
 * AnotherAudioRecorderPlugin
 */
public class AnotherAudioRecorderPlugin implements FlutterPlugin, ActivityAware {

    private MethodChannel methodChannel;
    private MethodCallHandlerImpl methodCallHandler;
    private Activity activity;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        initialize(binding.getApplicationContext(), binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        destroy();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        startListeningToActivity(activity, binding::addRequestPermissionsResultListener);
    }

    @Override
    public void onDetachedFromActivity() {
        stopListeningToActivity();
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        startListeningToActivity(activity, binding::addRequestPermissionsResultListener);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        stopListeningToActivity();
        activity = null;
    }

    private void initialize(Context applicationContext, BinaryMessenger messenger) {
        methodChannel = new MethodChannel(messenger, "another_audio_recorder");
        methodCallHandler = new MethodCallHandlerImpl();
        methodChannel.setMethodCallHandler(methodCallHandler);
    }

    private void destroy() {
        if (methodChannel != null) {
            methodChannel.setMethodCallHandler(null);
            methodChannel = null;
        }
        methodCallHandler = null;
    }

    private void startListeningToActivity(Activity activity, PermissionRegistry.AddPermissionResultListener permissionRegistry) {
        if (methodCallHandler != null && activity != null) {
            methodCallHandler.setActivity(activity);
            methodCallHandler.addPermissionResultListener(permissionRegistry);
        }
    }

    private void stopListeningToActivity() {
        if (methodCallHandler != null) {
            methodCallHandler.setActivity(null);
        }
    }
}
