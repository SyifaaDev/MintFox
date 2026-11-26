package com.mintfox.app;

import android.app.Application;

public class MintFoxApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (def != null) def.uncaughtException(t, e);
        });
    }
}
