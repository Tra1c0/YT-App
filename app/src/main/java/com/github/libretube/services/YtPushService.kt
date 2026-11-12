package com.github.libretube.services

import com.github.libretube.util.YtPush
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * YT: receives the UnifiedPush events of the update push from the connector library (which gets
 * them from the distributor, e.g. ntfy) and hands them to [YtPush]. Not exported: only the
 * connector inside YT binds to it. Events of any other registration are ignored.
 */
class YtPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (instance == YtPush.INSTANCE) YtPush.onNewEndpoint(this, endpoint.url, endpoint.temporary)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // encrypted (Web Push) or plain: both are only a trigger, the check decides
        if (instance == YtPush.INSTANCE) YtPush.onMessage(this, message.content)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        if (instance == YtPush.INSTANCE) YtPush.onRegistrationFailed(this, reason.name)
    }

    override fun onUnregistered(instance: String) {
        if (instance == YtPush.INSTANCE) YtPush.onUnregistered(this)
    }
}
