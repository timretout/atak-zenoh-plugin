package com.atakmap.android.zenoh.plugin

import io.zenoh.Config
import io.zenoh.Session
import io.zenoh.Zenoh
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.keyexpr.intoKeyExpr
import io.zenoh.pubsub.Subscriber

/**
 * Java-friendly SAM callback for inbound mesh samples. Raw bytes, not a
 * decoded String: a sample's payload may be TAK Protocol (protobuf), which
 * isn't valid UTF-8 in general, so any text decoding has to happen only
 * after the format is known -- see CotBridgeService. A plain `fun interface`
 * (rather than Kotlin's `Function1`) so Java call sites can pass a lambda
 * without touching any Kotlin-specific types.
 */
fun interface ZenohSampleListener {
    fun onSampleReceived(payload: ByteArray)
}

/**
 * Thin wrapper around the zenoh-kotlin Session/Publisher/Subscriber API.
 *
 * The upstream API returns `kotlin.Result<T>` from name-mangled methods
 * that are effectively uncallable from Java, so every Zenoh call is
 * isolated here and this class only ever hands the Java side of the
 * plugin plain booleans/exceptions/strings.
 */
class ZenohBridge {

    private var session: Session? = null
    private val subscribers = mutableListOf<Subscriber<Unit>>()

    /**
     * Opens a client-mode session from the given JSON5 config and declares a
     * subscriber for each topic, routing payloads to [listener].
     *
     * Outbound publishing has no fixed key to declare up front -- each CoT
     * event publishes under its own per-entity key (see [publish]), per the
     * fabric's Pattern-B guidance (docs/zenoh-publish-key-design.md) -- so
     * there is no publisher declared here.
     *
     * Any failure leaves the bridge fully stopped (no partial state) and is
     * rethrown to the caller.
     */
    @Synchronized
    @Throws(Exception::class)
    fun start(
        configJson5: String,
        subscribeTopics: List<String>,
        listener: ZenohSampleListener
    ) {
        stop()
        try {
            val config = Config.fromJson5(configJson5).getOrThrow()
            val newSession = Zenoh.open(config).getOrThrow()
            session = newSession

            for (topic in subscribeTopics) {
                val keyExpr: KeyExpr = topic.intoKeyExpr().getOrThrow()
                val subscriber = newSession.declareSubscriber(keyExpr, callback = { sample ->
                    listener.onSampleReceived(sample.payload.toBytes())
                }).getOrThrow()
                subscribers.add(subscriber)
            }
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    /**
     * Publishes [xml] to [keyExpr] (the caller's per-entity key -- see
     * CotBridgeService.buildPublishKey). No-op if not started.
     */
    @Synchronized
    @Throws(Exception::class)
    fun publish(keyExpr: String, xml: String) {
        val session = this.session ?: return
        session.put(keyExpr.intoKeyExpr().getOrThrow(), xml).getOrThrow()
    }

    @Synchronized
    fun isRunning(): Boolean = session != null

    /** Closes all subscribers and the session, in that order. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        for (subscriber in subscribers) {
            runCatching { subscriber.close() }
        }
        subscribers.clear()
        runCatching { session?.close() }
        session = null
    }
}
