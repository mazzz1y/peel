package wtf.mazy.peel.push

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class PeelPushService : PushService() {

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return
        PushBridge.onMessage(applicationContext, message.content, instance)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        PushBridge.onNewEndpoint(applicationContext, endpoint, instance)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        PushBridge.onRegistrationFailed(instance)
    }

    override fun onUnregistered(instance: String) {
        PushBridge.onUnregistered(this, instance)
    }
}
