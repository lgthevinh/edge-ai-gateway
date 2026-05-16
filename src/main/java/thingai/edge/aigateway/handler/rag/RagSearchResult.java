package thingai.edge.aigateway.handler.rag;

public class RagSearchResult {
    private final RagChunk chunk;
    private final double distance;

    public RagSearchResult(RagChunk chunk, double distance) {
        this.chunk = chunk;
        this.distance = distance;
    }

    public RagChunk getChunk() {
        return chunk;
    }

    public double getDistance() {
        return distance;
    }
}
