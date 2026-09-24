package com.aegis.dialer.telecom

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.CallAudioState

object CallSession {
    @Volatile
    var activeCall: Call? = null
        private set

    @Volatile
    var state: Int = Call.STATE_DISCONNECTED
        private set

    @Volatile
    var number: String = "Unknown"
        private set

    @Volatile
    var service: InCallService? = null

    fun attach(call: Call) {
        activeCall = call
        state = call.state
        number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
    }

    fun update(call: Call, newState: Int) {
        if (activeCall === call) state = newState
    }

    fun clear(call: Call) {
        if (activeCall === call) {
            activeCall = null
            state = Call.STATE_DISCONNECTED
            number = "Unknown"
        }
    }

    fun answer() {
        activeCall?.answer(0)
    }

    fun reject() {
        activeCall?.reject(false, null)
    }

    fun disconnect() {
        activeCall?.disconnect()
    }

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }

    fun setSpeaker(enabled: Boolean) {
        service?.setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    fun hold(held: Boolean) {
        activeCall?.let { call -> if (held) call.hold() else call.unhold() }
    }
}
