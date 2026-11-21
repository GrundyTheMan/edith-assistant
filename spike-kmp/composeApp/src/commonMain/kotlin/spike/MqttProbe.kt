package spike

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

private const val BROKER_PORT = 8883

/**
 * K0.6b: MQTT 3.1.1 over TLS (the way Meross's cloud is spoken to), from the phone to a public test broker: connect with the platform's own
 * trust store, subscribe to a private topic, publish one message, and receive it. No credentials are involved: Meross's go in a later step.
 */
suspend fun mqttProbe(): String {
    val brokers = listOf("broker.hivemq.com", "mqtt.eclipseprojects.io")
    val failures = mutableListOf<String>()
    for (host in brokers) {
        val result = try {
            withTimeoutOrNull(25_000) { roundTrip(host) } ?: "timed out"
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message}"
        }
        if (result.startsWith("received")) return "MQTT over TLS ($host:$BROKER_PORT): $result"
        failures += "$host: $result"
    }
    return "MQTT over TLS failed: " + failures.joinToString("; ")
}

/** The client keeps running until its scope ends, so each attempt gets a scope of its own and it is cancelled afterwards. */
private suspend fun roundTrip(host: String): String {
    val clientScope = CoroutineScope(SupervisorJob() as Job + kotlinx.coroutines.Dispatchers.Default)
    try {
        return roundTrip(clientScope, host)
    } finally {
        clientScope.cancel()
    }
}

private suspend fun roundTrip(scope: CoroutineScope, host: String): String {
    val topic = "edith-spike/${Random.nextLong().toString(16)}/probe"
    val request = ConnectionRequest(clientId = "edith-spike-${Random.nextInt(100000)}", keepAliveSeconds = 30, cleanSession = true)
    val endpoint = MqttEndpoint(host, BROKER_PORT, config = TransportConfig.tlsDefault())
    val persistence = request.controlPacketFactory.defaultPersistence(inMemory = true)
    val broker = persistence.addBroker(request)
    val client = MqttClient.start(scope = scope, broker = broker, persistence = persistence, endpoint = endpoint)
    client.awaitConnectivity()
    val messages = client.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
    val first = scope.launch { messages.first() }
    client.subscribe(topic, OpaquePublishPayloadCodec, QualityOfService.AT_LEAST_ONCE).subAck.await()
    client.publish(topicName = topic, qos = QualityOfService.AT_LEAST_ONCE, payload = "hello".toReadBuffer(Charset.UTF8))
    first.join()
    return "received my own message"
}
