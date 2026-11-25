package com.limelight.screensaver;

import android.service.dreams.DreamService;

/**
 * TunaReceive's screensaver: the covers of your games drifting slowly across the screen, with the
 * time. Pick it under the TV's Settings > Device Preferences > Screen saver.
 */
public class CoverDream extends DreamService {
    private CoverDriftView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        view = new CoverDriftView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }
}
