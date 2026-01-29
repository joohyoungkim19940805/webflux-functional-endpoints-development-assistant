package com.byeolnaerim.watch.document.asyncapi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo.Info;
import tools.jackson.databind.json.JsonMapper;

/**
 * RSocket(@Controller + @MessageMapping) 라우트를 AsyncAPI(2.6.0) 문서(JSON)로 변환한다.
 *
 * 목적:
 * 1) AsyncAPI Studio로 UI 문서 렌더링
 * 2) nodejs/typescript에서 파싱하여 서비스/타입(유니언 포함) 코드를 생성하기 쉬운 형태 제공
 *
 * - AsyncAPI는 protocol-agnostic 이므로 server.protocol 에 "rsocket"을 사용한다.
 * - RSocket 전용 정보(Interaction model, publisher, controller/method 등)는 x-rsocket 확장 필드에 넣는다.
 *
 * 주의:
 * - AsyncAPI 3.0은 Studio/Generator 등 도구 호환성이 흔들리는 경우가 있어 2.6.0을 기본으로 한다.
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

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static String generateAsyncApiJson(
		List<RsoketRouteInfo> routes, Options options
	)
		throws Exception {

		if (options == null) { options = new Options(); }

		// ---------- Root ----------
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put( "asyncapi", "2.6.0" );

		doc.put(
			"info",
			Map.of(
				"title", options.getTitle(),
				"version", options.getVersion(),
				"description", options.getDescription()
			)
		);

		doc.put( "defaultContentType", options.getDefaultContentType() );

		// ---------- Servers ----------
		Map<String, Object> servers = new LinkedHashMap<>();
		servers.put(
			options.getServerName(),
			Map.of(
				"url", options.getServerUrl(),
				"protocol", "rsocket"
			)
		);
		doc.put( "servers", servers );

		// ---------- Components ----------
		Map<String, Object> components = new LinkedHashMap<>();
		Map<String, Object> schemas = new LinkedHashMap<>();
		Map<String, Object> messages = new LinkedHashMap<>();
		components.put( "schemas", schemas );
		components.put( "messages", messages );
		doc.put( "components", components );

		// ---------- Channels ----------
		Map<String, Object> channels = new LinkedHashMap<>();
		doc.put( "channels", channels );

		// destination 기준으로 묶되, 같은 destination이 여러 개면 message.oneOf 로 합친다.
		Map<String, List<RsoketRouteInfo>> byDestination = routes
			.stream()
			.collect( Collectors.groupingBy( RsoketRouteInfo::getDestination, LinkedHashMap::new, Collectors.toList() ) );

		for (Map.Entry<String, List<RsoketRouteInfo>> e : byDestination.entrySet()) {
			String destination = e.getKey();
			List<RsoketRouteInfo> destRoutes = e.getValue();

			Map<String, Object> ch = new LinkedHashMap<>();
			ch.put( "description", "RSocket destination: " + destination );

			// parameters (DestinationVariable)
			Map<String, Object> params = buildChannelParameters( destRoutes, schemas );
			if (! params.isEmpty()) {
				ch.put( "parameters", params );

			}

			// publish: client -> server (request)
			Map<String, Object> publish = new LinkedHashMap<>();
			publish.put( "operationId", toOperationId( destination ) );
			publish.put( "summary", "Send request to " + destination );

			List<Map<String, Object>> reqMsgRefs = new ArrayList<>();
			for (RsoketRouteInfo r : destRoutes) {
				String msgKey = ensureRequestMessage( r, messages, schemas );
				reqMsgRefs.add( Map.of( "$ref", "#/components/messages/" + msgKey ) );

			}
			publish.put( "message", oneOfOrSingle( reqMsgRefs ) );

			// subscribe: server -> client (response). fireAndForget는 생략
			Map<String, Object> subscribe = buildSubscribeOperation( destRoutes, messages, schemas );

			// channel level extensions (코드 제너레이터용)
			ch.put(
				"x-rsocket",
				Map.of(
					"destination", destination,
					"routes",
					destRoutes.stream().map( RsoketAsyncApiGenerator::routeMeta ).collect( Collectors.toList() )
				)
			);

			ch.put( "publish", publish );
			if (subscribe != null) { ch.put( "subscribe", subscribe ); }

			channels.put( destination, ch );

		}

		// ---------- Meta ----------
		doc.put( "x-generatedAt", Instant.now().toString() );
		doc.put( "x-format", "rsocket-asyncapi" );

		JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
		return mapper.writerWithDefaultPrettyPrinter().writeValueAsString( doc );

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
			if (hi == null || hi.getResponseBodyInfo().isEmpty()) { continue; }

			HandlerInfo.Info resp = hi.getResponseBodyInfo().values().iterator().next();
			HandlerInfo.Info unwrapped = resp; // parser already unwrapped for method return
			Class<?> t = unwrapped.getType();
			if (t == null || t == Void.class || t == void.class) { continue; }

			hasAnyResponse = true;
			String msgKey = ensureResponseMessage( r, messages, schemas );
			respMsgRefs.add( Map.of( "$ref", "#/components/messages/" + msgKey ) );

		}

		if (! hasAnyResponse) { return null; }

		Map<String, Object> subscribe = new LinkedHashMap<>();
		subscribe.put( "operationId", toOperationId( destRoutes.get( 0 ).getDestination() ) + "_reply" );
		subscribe.put( "summary", "Receive response from " + destRoutes.get( 0 ).getDestination() );
		subscribe.put( "message", oneOfOrSingle( respMsgRefs ) );

		// interaction hint (Mono/Flux)
		// Flux라면 "stream" 성격을 확장 필드로 넣는다.
		boolean anyFlux = destRoutes.stream().anyMatch( r -> "Flux".equals( r.getPublisher() ) );
		subscribe.put( "x-rsocket", Map.of( "stream", anyFlux ) );

		return subscribe;

	}

	private static Object oneOfOrSingle(
		List<Map<String, Object>> refs
	) {

		if (refs == null || refs.isEmpty()) { return Map.of(); }
		if (refs.size() == 1) { return refs.get( 0 ); }
		return Map.of( "oneOf", refs );

	}

	// ---------------------------
	// Channel parameters
	// ---------------------------

	private static Map<String, Object> buildChannelParameters(
		List<RsoketRouteInfo> destRoutes, Map<String, Object> schemas
	) {

		Map<String, Object> params = new LinkedHashMap<>();

		for (RsoketRouteInfo r : destRoutes) {
			RsoketHandlerInfo hi = r.getHandlerInfo();
			if (hi == null) { continue; }

			hi.getDestinationVariableInfo().forEach( (name, info) -> {
				// 동일 키가 여러 번 나오면 첫 번째만 사용 (스키마가 같다고 가정)
				params.putIfAbsent(
					name,
					Map.of(
						"description", "Destination variable: " + name,
						"schema", mapType( info, schemas )
					)
				);

			} );

		}

		return params;

	}

	// ---------------------------
	// Messages
	// ---------------------------

	@SuppressWarnings("unchecked")
	private static String ensureRequestMessage(
		RsoketRouteInfo route,
		Map<String, Object> messages,
		Map<String, Object> schemas
	) {

		String msgKey = safeId( route.getControllerSimpleName() + "_" + route.getMethod() + "_Request" );

		if (messages.containsKey( msgKey )) { return msgKey; }

		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put( "name", msgKey );
		msg.put( "title", route.getControllerSimpleName() + "." + route.getMethod() + " request" );

		RsoketHandlerInfo hi = route.getHandlerInfo();
		Object payloadSchema = buildRequestPayloadSchema( hi, schemas );
		msg.put( "payload", payloadSchema );

		msg.put( "x-rsocket", messageMeta( route, "request" ) );

		messages.put( msgKey, msg );
		return msgKey;

	}

	private static String ensureResponseMessage(
		RsoketRouteInfo route,
		Map<String, Object> messages,
		Map<String, Object> schemas
	) {

		String msgKey = safeId( route.getControllerSimpleName() + "_" + route.getMethod() + "_Response" );

		if (messages.containsKey( msgKey )) { return msgKey; }

		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put( "name", msgKey );
		msg.put( "title", route.getControllerSimpleName() + "." + route.getMethod() + " response" );

		RsoketHandlerInfo hi = route.getHandlerInfo();

		Object payloadSchema = Map.of( "type", "object" );
		if (hi != null && ! hi.getResponseBodyInfo().isEmpty()) {
			HandlerInfo.Info resp = hi.getResponseBodyInfo().values().iterator().next();
			payloadSchema = mapType( resp, schemas );

		}

		msg.put( "payload", payloadSchema );

		msg.put( "x-rsocket", messageMeta( route, "response" ) );

		messages.put( msgKey, msg );
		return msgKey;

	}

	private static Object buildRequestPayloadSchema(
		RsoketHandlerInfo hi, Map<String, Object> schemas
	) {

		if (hi == null || hi.getPayloadInfo().isEmpty()) {
			// payload가 없으면 빈 object로 둔다 (tool 호환성)
			return Map.of( "type", "object", "additionalProperties", false );

		}

		// Spring RSocket은 대부분 단일 @Payload를 기대하므로,
		// 파라미터가 1개면 그 타입 그대로를 payload schema로 둔다.
		if (hi.getPayloadInfo().size() == 1) {
			HandlerInfo.Info p = hi.getPayloadInfo().values().iterator().next();
			Map<String, Object> schema = mapType( p, schemas );

			// 코드 제너레이터가 원본 파라미터 이름을 알 수 있게 확장 정보 추가
			return new LinkedHashMap<>(
				Map.of(
					"allOf",
					List.of(
						schema,
						Map.of( "x-paramName", p.getName() )
					)
				)
			);

		}

		// 여러 개면 object wrapper 로 표현 (실제 바인딩은 프로젝트 규약에 따라 다를 수 있음)
		Map<String, Object> obj = new LinkedHashMap<>();
		obj.put( "type", "object" );
		obj.put( "additionalProperties", false );

		Map<String, Object> props = new LinkedHashMap<>();
		hi.getPayloadInfo().forEach( (name, info) -> props.put( name, mapType( info, schemas ) ) );
		obj.put( "properties", props );

		obj.put( "x-multiParam", true );
		return obj;

	}

	private static Map<String, Object> messageMeta(
		RsoketRouteInfo r, String direction
	) {

		String interaction = "requestResponse";
		if ("Flux".equals( r.getPublisher() )) { interaction = "requestStream"; }

		// fireAndForget 힌트: response type이 Void면 requestResponse라도 응답 없음으로 처리될 수 있음.
		boolean fireAndForget = false;
		RsoketHandlerInfo hi = r.getHandlerInfo();
		if (hi != null && ! hi.getResponseBodyInfo().isEmpty()) {
			HandlerInfo.Info resp = hi.getResponseBodyInfo().values().iterator().next();
			Class<?> t = resp.getType();
			fireAndForget = (t == null || t == Void.class || t == void.class);

		}

		Map<String, Object> m = new LinkedHashMap<>();
		m.put( "direction", direction );
		m.put( "controller", r.getController() );
		m.put( "method", r.getMethod() );
		m.put( "destination", r.getDestination() );
		m.put( "publisher", r.getPublisher() );
		m.put( "interaction", interaction );
		m.put( "fireAndForget", fireAndForget );
		return m;

	}

	private static Map<String, Object> routeMeta(
		RsoketRouteInfo r
	) {

		Map<String, Object> m = new LinkedHashMap<>();
		m.put( "controller", r.getController() );
		m.put( "method", r.getMethod() );
		m.put( "publisher", r.getPublisher() );
		return m;

	}

	// ---------------------------
	// Id helpers
	// ---------------------------

	private static String toOperationId(
		String destination
	) {

		String id = safeId( destination );
		if (id.isEmpty()) { id = "operation"; }
		if (Character.isDigit( id.charAt( 0 ) )) { id = "_" + id; }
		return id;

	}

	private static String safeId(
		String raw
	) {

		if (raw == null) { return ""; }
		return raw.replaceAll( "[^A-Za-z0-9_]", "_" );

	}

	// ---------------------------
	// Schema mapping (copied from RsoketGenerator)
	// ---------------------------

	@SuppressWarnings("unchecked")
	private static Map<String, Object> buildSchema(
		HandlerInfo.Info info, Map<String, Object> schemas
	) {

		Map<String, Object> schema = new LinkedHashMap<>();
		Map<String, Object> properties = new LinkedHashMap<>();
		schema.put( "type", "object" );
		schema.put( "properties", properties );
		schema.put( "additionalProperties", false );

		info.getFields().forEach( (fieldName, fieldInfo) -> {
			Map<String, Object> fieldTypeMap = mapType( fieldInfo, schemas );
			Map<String, Object> property = new LinkedHashMap<>( fieldTypeMap );

			if (fieldInfo.getDescription() != null) {
				property.put( "description", fieldInfo.getDescription() );

			}

			if (fieldInfo.getExample() != null) {
				property.put( "example", fieldInfo.getExample() );

			}

			properties.put( fieldName, property );

		} );

		return schema;

	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapType(
		Info info, Map<String, Object> schemas
	) {

		Class<?> type = info.getType();
		Map<String, Object> schema = new LinkedHashMap<>();
		String typeStr = null;
		String format = null;
		List<String> enumList = new ArrayList<>();
		Map<String, Object> items = new LinkedHashMap<>();

		if (type == String.class) { typeStr = "string"; }

		if (type == Integer.class || type == int.class || type == Long.class || type == long.class || type == Byte.class || type == byte.class || type == Short.class || type == short.class) {
			typeStr = "integer";
		}

		if (type == Double.class || type == double.class || type == Float.class || type == float.class) {
			typeStr = "number";
		}

		if (type == Boolean.class || type == boolean.class) {
			typeStr = "boolean";
		}

		if (type != null && List.class.isAssignableFrom( type )) {
			typeStr = "array";

			Map<String, Object> prevMap = new LinkedHashMap<>();
			BiConsumer<Map<String, Object>, Map<String, Object>> putMap = (parentMap, childMap) -> {
				Object _items = childMap.get( "items" );
				Object _enumList = childMap.get( "enum" );
				Object _format = childMap.get( "format" );

				if (childMap.containsKey( "type" )) {
					parentMap.put( "type", childMap.get( "type" ) );

				} else if (childMap.containsKey( "$ref" )) {
					parentMap.put( "$ref", childMap.get( "$ref" ) );

				}

				if (_items != null) parentMap.put( "items", childMap.get( "items" ) );
				if (_format != null) parentMap.put( "format", childMap.get( "format" ) );
				if (_enumList != null) parentMap.put( "enum", childMap.get( "enum" ) );
			};

			for (int i = 0, len = info.getGenericTypes().size(); i < len; i += 1) {

				if (i == 0) {
					prevMap = mapType( info.getGenericTypes().get( i ), schemas );
					putMap.accept( items, prevMap );
					continue;

				}

				Map<String, Object> _items = (Map<String, Object>) prevMap.get( "items" );
				Map<String, Object> nextMap = mapType( info.getGenericTypes().get( i ), schemas );
				putMap.accept( _items, nextMap );
				prevMap = nextMap;

			}
		}

		if (type == java.time.LocalDateTime.class || type == java.time.LocalDate.class || type == java.time.LocalTime.class || type == java.util.Date.class || type == java.time.Instant.class) {
			typeStr = "string";
			format = type.equals( java.time.LocalDateTime.class ) ? "date-time" : type.equals( java.time.LocalDate.class ) ? "date" : type.equals( java.time.LocalTime.class ) ? "time" : "date-time";
		}

		if (type != null && type.isEnum()) {
			typeStr = "string";
			enumList.addAll( RouteUtil.parserEnumValues( type ) );
		}

		if (type != null && RouteUtil.isPojo( type )) {
			typeStr = "#/components/schemas/" + type.getSimpleName();

			if (schemas.containsKey( type.getSimpleName() ) && schemas.get( type.getSimpleName() ) instanceof Map map) {
				map.putAll( buildSchema( info, schemas ) );

			} else {
				schemas.putIfAbsent( type.getSimpleName(), buildSchema( info, schemas ) );
			}
		}

		if (type == org.bson.types.ObjectId.class) {
			typeStr = "string";
		}

		if (typeStr == null) {
			typeStr = "object";
		}

		if (format != null) { schema.put( "format", format ); }
		if (! enumList.isEmpty()) { schema.put( "enum", enumList ); }
		if (! items.isEmpty()) { schema.put( "items", items ); }

		if (typeStr.startsWith( "#" )) {
			schema.put( "$ref", typeStr );
		} else {
			schema.put( "type", typeStr );
		}

		return schema;
	}
}
