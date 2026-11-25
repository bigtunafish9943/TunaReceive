package com.limelight.binding.input.driver;

public interface UsbDriverListener {
    void reportControllerState(int controllerId, int buttonFlags,
                               float leftStickX, float leftStickY,
                               float rightStickX, float rightStickY,
                               float leftTrigger, float rightTrigger);
    void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ);

    /** Battery of a controller TunaReceive drives itself, as MoonBridge.LI_BATTERY_STATE_* and percent. */
    default void reportControllerBattery(int controllerId, byte state, byte percent) {
    }

    void deviceRemoved(AbstractController controller);
    void deviceAdded(AbstractController controller);
}
