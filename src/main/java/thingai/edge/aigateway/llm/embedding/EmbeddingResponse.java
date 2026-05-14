package thingai.edge.aigateway.llm.embedding;

import com.google.gson.annotations.SerializedName;

public class EmbeddingResponse {
    @SerializedName("object")
    private String object;

    @SerializedName("data")
    private EmbeddingData[] data;

    @SerializedName("model")
    private String model;

    @SerializedName("usage")
    private EmbeddingUsage usage;

    public EmbeddingResponse() {
    }

    public EmbeddingResponse(String object, EmbeddingData[] data, String model, EmbeddingUsage usage) {
        this.object = object;
        this.data = data;
        this.model = model;
        this.usage = usage;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public EmbeddingData[] getData() {
        return data;
    }

    public void setData(EmbeddingData[] data) {
        this.data = data;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public EmbeddingUsage getUsage() {
        return usage;
    }

    public void setUsage(EmbeddingUsage usage) {
        this.usage = usage;
    }

    public float[] getFirstEmbedding() {
        if (data == null || data.length == 0) return null;
        return data[0].getEmbedding();
    }
}
