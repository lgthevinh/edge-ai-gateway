package thingai.edge.aigateway.agent.tools;

import com.google.gson.JsonObject;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class CurlApiTool implements IAgentTool {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public String getName() { return "curl_api"; }

    @Override
    public String getDescription() { return "Make an HTTP request to a URL and return the response"; }

    @Override
    public String getParametersJson() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\",\"description\":\"The URL to request\"},"
                + "\"method\":{\"type\":\"string\",\"description\":\"HTTP method (GET, POST, PUT, DELETE). Defaults to GET\"},"
                + "\"headers\":{\"type\":\"object\",\"description\":\"Optional HTTP headers as key-value pairs\"},"
                + "\"body\":{\"type\":\"string\",\"description\":\"Optional request body for POST/PUT\"}"
                + "},\"required\":[\"url\"]}";
    }

    @Override
    public String execute(String paramsJson) {
        JsonObject params = JsonUtil.fromJson(paramsJson, JsonObject.class);
        String url = params.get("url").getAsString();
        String method = params.has("method") ? params.get("method").getAsString().toUpperCase() : "GET";
        String body = params.has("body") && !params.get("body").isJsonNull() ? params.get("body").getAsString() : null;

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            if (params.has("headers") && params.get("headers").isJsonObject()) {
                JsonObject headers = params.getAsJsonObject("headers");
                for (String key : headers.keySet()) {
                    reqBuilder.header(key, headers.get(key).getAsString());
                }
            }

            switch (method) {
                case "POST":
                    reqBuilder.POST(body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
                    break;
                case "PUT":
                    reqBuilder.PUT(body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
                    break;
                case "DELETE":
                    reqBuilder.DELETE();
                    break;
                default:
                    reqBuilder.GET();
                    break;
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject result = new JsonObject();
            result.addProperty("status", response.statusCode());
            result.addProperty("body", response.body());
            return JsonUtil.toJson(result);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return JsonUtil.toJson(error);
        }
    }
}
