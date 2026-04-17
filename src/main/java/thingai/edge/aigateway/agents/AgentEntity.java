package thingai.edge.aigateway.agents;

public class AgentEntity {
    private String name;
    private String systemInstruction;
    private String model;
    private String[] toolKit;
    private int[] samplingParams; // temperature, top_p, top_k

    public AgentEntity(String name, String systemInstruction, String model, String[] toolKit) {
        this.name = name;
        this.systemInstruction = systemInstruction;
        this.model = model;
        this.toolKit = toolKit;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSystemInstruction() {
        return systemInstruction;
    }

    public void setSystemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String[] getToolKit() {
        return toolKit;
    }

    public void setToolKit(String[] toolKit) {
        this.toolKit = toolKit;
    }

    public int[] getSamplingParams() {
        return samplingParams;
    }

    public void setSamplingParams(int[] samplingParams) {
        if (samplingParams.length != 3) {
            throw new IllegalArgumentException("Sampling parameters must be an array of three integers: [temperature, top_p, top_k]");
        }
        this.samplingParams = samplingParams;
    }
}
