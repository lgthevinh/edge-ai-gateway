package thingai.edge.aigateway;

import org.thingai.base.Service;
import org.thingai.base.dao.Dao;
import org.thingai.base.log.ILog;
import org.thingai.base.utils.ArrayUtils;
import org.thingai.sdk.ai.vector.dao.DaoVectorSqlite;
import org.thingai.sdk.ai.vector.define.DistanceMetric;
import thingai.edge.aigateway.agent.AgentOrchestrator;
import thingai.edge.aigateway.agent.IAgentTool;
import thingai.edge.aigateway.agent.mcp.McpRegistry;
import thingai.edge.aigateway.agent.preset.AssistantAgent;
import thingai.edge.aigateway.agent.tools.CurlApiTool;
import thingai.edge.aigateway.agent.tools.ListDocumentsTool;
import thingai.edge.aigateway.agent.tools.ReadDocumentTool;
import thingai.edge.aigateway.handler.embedding.EmbeddingHandler;
import thingai.edge.aigateway.handler.knowledge.KnowledgeDocument;
import thingai.edge.aigateway.handler.knowledge.KnowledgeHandler;
import thingai.edge.aigateway.llm.LlamaCppProvider;
import thingai.edge.aigateway.handler.session.Session;
import thingai.edge.aigateway.handler.session.SessionMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class EdgeAiGateway extends Service {
    private static final String TAG = "EdgeAiGateway";
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1536;

    private Dao dao;
    private String llamaServerUrl;

    private static McpRegistry mcpRegistry;
    private static LlamaCppProvider llamaCppProvider;
    private static LlamaCppProvider embeddingProvider;
    private static AgentOrchestrator agentOrchestrator;
    private static KnowledgeHandler knowledgeHandler;
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
        String embeddingServerUrl = env.getOrDefault("EMBEDDING_SERVER_URL", llamaServerUrl);
        String embeddingModel = env.getOrDefault("EMBEDDING_MODEL", model);
        int embeddingDimensions = parseInt(
                env.get("EMBEDDING_DIMENSIONS"),
                DEFAULT_EMBEDDING_DIMENSIONS
        );
        configuredLlamaServerUrl = llamaServerUrl;

        // init dao
        DaoVectorSqlite vectorDao = new DaoVectorSqlite(getAppDir() + "/data.db");
        dao = vectorDao;
        dao.initDao(new Class[] {
                Session.class,
                SessionMessage.class,
                KnowledgeDocument.class
        });
        vectorDao.initVectorSearch(
                KnowledgeDocument.class,
                "embedding",
                embeddingDimensions,
                DistanceMetric.COSINE
        );
        llamaCppProvider = new LlamaCppProvider(llamaServerUrl, apiKey, model);
        embeddingProvider = new LlamaCppProvider(embeddingServerUrl, apiKey, embeddingModel);
        ILog.d(TAG, "onServiceInit", "embedding provider initialized model=" + embeddingModel
                + " dimensions=" + embeddingDimensions);
        ILog.d(TAG, "onServiceInit", "knowledge vector search initialized dimensions=" + embeddingDimensions);

        knowledgeHandler = new KnowledgeHandler(
                dao,
                new EmbeddingHandler(embeddingProvider, embeddingModel, embeddingDimensions)
        );

        mcpRegistry = new McpRegistry();
        mcpRegistry.loadConfig("mcp-servers.json");
        IAgentTool[] documentTools = new IAgentTool[] {
                new ListDocumentsTool(knowledgeHandler),
                new ReadDocumentTool(knowledgeHandler),
                new CurlApiTool()
        };
        IAgentTool[] extraTools = ArrayUtils.concat(documentTools, mcpRegistry.getAllTools());
        agentOrchestrator = new AgentOrchestrator(
                dao,
                AssistantAgent.create(llamaCppProvider, extraTools)
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

    public static LlamaCppProvider getEmbeddingProvider() {
        if (embeddingProvider == null) {
            throw new IllegalStateException("EdgeAiGateway has not initialized embedding provider");
        }
        return embeddingProvider;
    }

    public static McpRegistry getMcpRegistry() {
        if (mcpRegistry == null) {
            throw new IllegalStateException("EdgeAiGateway has not initialized McpRegistry");
        }
        return mcpRegistry;
    }

    public static KnowledgeHandler getKnowledgeHandler() {
        if (knowledgeHandler == null) {
            throw new IllegalStateException("EdgeAiGateway has not initialized KnowledgeHandler");
        }
        return knowledgeHandler;
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
        embeddingProvider = null;
        knowledgeHandler = null;
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

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
