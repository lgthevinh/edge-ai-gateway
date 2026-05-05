package thingai.edge.aigateway;

import thingai.edge.aigateway.api.ApiServer;

public class Main {
    public static void main(String[] args) {
        EdgeAiGateway service = new EdgeAiGateway();
        service.init();
        Runtime.getRuntime().addShutdownHook(new Thread(service::shutdown));

        ApiServer apiServer = new ApiServer();
        apiServer.start();
    }
}
