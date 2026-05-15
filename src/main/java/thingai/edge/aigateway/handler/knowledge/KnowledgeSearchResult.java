package thingai.edge.aigateway.handler.knowledge;

public class KnowledgeSearchResult {
    private final KnowledgeDocument document;
    private final double distance;

    public KnowledgeSearchResult(KnowledgeDocument document, double distance) {
        this.document = document;
        this.distance = distance;
    }

    public KnowledgeDocument getDocument() {
        return document;
    }

    public double getDistance() {
        return distance;
    }
}
