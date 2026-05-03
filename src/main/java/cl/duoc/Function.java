package cl.duoc;

import java.util.Map;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.BinaryData;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.EventGridPublisherClient;
import com.azure.messaging.eventgrid.EventGridPublisherClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

public class Function {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_EVENT_TYPE = "Biblioteca.GenericEvent";
    private static final String DEFAULT_SUBJECT = "biblioteca/generic";
    private static final String DATA_VERSION = "1.0";

    @FunctionName("eventPublisher")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {
                HttpMethod.POST}, authLevel = AuthorizationLevel.FUNCTION) HttpRequestMessage<String> request,
            final ExecutionContext context) {

        String eventGridTopicEndpoint = System.getenv("EVENT_GRID_TOPIC_ENDPOINT");
        String eventGridTopicKey = System.getenv("EVENT_GRID_TOPIC_KEY");

        if (eventGridTopicEndpoint == null || eventGridTopicEndpoint.isBlank()
                || eventGridTopicKey == null || eventGridTopicKey.isBlank()) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "EVENT_GRID_TOPIC_ENDPOINT y EVENT_GRID_TOPIC_KEY son obligatorios"))
                    .build();
        }

        try {
            EventPayload payload = parsePayload(request.getBody());

            EventGridPublisherClient<EventGridEvent> client = new EventGridPublisherClientBuilder()
                    .endpoint(eventGridTopicEndpoint)
                    .credential(new AzureKeyCredential(eventGridTopicKey))
                    .buildEventGridEventPublisherClient();

            EventGridEvent event = new EventGridEvent(
                    payload.subject,
                    payload.eventType,
                    BinaryData.fromObject(payload.data),
                    DATA_VERSION);

            client.sendEvent(event);

            context.getLogger().info(String.format(
                    "Evento publicado: eventType=%s subject=%s",
                    payload.eventType, payload.subject));

            return request.createResponseBuilder(HttpStatus.OK)
                    .body(Map.of(
                            "mensaje", "Evento publicado correctamente",
                            "eventType", payload.eventType,
                            "subject", payload.subject))
                    .build();
        } catch (Exception e) {
            context.getLogger().severe("Error al publicar evento: " + e.getMessage());

            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al publicar evento", "detalle", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    private EventPayload parsePayload(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return new EventPayload(DEFAULT_EVENT_TYPE, DEFAULT_SUBJECT, Map.of("mensaje", "evento sin contenido"));
        }

        Map<String, Object> json = OBJECT_MAPPER.readValue(body, Map.class);

        String eventType = stringOrDefault(json.get("eventType"), DEFAULT_EVENT_TYPE);
        String subject = stringOrDefault(json.get("subject"), DEFAULT_SUBJECT);
        Object data = json.getOrDefault("data", json);

        return new EventPayload(eventType, subject, data);
    }

    private String stringOrDefault(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private record EventPayload(String eventType, String subject, Object data) {
    }
}
