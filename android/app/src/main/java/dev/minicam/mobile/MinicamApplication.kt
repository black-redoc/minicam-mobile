package dev.minicam.mobile

import android.app.Application
import dev.minicam.mobile.i18n.I18n
import dev.minicam.mobile.push.FirebasePush

class MinicamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any text is built, also for a push that starts the process (docs/adr/0017).
        I18n.init(this)
        // A push can start the process with no activity: Firebase must exist before the service
        // runs. Auto-init stays off, so this creates no token by itself (docs/adr/0007).
        FirebasePush.initialize(this)
    }
}
