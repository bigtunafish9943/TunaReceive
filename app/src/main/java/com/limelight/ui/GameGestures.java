package com.limelight.ui;

import com.limelight.binding.input.GameInputDevice;

public interface GameGestures {
    void toggleKeyboard();

    default void showGameMenu(GameInputDevice device){};

    /** View + R1: save a screenshot of the stream. */
    default void takeScreenshot() {}

    /** View + L1: save the last 30 seconds on the PC. */
    default void saveReplay() {}
}
