package thingai.edge.aigateway.llm.embedding;

import com.google.gson.annotations.SerializedName;

public class EmbeddingRequest {
    @SerializedName("model")
    private String model;

    @SerializedName("input")
    private Object input;

    @SerializedName("encoding_format")
    private String encodingFormat;

    @SerializedName("dimensions")
    private Integer dimensions;

    public EmbeddingRequest() {
    }

    public EmbeddingRequest(String model, Object input, String encodingFormat, Integer dimensions) {
        this.model = model;
        this.input = input;
        this.encodingFormat = encodingFormat;
        this.dimensions = dimensions;
    }

    public static EmbeddingRequest of(String input) {
        return new EmbeddingRequest(null, input, "float", null);
    }

    public static EmbeddingRequest of(String[] input) {
        return new EmbeddingRequest(null, input, "float", null);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public String getEncodingFormat() {
        return encodingFormat;
    }

    public void setEncodingFormat(String encodingFormat) {
        this.encodingFormat = encodingFormat;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }
}
