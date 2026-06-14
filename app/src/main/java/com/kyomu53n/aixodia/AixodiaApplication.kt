package com.kyomu53n.aixodia

import android.app.Application
import com.kyomu53n.aixodia.ShizukuCommandExecutor
import com.kyomu53n.aixodia.memory.AppMemory
import com.kyomu53n.aixodia.vision.ScreenEyes

class AixodiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppMemory.init(this)
        ScreenEyes.init(this)
        ShizukuCommandExecutor.setup(this)
    }
}