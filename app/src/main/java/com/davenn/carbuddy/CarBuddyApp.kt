package com.davenn.carbuddy

import android.app.Application

class CarBuddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
