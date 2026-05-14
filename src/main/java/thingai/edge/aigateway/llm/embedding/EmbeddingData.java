package thingai.edge.aigateway.llm.embedding;

import com.google.gson.annotations.SerializedName;

public class EmbeddingData {
    @SerializedName("object")
    private String object;

    @SerializedName("index")
    private int index;

    @SerializedName("embedding")
    private float[] embedding;

    public EmbeddingData() {
    }

    public EmbeddingData(String object, int index, float[] embedding) {
        this.object = object;
        this.index = index;
        this.embedding = embedding;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
