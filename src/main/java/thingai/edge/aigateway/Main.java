package thingai.edge.aigateway;

import thingai.edge.aigateway.api.ApiServer;

public class Main {
    public static void main(String[] args) {
        EdgeAgentService service = new EdgeAgentService();
        service.init();

        // Start API server
        ApiServer apiServer = new ApiServer();
        apiServer.start();
    }
}