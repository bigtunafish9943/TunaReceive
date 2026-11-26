package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        com.limelight.utils.TunaTheme.install(this);
        // Controllers TunaReceive drives itself work in its menus too, not only in a game
        com.limelight.binding.input.driver.MenuControllerBridge.install(this);
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }
    }
}