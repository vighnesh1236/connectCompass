package com.vighnesh1236.campusconnect

import android.app.Application
import com.vighnesh1236.campusconnect.data.SessionStore

class CampusConnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionStore.initialize(this)
        CampusNotifications.initialize(this)
    }
}
