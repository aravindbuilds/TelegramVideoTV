package com.tgtv.videotv

import android.app.Application
import android.util.Log
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdLib

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!Credentials.hasApi(this)) return
        // Never hard-crash on TDLib native init: the UI must still open so the
        // user can re-configure or re-authenticate.
        try {
            TdLib.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "TDLib init failed", t)
        }
    }

    private companion object {
        const val TAG = "TgVideoTvApp"
    }
}
