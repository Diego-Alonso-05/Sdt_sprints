package common;

import org.eclipse.paho.client.mqttv3.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * MessageBus
 * ----------
 * Replaces the old IPFS PubSub logic entirely.
 *
 * Provides:
 *    - publish(topic, json)
 *    - subscribe(topic, handler)
 *
 * Transport: MQTT (Mosquitto broker)
 * Connection: tcp://127.0.0.1:1883
 *
 * Used by:
 *   - Leader (TCPServer)
 *   - Peers (TCPClient)
 *   - RAFT services
 *   - Heartbeat services
 */
public class MessageBus {

    /** MQTT broker endpoint */
    private static final String BROKER_URL = "tcp://127.0.0.1:1883";

    /** Shared client instance */
    private static MqttClient client;

    /**
     * Ensures the MQTT client is initialized and connected.
     */
    private static synchronized MqttClient getClient() throws MqttException {

        if (client != null && client.isConnected()) {
            return client;
        }

        String clientId = "sdt-client-" + UUID.randomUUID();
        client = new MqttClient(BROKER_URL, clientId);

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                AppLog.log("[MessageBus] Connection lost: " +
                        (cause == null ? "unknown" : cause.getMessage()));
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // Not used here — listeners defined per-subscription.
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Acknowledged.
            }
        });

        client.connect(opts);

        AppLog.log("[MessageBus] Connected to broker " + BROKER_URL +
                " with clientId=" + clientId);

        return client;
    }

    // --------------------------------------------------------------------
    // Publish a JSON message
    // --------------------------------------------------------------------
    public static void publish(String topic, String json) {
        try {
            MqttMessage msg = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            msg.setQos(1);  // At-least-once
            msg.setRetained(false);

            getClient().publish(topic, msg);

            AppLog.log("[MessageBus] PUBLISH topic=" + topic + " | json=" + json);

        } catch (Exception e) {
            AppLog.log("[MessageBus] ERROR publishing to " + topic + ": " + e.getMessage());
        }
    }

    // --------------------------------------------------------------------
    // Subscribe to JSON messages
    // --------------------------------------------------------------------
    public static void subscribe(String topic, Consumer<String> handler) {

        new Thread(() -> {

            while (true) {

                try {
                    getClient().subscribe(topic, (t, message) -> {
                        String json = new String(message.getPayload(), StandardCharsets.UTF_8);
                        handler.accept(json);
                    });

                    AppLog.log("[MessageBus] SUBSCRIBED to topic: " + topic);
                    return;

                } catch (Exception e) {

                    AppLog.log("[MessageBus] Failed subscribing to " + topic +
                            ". Retrying in 1500ms. Error: " + e.getMessage());

                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {}
                }
            }

        }, "msgbus-sub-" + topic).start();
    }
}
