package thingai.edge.aigateway.utils;

import com.google.gson.Gson;

public class JsonUtil {
    public static final Gson gson = new Gson();

    public static synchronized String toJson(Object obj) {
        return gson.toJson(obj);
    }

    public static synchronized <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }
}
