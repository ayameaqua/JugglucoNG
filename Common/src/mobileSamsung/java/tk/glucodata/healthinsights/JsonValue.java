package tk.glucodata.healthinsights;

import com.google.gson.*;
import java.lang.reflect.*;
import java.time.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/** Export public DTO properties only, without traversing SDK service/binder internals. */
public final class JsonValue {
    public static JsonElement of(Object value) { return of(value, new IdentityHashMap<>(), 0); }
    private static JsonElement of(Object v, IdentityHashMap<Object, Boolean> path, int depth) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof String || v instanceof Character || v instanceof Enum<?>) return new JsonPrimitive(v.toString());
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number n) {
            if ((n instanceof Double || n instanceof Float) && !Double.isFinite(n.doubleValue())) return new JsonPrimitive(n.toString());
            return new JsonPrimitive(n);
        }
        if (v instanceof Instant i) {
            JsonObject o = new JsonObject();
            o.addProperty("utc", i.toString());
            o.addProperty("epochSeconds", i.getEpochSecond());
            o.addProperty("nano", i.getNano());
            o.addProperty("epochMillis", i.toEpochMilli());
            return o;
        }
        if (v instanceof Duration d) {
            JsonObject o = new JsonObject(); o.addProperty("iso8601", d.toString());
            o.addProperty("seconds", d.getSeconds()); o.addProperty("nano", d.getNano()); return o;
        }
        if (v instanceof TemporalAccessor || v instanceof ZoneId || v instanceof UUID) return new JsonPrimitive(v.toString());
        if (depth > 32 || path.containsKey(v)) {
            JsonObject o = new JsonObject(); o.addProperty("serializationIncomplete", depth > 32 ? "depth_limit" : "cycle"); return o;
        }
        path.put(v, true);
        try {
            if (v instanceof Iterable<?> list) {
                JsonArray a = new JsonArray(); for (Object x : list) a.add(of(x, path, depth + 1)); return a;
            }
            if (v instanceof Map<?, ?> map) {
                JsonObject o = new JsonObject(); map.forEach((k,x) -> o.add(String.valueOf(k), of(x,path,depth+1))); return o;
            }
            if (v.getClass().isArray()) {
                JsonArray a = new JsonArray(); for(int i=0;i<Array.getLength(v);i++) a.add(of(Array.get(v,i),path,depth+1)); return a;
            }
            JsonObject o = new JsonObject(); o.addProperty("sdkClass", v.getClass().getName());
            if (!v.getClass().getName().startsWith("com.samsung.android.sdk.health.data.")) {
                o.addProperty("serializationIncomplete", "unsupported_class"); return o;
            }
            List<Method> methods = Arrays.stream(v.getClass().getMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount()==0 && m.getReturnType()!=Void.TYPE && !m.getName().equals("getClass")
                    && !m.getName().contains("$")
                    && (m.getName().startsWith("get") || m.getName().startsWith("is")))
                .sorted(Comparator.comparing(Method::getName)).collect(java.util.stream.Collectors.toList());
            for (Method m : methods) {
                try { o.add(m.getName(), of(m.invoke(v),path,depth+1)); }
                catch (ReflectiveOperationException | RuntimeException ex) {
                    JsonObject error = new JsonObject(); error.addProperty("serializationError", ex.toString()); o.add(m.getName(), error);
                }
            }
            return o;
        } finally { path.remove(v); }
    }
    private JsonValue() {}
}
