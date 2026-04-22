package com.bossscraper.app;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class App extends Application {

    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        setupCrashHandler();
    }

    private void setupCrashHandler() {
        Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                // Write crash log to internal storage
                File crashFile = new File(getFilesDir(), "crash.txt");
                FileWriter fw = new FileWriter(crashFile, false);
                PrintWriter pw = new PrintWriter(fw);
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.CHINA).format(new Date());
                pw.println("=== CRASH at " + time + " ===");
                pw.println("Thread: " + thread.getName());
                pw.println();
                throwable.printStackTrace(pw);
                // Also print cause chain
                Throwable cause = throwable.getCause();
                while (cause != null) {
                    pw.println("\nCaused by:");
                    cause.printStackTrace(pw);
                    cause = cause.getCause();
                }
                pw.flush();
                pw.close();
                Log.e(TAG, "Crash written to " + crashFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to write crash log: " + e);
            }

            // Call the default handler so the system shows the crash dialog
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }
}
