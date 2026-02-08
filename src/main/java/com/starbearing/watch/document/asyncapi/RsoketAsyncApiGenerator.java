package com.starbearing.watch.document.asyncapi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.starbearing.watch.RouteUtil;
import tools.jackson.databind.json.JsonMapper;

/**
 * RsoketRouteInfo 목록을 AsyncAPI(2.6.0) 문서(JSON)로 변환.
 *
 * - AsyncAPI Studio로 UI 렌더링 가능
 * - nodejs/typescript에서 파싱하여 서비스/유니언 타입 코드를 만들기 쉽도록
 *   RSocket 전용 메타를 x-rsocket 확장 필드에 포함
 *
 * NOTE:
 * - Rsoket 모듈이 REST(swagger) 모듈에 의존하지 않도록 HandlerInfo를 사용하지 않는다.
 */
public class RsoketAsyncApiGenerator {

	public static final class Options {
		private String title = "RSocket API";
		private String version = "1.0.0";
		private String description = "Generated from Spring RSocket @Controller/@MessageMapping";
		private String serverName = "rsocket";
		private String serverUrl = "tcp://localhost:7000";
		private String defaultContentType = "application/json";

		public String getTitle() { return title; }
		public Options setTitle(String title) { this.title = title; return this; }

		public String getVersion() { return version; }
		public Options setVersion(String version) { this.version = version; return this; }

		public String getDescription() { return description; }
		public Options setDescription(String description) { this.description = description; return this; }

		public String getServerName() { return serverName; }
		public Options setServerName(String serverName) { this.serverName = serverName; return this; }

		public String getServerUrl() { return serverUrl; }
		public Options setServerUrl(String serverUrl) { this.serverUrl = serverUrl; return this; }

		public String getDefaultContentType() { return defaultContentType; }
		public Options setDefaultContentType(String defaultContentType) { this.defaultContentType = defaultContentType; return this; }
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	public static String generateAsyncApiJson(List<RsoketRouteInfo> routes, Options options) throws Exception {
		if (options == null) options = new Options();

		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("asyncapi", "2.6.0");
		doc.put("info", Map.of("title", options.getTitle(), "version", options.getVersion(), "description", options.getDescription()));
		doc.put("defaultContentType", options.getDefaultContentType());

		Map<String, Object> servers = new LinkedHashMap<>();
		servers.put(options.getServerName(), Map.of("url", options.getServerUrl(), "protocol", "rsocket"));
		doc.put("servers", servers);

		Map<String, Object> components = new LinkedHashMap<>();
		Map<String, Object> schemas = new LinkedHashMap<>();
		Map<String, Object> messages = new LinkedHashMap<>();
		components.put("schemas", schemas);
		components.put("messages", messages);
		doc.put("components", components);

		Map<String, Object> channels = new LinkedHashMap<>();
		doc.put("channels", channels);

		Map<String, List<RsoketRouteInfo>> byDestination = routes.stream()
			.collect(Collectors.groupingBy(RsoketRouteInfo::getDestination, LinkedHashMap::new, Collectors.toList()));

		for (Map.Entry<String, List<RsoketRouteInfo>> e : byDestination.entrySet()) {
			String destination = e.getKey();
			List<RsoketRouteInfo> destRoutes = e.getValue();

			Map<String, Object> ch = new LinkedHashMap<>();
			ch.put("description", "RSocket destination: " + destination);

			Map<String, Object> params = buildChannelParameters(destRoutes, schemas);
			if (!params.isEmpty()) ch.put("parameters", params);

			// publish: client -> server (request)
			Map<String, Object> publish = new LinkedHashMap<>();
			publish.put("operationId", toOperationId(destination));
			publish.put("summary", "Send request to " + destination);

			List<Map<String, Object>> reqMsgRefs = new ArrayList<>();
			for (RsoketRouteInfo r : destRoutes) {
				String msgKey = ensureRequestMessage(r, messages, schemas);
				reqMsgRefs.add(Map.of("$ref", "#/components/messages/" + msgKey));
			}
			publish.put("message", oneOfOrSingle(reqMsgRefs));

			// subscribe: server -> client (response). fireAndForget는 생략
			Map<String, Object> subscribe = buildSubscribeOperation(destRoutes, messages, schemas);

			// channel level extensions (코드 제너레이터용)
			ch.put("x-rsocket", Map.of(
				"destination", destination,
				"routes", destRoutes.stream().map(RsoketAsyncApiGenerator::routeMeta).collect(Collectors.toList())
			));

			ch.put("publish", publish);
			if (subscribe != null) ch.put("subscribe", subscribe);

			channels.put(destination, ch);
		}

		doc.put("x-generatedAt", Instant.now().toString());
		doc.put("x-format", "rsocket-asyncapi");

		JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
	}

	// ---------------------------
	// Publish/Subscribe modeling
	// ---------------------------

	private static Map<String, Object> buildSubscribeOperation(
		List<RsoketRouteInfo> destRoutes,
		Map<String, Object> messages,
		Map<String, Object> schemas
	) {
		List<Map<String, Object>> respMsgRefs = new ArrayList<>();
		boolean hasAnyResponse = false;

		for (RsoketRouteInfo r : destRoutes) {
			RsoketHandlerInfo hi = r.getHandlerInfo();
			if (hi == null || hi.getResponseBodyInfo().isEmpty()) continue;

			RsoketTypeInfo resp = hi.getResponseBodyInfo().values().iterator().next();
			if (isVoid(resp)) continue;

			hasAnyResponse = true;
			String msgKey = ensureResponseMessage(r, messages, schemas);
			respMsgRefs.add(Map.of("$ref", "#/components/messages/" + msgKey));
		}

		if (!hasAnyResponse) return null;

		Map<String, Object> subscribe = new LinkedHashMap<>();
		subscribe.put("operationId", toOperationId(destRoutes.get(0).getDestination()) + "_reply");
		subscribe.put("summary", "Receive response from " + destRoutes.get(0).getDestination());
		subscribe.put("message", oneOfOrSingle(respMsgRefs));

		boolean anyFlux = destRoutes.stream().anyMatch(r -> "Flux".equals(r.getPublisher()));
		subscribe.put("x-rsocket", Map.of("stream", anyFlux));

		return subscribe;
	}

	private static boolean isVoid(RsoketTypeInfo info) {
		if (info == null) return true;
		Class<?> t = info.getType();
		if (t == Void.class || t == void.class) return true;
		String q = (info.getTypeRef() != null) ? info.getTypeRef().getQualifiedName() : null;
		return "java.lang.Void".equals(q) || "void".equals(q);
	}

	private static Object oneOfOrSingle(List<Map<String, Object>> refs) {
		if (refs == null || refs.isEmpty()) return Map.of();
		if (refs.size() == 1) return refs.get(0);
		return Map.of("oneOf", refs);
	}

	// ---------------------------
	// Channel parameters
	// ---------------------------

	private static Map<String, Object> buildChannelParameters(List<RsoketRouteInfo> destRoutes, Map<String, Object> schemas) {
		Map<String, Object> params = new LinkedHashMap<>();

		for (RsoketRouteInfo r : destRoutes) {
			RsoketHandlerInfo hi = r.getHandlerInfo();
			if (hi == null) continue;

			hi.getDestinationVariableInfo().forEach((name, info) -> {
				params.putIfAbsent(name, Map.of(
					"description", "Destination variable: " + name,
					"schema", mapType(info, schemas)
				));
			});
		}

		return params;
	}

	// ---------------------------
	// Messages
	// ---------------------------

	@SuppressWarnings("unchecked")
	private static String ensureRequestMessage(RsoketRouteInfo route, Map<String, Object> messages, Map<String, Object> schemas) {
		String msgKey = safeId(route.getControllerSimpleName() + "_" + route.getMethod() + "_Request");
		if (messages.containsKey(msgKey)) return msgKey;

		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put("name", msgKey);
		msg.put("title", route.getControllerSimpleName() + "." + route.getMethod() + " request");

		RsoketHandlerInfo hi = route.getHandlerInfo();
		Object payloadSchema = buildRequestPayloadSchema(hi, schemas);
		msg.put("payload", payloadSchema);

		msg.put("x-rsocket", messageMeta(route, "request"));
		messages.put(msgKey, msg);
		return msgKey;
	}

	private static String ensureResponseMessage(RsoketRouteInfo route, Map<String, Object> messages, Map<String, Object> schemas) {
		String msgKey = safeId(route.getControllerSimpleName() + "_" + route.getMethod() + "_Response");
		if (messages.containsKey(msgKey)) return msgKey;

		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put("name", msgKey);
		msg.put("title", route.getControllerSimpleName() + "." + route.getMethod() + " response");

		RsoketHandlerInfo hi = route.getHandlerInfo();
		Object payloadSchema = Map.of("type", "object");
		if (hi != null && !hi.getResponseBodyInfo().isEmpty()) {
			RsoketTypeInfo resp = hi.getResponseBodyInfo().values().iterator().next();
			payloadSchema = mapType(resp, schemas);
		}

		msg.put("payload", payloadSchema);
		msg.put("x-rsocket", messageMeta(route, "response"));

		messages.put(msgKey, msg);
		return msgKey;
	}

	private static Object buildRequestPayloadSchema(RsoketHandlerInfo hi, Map<String, Object> schemas) {
		if (hi == null || hi.getPayloadInfo().isEmpty()) {
			return Map.of("type", "object", "additionalProperties", false);
		}

		// 단일 payload면 그대로 사용
		if (hi.getPayloadInfo().size() == 1) {
			RsoketTypeInfo p = hi.getPayloadInfo().values().iterator().next();
			Map<String, Object> schema = mapType(p, schemas);

			// 코드 제너레이터가 원본 파라미터 이름을 알 수 있게 확장
			return new LinkedHashMap<>(Map.of(
				"allOf", List.of(schema, Map.of("x-paramName", p.getName()))
			));
		}

		// 여러 개면 object wrapper
		Map<String, Object> obj = new LinkedHashMap<>();
		obj.put("type", "object");
		obj.put("additionalProperties", false);

		Map<String, Object> props = new LinkedHashMap<>();
		hi.getPayloadInfo().forEach((name, info) -> props.put(name, mapType(info, schemas)));
		obj.put("properties", props);

		obj.put("x-multiParam", true);
		return obj;
	}

	private static Map<String, Object> messageMeta(RsoketRouteInfo r, String direction) {
		String interaction = "requestResponse";
		if ("Flux".equals(r.getPublisher())) interaction = "requestStream";

		boolean fireAndForget = false;
		RsoketHandlerInfo hi = r.getHandlerInfo();
		if (hi != null && !hi.getResponseBodyInfo().isEmpty()) {
			RsoketTypeInfo resp = hi.getResponseBodyInfo().values().iterator().next();
			fireAndForget = isVoid(resp);
		}

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("direction", direction);
		m.put("controller", r.getController());
		m.put("method", r.getMethod());
		m.put("destination", r.getDestination());
		m.put("publisher", r.getPublisher());
		m.put("interaction", interaction);
		m.put("fireAndForget", fireAndForget);
		return m;
	}

	private static Map<String, Object> routeMeta(RsoketRouteInfo r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("controller", r.getController());
		m.put("method", r.getMethod());
		m.put("publisher", r.getPublisher());
		return m;
	}

	// ---------------------------
	// Id helpers
	// ---------------------------

	private static String toOperationId(String destination) {
		String id = safeId(destination);
		if (id.isEmpty()) id = "operation";
		if (Character.isDigit(id.charAt(0))) id = "_" + id;
		return id;
	}

	private static String safeId(String raw) {
		if (raw == null) return "";
		return raw.replaceAll("[^A-Za-z0-9_]", "_");
	}

	// ---------------------------
	// Schema mapping
	// ---------------------------

	private static Map<String, Object> buildSchema(RsoketTypeInfo info, Map<String, Object> schemas) {
		Map<String, Object> schema = new LinkedHashMap<>();
		Map<String, Object> properties = new LinkedHashMap<>();

		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("additionalProperties", false);

		// 타입 메타 (코드 제너레이터용)
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

	private static String javaTypeName(RsoketTypeInfo info) {
		if (info == null) return null;
		if (info.getTypeRef() != null && info.getTypeRef().getQualifiedName() != null) return info.getTypeRef().getQualifiedName();
		if (info.getType() != null && info.getType() != Object.class) return info.getType().getName();
		return null;
	}

	private static String schemaId(RsoketTypeInfo info) {
		String t = javaTypeName(info);
		if (t == null) {
			// fallback: simple name
			if (info != null && info.getTypeRef() != null) t = info.getTypeRef().getSimpleName();
			else if (info != null && info.getType() != null) t = info.getType().getSimpleName();
		}
		return safeId(t == null ? "Object" : t);
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

		// date/time
		if (type == java.time.LocalDateTime.class || type == java.time.LocalDate.class || type == java.time.LocalTime.class || type == java.util.Date.class || type == java.time.Instant.class
			|| "java.time.LocalDateTime".equals(q) || "java.time.LocalDate".equals(q) || "java.time.LocalTime".equals(q) || "java.util.Date".equals(q) || "java.time.Instant".equals(q)) {
			typeStr = "string";
			format = ("java.time.LocalDate".equals(q) || type == java.time.LocalDate.class) ? "date"
				: ("java.time.LocalTime".equals(q) || type == java.time.LocalTime.class) ? "time"
				: "date-time";
		}

		// enum
		if (type != null && type != Object.class && type.isEnum()) {
			typeStr = "string";
			enumList.addAll(RouteUtil.parserEnumValues(type));
		}

		// pojo ref
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

		// special
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
