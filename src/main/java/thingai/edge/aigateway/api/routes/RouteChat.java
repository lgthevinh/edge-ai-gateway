package thingai.edge.aigateway.api.routes;

import com.google.gson.JsonObject;
import io.javalin.apibuilder.EndpointGroup;
import thingai.edge.aigateway.utils.JsonUtil;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class RouteChat implements EndpointGroup {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String llamaServerUrl;

    public RouteChat(String llamaServerUrl) {
        this.llamaServerUrl = llamaServerUrl;
    }

    @Override
    public void addEndpoints() {
        path("chat", () -> {
            post(ctx -> {
                String body = ctx.body();
                String auth = ctx.header("Authorization");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(llamaServerUrl + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", auth != null ? auth : "")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                JsonObject parsed = JsonUtil.fromJson(body, JsonObject.class);
                boolean stream = parsed != null && parsed.has("stream")
                        && parsed.get("stream").getAsBoolean();

                if (stream) {
                    ctx.contentType("text/event-stream");
                    HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    ctx.result(response.body());
                } else {
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    ctx.status(response.statusCode()).result(response.body());
                }
            });
            get("/history", ctx -> {
                ctx.json("[]");
            });
        });
    }
}
