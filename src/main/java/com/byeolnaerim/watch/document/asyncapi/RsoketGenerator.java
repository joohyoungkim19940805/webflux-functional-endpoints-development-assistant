package com.byeolnaerim.watch.document.asyncapi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.byeolnaerim.watch.RouteUtil;
import tools.jackson.databind.json.JsonMapper;

/**
 * RsoketRouteInfo 목록을 "rsoket.json" 포맷으로 출력.
 * - 목표: AsyncAPI UI 외에도, 클라이언트 코드제너레이터가 쉽게 파싱할 수 있는 단순 포맷 제공
 */
public class RsoketGenerator {

	public static String generateRsoketJson(List<RsoketRouteInfo> routes) throws Exception {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("generatedAt", Instant.now().toString());
		root.put("format", "rsoket");

		Map<String, Object> components = new LinkedHashMap<>();
		Map<String, Object> schemas = new LinkedHashMap<>();
		components.put("schemas", schemas);
		root.put("components", components);

		Map<String, List<RsoketRouteInfo>> byController = routes.stream()
			.collect(Collectors.groupingBy(RsoketRouteInfo::getController, LinkedHashMap::new, Collectors.toList()));

		List<Map<String, Object>> controllers = new ArrayList<>();
		for (Map.Entry<String, List<RsoketRouteInfo>> e : byController.entrySet()) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("controller", e.getKey());
			c.put("routes", e.getValue().stream().map(r -> routeToMap(r, schemas)).collect(Collectors.toList()));
			controllers.add(c);
		}
		root.put("controllers", controllers);

		JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
		return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
	}

	private static Map<String, Object> routeToMap(RsoketRouteInfo route, Map<String, Object> schemas) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("destination", route.getDestination());
		m.put("method", route.getMethod());
		m.put("publisher", route.getPublisher());

		RsoketHandlerInfo hi = route.getHandlerInfo();
		if (hi == null) return m;

		List<Map<String, Object>> payload = new ArrayList<>();
		hi.getPayloadInfo().forEach((name, info) -> {
			payload.add(Map.of("name", name, "schema", mapType(info, schemas)));
		});
		m.put("payload", payload);

		List<Map<String, Object>> destVars = new ArrayList<>();
		hi.getDestinationVariableInfo().forEach((name, info) -> {
			destVars.add(Map.of("name", name, "schema", mapType(info, schemas)));
		});
		m.put("destinationVariables", destVars);

		if (!hi.getResponseBodyInfo().isEmpty()) {
			RsoketTypeInfo r = hi.getResponseBodyInfo().values().iterator().next();
			m.put("response", mapType(r, schemas));
		}
		return m;
	}

	// ---- schema mapping (AsyncAPI generator와 동일 로직) ----

	private static String safeId(String raw) {
		if (raw == null) return "";
		return raw.replaceAll("[^A-Za-z0-9_]", "_");
	}

	private static String javaTypeName(RsoketTypeInfo info) {
		if (info == null) return null;
		if (info.getTypeRef() != null && info.getTypeRef().getQualifiedName() != null) return info.getTypeRef().getQualifiedName();
		if (info.getType() != null && info.getType() != Object.class) return info.getType().getName();
		return null;
	}

	private static String schemaId(RsoketTypeInfo info) {
		String t = javaTypeName(info);
		if (t == null) {
			if (info != null && info.getTypeRef() != null) t = info.getTypeRef().getSimpleName();
			else if (info != null && info.getType() != null) t = info.getType().getSimpleName();
		}
		return safeId(t == null ? "Object" : t);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> buildSchema(RsoketTypeInfo info, Map<String, Object> schemas) {
		Map<String, Object> schema = new LinkedHashMap<>();
		Map<String, Object> properties = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("additionalProperties", false);

		String javaType = javaTypeName(info);
		if (javaType != null) schema.put("x-javaType", javaType);

		info.getFields().forEach((fieldName, fieldInfo) -> {
			Map<String, Object> fieldTypeMap = mapType(fieldInfo, schemas);
			Map<String, Object> property = new LinkedHashMap<>(fieldTypeMap);

			if (fieldInfo.getDescription() != null) property.put("description", fieldInfo.getDescription());
			if (fieldInfo.getExample() != null) property.put("example", fieldInfo.getExample());

			properties.put(fieldName, property);
		});

		return schema;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapType(RsoketTypeInfo info, Map<String, Object> schemas) {
		Map<String, Object> schema = new LinkedHashMap<>();
		String q = (info != null && info.getTypeRef() != null) ? info.getTypeRef().getQualifiedName() : null;
		Class<?> type = (info != null) ? info.getType() : null;

		String typeStr = null;
		String format = null;
		List<String> enumList = new ArrayList<>();
		Map<String, Object> items = new LinkedHashMap<>();

		if (type == String.class || "java.lang.String".equals(q)) typeStr = "string";

		if (type == Integer.class || type == int.class || type == Long.class || type == long.class || type == Byte.class || type == byte.class || type == Short.class || type == short.class
			|| "java.lang.Integer".equals(q) || "int".equals(q) || "java.lang.Long".equals(q) || "long".equals(q) || "java.lang.Short".equals(q) || "short".equals(q) || "java.lang.Byte".equals(q) || "byte".equals(q)) {
			typeStr = "integer";
		}

		if (type == Double.class || type == double.class || type == Float.class || type == float.class
			|| "java.lang.Double".equals(q) || "double".equals(q) || "java.lang.Float".equals(q) || "float".equals(q)) {
			typeStr = "number";
		}

		if (type == Boolean.class || type == boolean.class || "java.lang.Boolean".equals(q) || "boolean".equals(q)) {
			typeStr = "boolean";
		}

		boolean isList =
			(type != null && java.util.List.class.isAssignableFrom(type)) ||
			("java.util.List".equals(q)) ||
			(info != null && info.getTypeRef() != null && "List".equals(info.getTypeRef().getSimpleName()));

		if (isList) {
			typeStr = "array";

			Map<String, Object> prevMap = new LinkedHashMap<>();
			BiConsumer<Map<String, Object>, Map<String, Object>> putMap = (parentMap, childMap) -> {
				Object _items = childMap.get("items");
				Object _enumList = childMap.get("enum");
				Object _format = childMap.get("format");

				if (childMap.containsKey("type")) parentMap.put("type", childMap.get("type"));
				else if (childMap.containsKey("$ref")) parentMap.put("$ref", childMap.get("$ref"));

				if (_items != null) parentMap.put("items", _items);
				if (_format != null) parentMap.put("format", _format);
				if (_enumList != null) parentMap.put("enum", _enumList);
			};

			for (int i = 0, len = info.getGenericTypes().size(); i < len; i += 1) {
				if (i == 0) {
					prevMap = mapType(info.getGenericTypes().get(i), schemas);
					putMap.accept(items, prevMap);
					continue;
				}

				Map<String, Object> _items = (Map<String, Object>) prevMap.get("items");
				Map<String, Object> nextMap = mapType(info.getGenericTypes().get(i), schemas);
				putMap.accept(_items, nextMap);
				prevMap = nextMap;
			}
		}

		if (type == java.time.LocalDateTime.class || type == java.time.LocalDate.class || type == java.time.LocalTime.class || type == java.util.Date.class || type == java.time.Instant.class
			|| "java.time.LocalDateTime".equals(q) || "java.time.LocalDate".equals(q) || "java.time.LocalTime".equals(q) || "java.util.Date".equals(q) || "java.time.Instant".equals(q)) {
			typeStr = "string";
			format = ("java.time.LocalDate".equals(q) || type == java.time.LocalDate.class) ? "date"
				: ("java.time.LocalTime".equals(q) || type == java.time.LocalTime.class) ? "time"
				: "date-time";
		}

		if (type != null && type != Object.class && type.isEnum()) {
			typeStr = "string";
			enumList.addAll(RouteUtil.parserEnumValues(type));
		}

		boolean isPojo =
			(type != null && type != Object.class && RouteUtil.isPojo(type)) ||
			(info != null && info.getTypeRef() != null && info.getTypeRef().getQualifiedName() != null &&
				!info.getTypeRef().getQualifiedName().startsWith("java.") && !info.getTypeRef().getQualifiedName().startsWith("javax.") &&
				info.getTypeRef().getTypeDeclaration() != null);

		if (isPojo) {
			String id = schemaId(info);
			String ref = "#/components/schemas/" + id;

			if (schemas.containsKey(id) && schemas.get(id) instanceof Map map) {
				map.putAll(buildSchema(info, schemas));
			} else {
				schemas.putIfAbsent(id, buildSchema(info, schemas));
			}

			schema.put("$ref", ref);
			return schema;
		}

		if (type == org.bson.types.ObjectId.class || "org.bson.types.ObjectId".equals(q)) {
			typeStr = "string";
		}

		if (typeStr == null) typeStr = "object";

		if (format != null) schema.put("format", format);
		if (!enumList.isEmpty()) schema.put("enum", enumList);
		if (!items.isEmpty()) schema.put("items", items);

		schema.put("type", typeStr);
		return schema;
	}
}
