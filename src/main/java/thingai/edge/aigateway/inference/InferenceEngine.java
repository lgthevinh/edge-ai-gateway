package thingai.edge.aigateway.inference;

import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;

import java.util.concurrent.ConcurrentLinkedQueue;

public class InferenceEngine {
    private static final String TAG = "InferenceEngine";

    /**
     * Single model engine, although the
     */
    private LlamaModel model;
    private ModelParameters modelParameters;

    private ConcurrentLinkedQueue<InferenceTask> inferenceTasks = new ConcurrentLinkedQueue<>();

    public InferenceEngine(ModelParameters modelParameters, LlamaModel model) {
        this.modelParameters = modelParameters;
        this.model = model;
    }

}
