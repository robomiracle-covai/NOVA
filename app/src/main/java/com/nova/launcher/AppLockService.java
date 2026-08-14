package com.nova.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class AppLockService extends AccessibilityService {
    private static final String TAG = "AppLockService";
    private static final String PROTECTED_PACKAGE = "com.android.settings";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED 
            || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            
            CharSequence pkgNameChar = event.getPackageName();
            CharSequence classNameChar = event.getClassName();
            
            String pkg = pkgNameChar != null ? pkgNameChar.toString() : "";
            String cls = classNameChar != null ? classNameChar.toString() : "";
            
            // Debug logging to discover package/class names of system menus
            Log.d(TAG, "Package: " + pkg + " | Class: " + cls + " [Event: " + AccessibilityEvent.eventTypeToString(eventType) + "]");
            
            // Prevent loop: Don't lock if the foreground is the launcher itself
            if (pkg.equals(getPackageName())) return;
            
            // Secure Detection Logic
            boolean isSettings = 
                    pkg.contains("settings") || 
                    cls.contains("Settings") || 
                    cls.contains("Wifi") || 
                    cls.contains("SubSettings");
            
            if (isSettings && !MainActivity.isAuthorized()) {
                Log.d(TAG, "Blocked Activity: " + pkg + " | " + cls);
                
                // Launch MainActivity with lock screen intent
                Intent lockIntent = new Intent(this, MainActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                lockIntent.putExtra("TRIGGER_LOCK", true);
                startActivity(lockIntent);
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service Interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Service Connected");
    }
}
