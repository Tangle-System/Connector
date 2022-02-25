package com.tangle.connector;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;

import com.tangle.connector.activities.ActivityControl;

/**
 *This function will start AndroidController.
 *With parameters you can set some functionality of connector.
 */
public class AndroidConnector {

    Context context;
    String homeWebUrl = "https://apps-tangle.netlify.app/";
    String updaterUrl;
    boolean disableBackButton = false;
    boolean fullScreenMode = false;
    int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    /**
     * @param context A Context of the application package implementing this class
     */
    public AndroidConnector(Context context){
        this.context = context;
    }

    /**
     * Start Tangle android connector for communicate with tangle devices trough websites.
     */
    public void start(){
        Intent intent = new Intent(context, ActivityControl.class);
        intent.putExtra("defaultWebUrl", homeWebUrl);
        intent.putExtra("updaterUrl", updaterUrl);
        intent.putExtra("disableBackButton", disableBackButton);
        intent.putExtra("fullScreenMode", fullScreenMode);
        intent.putExtra("screenOrientation", screenOrientation);
        context.startActivity(intent);
    }
    /**
     * Defining home web for webView. To this url you will be send on home button click.
     * Home site is for default set tot: https://apps-tangle.netlify.app/.
     * @param homeWebUrl Url of home website.
     */
    public void setHomeWebUrl(String homeWebUrl) {
        this.homeWebUrl = homeWebUrl;
    }

    /**
     * Defining url for appUpdater for checking new versions of application.
     * Updater listen for JSON from website with parameters about app version.
     * With default setting application doesn't use appUpdater.
     * @param updaterUrl Url of website with updater JSON.
     */
    public void setUpdaterUrl(String updaterUrl) {
        this.updaterUrl = updaterUrl;
    }

    /**
     * When disableBackButton is set to true then function of system back button will be disable.
     * Default value is false.
     * @param disableBackButton System back button setting.
     */
    public void setDisableBackButton(boolean disableBackButton) {
        this.disableBackButton = disableBackButton;
    }

    /**
     * When fullScreenMode is set to true the Activity will be show as fullscreen.
     * Default value is false.
     * @param fullScreenMode Fullscreen mode.
     */
    public void setFullScreenMode(boolean fullScreenMode) {
        this.fullScreenMode = fullScreenMode;
    }

    /**
     * There you can set prefered screen orientation with {@link ActivityInfo} constants.
     * @param screenOrientation Constant from {@link ActivityInfo}.
     */
    public void setScreenOrientation(int screenOrientation) {
        this.screenOrientation = screenOrientation;
    }
}
