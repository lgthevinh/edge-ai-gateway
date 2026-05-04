package thingai.edge.aigateway;

import thingai.edge.aigateway.agent.AgentOrchestrator;
import thingai.edge.aigateway.agent.preset.AssistantAgent;
import thingai.edge.aigateway.api.ApiServer;
import thingai.edge.aigateway.llm.LlamaCppProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        Map<String, String> env = loadEnv(".env");
        String llamaServerUrl = env.getOrDefault("LLAMA_SERVER_URL", "http://localhost:8080");
        String apiKey = env.getOrDefault("API_KEY", "");
        String model = env.getOrDefault("LLM_MODEL", "gemma-4-e2b");

        EdgeAiGateway service = new EdgeAiGateway();
        service.setLlamaServerUrl(llamaServerUrl);
        service.init();

        LlamaCppProvider provider = new LlamaCppProvider(llamaServerUrl, apiKey, model);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                service.getDao(),
                AssistantAgent.create(provider)
        );

        ApiServer apiServer = new ApiServer(llamaServerUrl, orchestrator);
        apiServer.start();
    }

    private static Map<String, String> loadEnv(String path) {
        Map<String, String> env = new HashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of(path))) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) {
        }
        return env;
    }
}
