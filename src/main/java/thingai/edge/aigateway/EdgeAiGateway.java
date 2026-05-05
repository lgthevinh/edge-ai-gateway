package thingai.edge.aigateway;

import org.thingai.base.Service;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.platform.dao.DaoSqlite;
import thingai.edge.aigateway.agent.AgentOrchestrator;
import thingai.edge.aigateway.agent.mcp.McpRegistry;
import thingai.edge.aigateway.agent.preset.AssistantAgent;
import thingai.edge.aigateway.llm.LlamaCppProvider;
import thingai.edge.aigateway.session.Session;
import thingai.edge.aigateway.session.SessionMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class EdgeAiGateway extends Service {
    private static final String TAG = "EdgeAiGateway";

    private Dao dao;
    private String llamaServerUrl;

    private static McpRegistry mcpRegistry;
    private static LlamaCppProvider llamaCppProvider;
    private static AgentOrchestrator agentOrchestrator;
    private static String configuredLlamaServerUrl;

    protected EdgeAiGateway() {
        super("edge-ai-gateway");
        setVersion("0.1.0");
        setAppDirName("edge-ai-gateway");
        ILog.ENABLE_LOGGING = true;
        ILog.logLevel = ILog.DEBUG;
    }

    @Override
    protected void onServiceInit() {
        ILog.d(TAG, "onServiceInit", "initializing gateway runtime");

        Map<String, String> env = loadEnv(".env");
        llamaServerUrl = env.getOrDefault(
                "LLAMA_SERVER_URL",
                llamaServerUrl != null ? llamaServerUrl : "http://localhost:8080"
        );
        String apiKey = env.getOrDefault("API_KEY", "");
        String model = env.getOrDefault("LLM_MODEL", "gemma-4-e2b");
        configuredLlamaServerUrl = llamaServerUrl;

        // init dao
        dao = new DaoSqlite(getAppDir() + "/data.db");
        dao.initDao(new Class[] {
                Session.class,
                SessionMessage.class
        });

        mcpRegistry = new McpRegistry();
        mcpRegistry.loadConfig("mcp-servers.json");

        llamaCppProvider = new LlamaCppProvider(llamaServerUrl, apiKey, model);
        agentOrchestrator = new AgentOrchestrator(
                dao,
                AssistantAgent.create(llamaCppProvider, mcpRegistry.getAllTools())
        );

        ILog.d(TAG, "onServiceInit", "gateway runtime initialized");
    }

    public Dao getDao() {
        return dao;
    }

    public void setLlamaServerUrl(String llamaServerUrl) {
        this.llamaServerUrl = llamaServerUrl;
    }

    public String getLlamaServerUrl() {
        return llamaServerUrl;
    }

    public static AgentOrchestrator getAgentOrchestrator() {
        if (agentOrchestrator == null) {
            throw new IllegalStateException("EdgeAiGateway has not initialized AgentOrchestrator");
        }
        return agentOrchestrator;
    }

    public static LlamaCppProvider getLlamaCppProvider() {
        if (llamaCppProvider == null) {
            throw new IllegalStateException("EdgeAiGateway has not initialized LlamaCppProvider");
        }
        return llamaCppProvider;
    }

    public static McpRegistry getMcpRegistry() {
        if (mcpRegistry == null) {
            throw new IllegalStateException("EdgeAiGateway has not initialized McpRegistry");
        }
        return mcpRegistry;
    }

    public static String getLlamaServerUrlStatic() {
        if (configuredLlamaServerUrl == null) {
            throw new IllegalStateException("EdgeAiGateway has not initialized llama server URL");
        }
        return configuredLlamaServerUrl;
    }

    @Override
    protected void onServiceShutdown() {
        if (mcpRegistry != null) {
            mcpRegistry.close();
            mcpRegistry = null;
        }
        agentOrchestrator = null;
        llamaCppProvider = null;
        configuredLlamaServerUrl = null;
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
