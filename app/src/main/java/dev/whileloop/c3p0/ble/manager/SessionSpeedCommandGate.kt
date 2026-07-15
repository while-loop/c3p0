package dev.whileloop.c3p0.ble.manager

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionSpeedCommandGate @Inject constructor() {
    private val allowed = AtomicBoolean(false)

    fun allow() {
        allowed.set(true)
    }

    fun block() {
        allowed.set(false)
    }

    fun isAllowed(): Boolean = allowed.get()
}
