package com.byeolnaerim.watch.document.prp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import com.byeolnaerim.watch.RouteUtil;
import tools.jackson.databind.json.JsonMapper;

public final class PrpJsonGenerator {
    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

    public static final class Options {
        private String title = "PRP API";
        private String version = "1.0.0";
        private String description = "Generated from @PrpRoute";
        public String getTitle() { return title; }
        public Options setTitle(String value) { title = value; return this; }
        public String getVersion() { return version; }
        public Options setVersion(String value) { version = value; return this; }
        public String getDescription() { return description; }
        public Options setDescription(String value) { description = value; return this; }
    }

    private PrpJsonGenerator() {}

    public static String generatePrpJson(List<PrpRouteInfo> routes, Options options) throws Exception {
        Options resolved = options == null ? new Options() : options;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("prp", "1.0");
        doc.put("x-format", "prp-contract");
        doc.put("info", Map.of(
            "title", resolved.getTitle(),
            "version", resolved.getVersion(),
            "description", resolved.getDescription()
        ));

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> schemas = new LinkedHashMap<>();
        components.put("schemas", schemas);
        doc.put("components", components);

        List<Map<String, Object>> operations = new ArrayList<>();
        Set<String> operationKeys = new HashSet<>();
        for (PrpRouteInfo route : routes) {
            String operationKey = route.getRoute() + "#" + route.getInteraction();
            if (!operationKeys.add(operationKey)) {
                throw new IllegalStateException("Duplicate PRP route interaction: " + operationKey);
            }
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("operationId", operationId(route));
            operation.put("route", route.getRoute());
            operation.put("interaction", route.getInteraction());
            operation.put("controller", route.getController());
            operation.put("controllerSimpleName", route.getControllerSimpleName());
            operation.put("method", route.getMethod());
            if (route.getDescription() != null && !route.getDescription().isBlank()) operation.put("description", route.getDescription());
            operation.put("request", "DATAGRAM".equals(route.getInteraction()) ? binarySchema() : mapType(route.getRequest(), schemas));
            operation.put("response", mapType(route.getResponse(), schemas));
            operations.add(operation);
        }
        doc.put("operations", operations);
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
    }

    private static String operationId(PrpRouteInfo route) {
        return safeId(route.getRoute() + "_" + route.getInteraction().toLowerCase());
    }

    private static Map<String, Object> binarySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("format", "binary");
        schema.put("x-typescriptType", "Uint8Array");
        return schema;
    }

    private static Map<String, Object> mapType(PrpTypeInfo info, Map<String, Object> schemas) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (info == null) return schema;
        String q = qualifiedName(info);
        Class<?> type = info.getType();
        String simple = simpleName(info);

        if (isVoid(type, q, simple)) {
            schema.put("type", "null");
            schema.put("x-typescriptType", "void");
            return schema;
        }
        if (isByteArray(info)) return binarySchema();
        if (isString(type, q)) { schema.put("type", "string"); return schema; }
        if (isBoolean(type, q)) { schema.put("type", "boolean"); return schema; }
        if (isInteger(type, q)) { schema.put("type", "integer"); return schema; }
        if (isNumber(type, q)) { schema.put("type", "number"); return schema; }
        if (isDate(type, q)) {
            schema.put("type", "string");
            schema.put("format", q != null && q.endsWith("LocalDate") ? "date" : q != null && q.endsWith("LocalTime") ? "time" : "date-time");
            return schema;
        }
        if (type != null && type != Object.class && type.isEnum()) {
            schema.put("type", "string");
            schema.put("enum", RouteUtil.parserEnumValues(type));
            return schema;
        }
        if (isArrayLike(info, type, q, simple)) {
            schema.put("type", "array");
            PrpTypeInfo item = !info.getGenericTypes().isEmpty() ? info.getGenericTypes().get(0) : null;
            if (item == null && info.getTypeRef() instanceof spoon.reflect.reference.CtArrayTypeReference<?> array) {
                item = new PrpTypeInfo();
                item.setTypeRef(array.getComponentType());
            }
            schema.put("items", item == null ? Map.of("type", "object") : mapType(item, schemas));
            return schema;
        }
        if (isMapLike(type, q, simple)) {
            schema.put("type", "object");
            PrpTypeInfo value = info.getGenericTypes().size() >= 2 ? info.getGenericTypes().get(1) : null;
            schema.put("additionalProperties", value == null ? true : mapType(value, schemas));
            return schema;
        }
        if (q != null && (q.endsWith("ObjectId") || "org.bson.types.ObjectId".equals(q))) {
            schema.put("type", "string");
            return schema;
        }
        if (isPojo(info, type, q)) {
            String id = schemaId(info);
            ensureSchema(id, info, schemas);
            schema.put("$ref", "#/components/schemas/" + id);
            return schema;
        }
        schema.put("type", "object");
        return schema;
    }

    private static void ensureSchema(String id, PrpTypeInfo info, Map<String, Object> schemas) {
        if (schemas.containsKey(id)) return;
        Map<String, Object> target = new LinkedHashMap<>();
        schemas.put(id, target);
        target.put("type", "object");
        target.put("additionalProperties", false);
        String javaType = qualifiedName(info);
        if (javaType != null) target.put("x-javaType", javaType);
        Map<String, Object> properties = new LinkedHashMap<>();
        target.put("properties", properties);
        info.getFields().forEach((name, field) -> {
            Map<String, Object> mapped = new LinkedHashMap<>(mapType(field, schemas));
            if (field.getDescription() != null && !field.getDescription().isBlank()) mapped.put("description", field.getDescription());
            if (field.getExample() != null) mapped.put("example", field.getExample());
            properties.put(name, mapped);
        });
    }

    private static boolean isPojo(PrpTypeInfo info, Class<?> type, String q) {
        if (type != null && type != Object.class && RouteUtil.isPojo(type)) return true;
        return q != null && !q.startsWith("java.") && !q.startsWith("javax.") && !q.startsWith("jakarta.")
            && info.getTypeRef() != null && info.getTypeRef().getTypeDeclaration() != null;
    }

    private static boolean isArrayLike(PrpTypeInfo info, Class<?> type, String q, String simple) {
        return (type != null && (type.isArray() || java.util.Collection.class.isAssignableFrom(type)))
            || (info.getTypeRef() != null && info.getTypeRef().isArray())
            || "java.util.List".equals(q) || "java.util.Set".equals(q) || "java.util.Collection".equals(q)
            || "List".equals(simple) || "Set".equals(simple) || "Collection".equals(simple);
    }

    private static boolean isMapLike(Class<?> type, String q, String simple) {
        return (type != null && java.util.Map.class.isAssignableFrom(type)) || "java.util.Map".equals(q) || "Map".equals(simple);
    }

    private static boolean isByteArray(PrpTypeInfo info) {
        return info.getTypeRef() != null && info.getTypeRef().isArray()
            && info.getTypeRef() instanceof spoon.reflect.reference.CtArrayTypeReference<?> array
            && "byte".equals(array.getComponentType().getQualifiedName());
    }

    private static boolean isVoid(Class<?> type, String q, String simple) {
        return type == void.class || type == Void.class || "void".equals(q) || "java.lang.Void".equals(q) || "void".equals(simple) || "Void".equals(simple);
    }
    private static boolean isString(Class<?> type, String q) { return type == String.class || "java.lang.String".equals(q) || "char".equals(q) || "java.lang.Character".equals(q); }
    private static boolean isBoolean(Class<?> type, String q) { return type == boolean.class || type == Boolean.class || "boolean".equals(q) || "java.lang.Boolean".equals(q); }
    private static boolean isInteger(Class<?> type, String q) {
        return type == byte.class || type == Byte.class || type == short.class || type == Short.class || type == int.class || type == Integer.class || type == long.class || type == Long.class
            || "byte".equals(q) || "java.lang.Byte".equals(q) || "short".equals(q) || "java.lang.Short".equals(q) || "int".equals(q) || "java.lang.Integer".equals(q) || "long".equals(q) || "java.lang.Long".equals(q);
    }
    private static boolean isNumber(Class<?> type, String q) { return type == float.class || type == Float.class || type == double.class || type == Double.class || "float".equals(q) || "java.lang.Float".equals(q) || "double".equals(q) || "java.lang.Double".equals(q); }
    private static boolean isDate(Class<?> type, String q) {
        return type == java.time.LocalDate.class || type == java.time.LocalDateTime.class || type == java.time.LocalTime.class || type == java.time.Instant.class || type == java.util.Date.class
            || "java.time.LocalDate".equals(q) || "java.time.LocalDateTime".equals(q) || "java.time.LocalTime".equals(q) || "java.time.Instant".equals(q) || "java.util.Date".equals(q);
    }
    private static String qualifiedName(PrpTypeInfo info) {
        if (info == null) return null;
        if (info.getTypeRef() != null && info.getTypeRef().getQualifiedName() != null) return info.getTypeRef().getQualifiedName();
        if (info.getType() != null && info.getType() != Object.class) return info.getType().getName();
        return null;
    }
    private static String simpleName(PrpTypeInfo info) {
        if (info == null) return null;
        if (info.getTypeRef() != null) return info.getTypeRef().getSimpleName();
        if (info.getType() != null) return info.getType().getSimpleName();
        return null;
    }
    private static String schemaId(PrpTypeInfo info) {
        String value = qualifiedName(info);
        if (value == null) value = simpleName(info);
        return safeId(value == null ? "Object" : value);
    }
    private static String safeId(String value) { return value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_"); }
}
