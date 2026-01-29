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
 * RsoketRouteInfo 목록을 swagger.json "비슷한" 형태의 JSON으로 출력.
 * - routes: destination 기준으로 엔드포인트 나열
 * - components.schemas: POJO/record 타입들은 components.schemas 아래에 정의
 */
public class RsoketGenerator {

	public static String generateRsoketJson(
		List<RsoketRouteInfo> routes
	)
		throws Exception {

		Map<String, Object> root = new LinkedHashMap<>();
		root.put( "generatedAt", Instant.now().toString() );
		root.put( "format", "rsoket" );

		Map<String, Object> components = new LinkedHashMap<>();
		Map<String, Object> schemas = new LinkedHashMap<>();
		components.put( "schemas", schemas );
		root.put( "components", components );

		// controllers group
		Map<String, List<RsoketRouteInfo>> byController = routes
			.stream()
			.collect( Collectors.groupingBy( RsoketRouteInfo::getController, LinkedHashMap::new, Collectors.toList() ) );

		List<Map<String, Object>> controllers = new ArrayList<>();

		for (Map.Entry<String, List<RsoketRouteInfo>> e : byController.entrySet()) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put( "controller", e.getKey() );
			c.put( "routes", e.getValue().stream().map( r -> routeToMap( r, schemas ) ).collect( Collectors.toList() ) );
			controllers.add( c );

		}

		root.put( "controllers", controllers );

		JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
		return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString( root );

	}

	private static Map<String, Object> routeToMap(
		RsoketRouteInfo route, Map<String, Object> schemas
	) {

		Map<String, Object> m = new LinkedHashMap<>();
		m.put( "destination", route.getDestination() );
		m.put( "method", route.getMethod() );
		m.put( "publisher", route.getPublisher() );

		RsoketHandlerInfo hi = route.getHandlerInfo();

		if (hi == null) { return m; }

		// payload params
		List<Map<String, Object>> payload = new ArrayList<>();
		hi.getPayloadInfo().forEach( (name, info) -> {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put( "name", name );
			p.put( "schema", mapType( info, schemas ) );
			payload.add( p );

		} );
		m.put( "payload", payload );

		// destination variables
		List<Map<String, Object>> destVars = new ArrayList<>();
		hi.getDestinationVariableInfo().forEach( (name, info) -> {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put( "name", name );
			p.put( "schema", mapType( info, schemas ) );
			destVars.add( p );

		} );
		m.put( "destinationVariables", destVars );

		// response (최초 1개만)
		if (! hi.getResponseBodyInfo().isEmpty()) {
			HandlerInfo.Info r = hi.getResponseBodyInfo().values().iterator().next();
			m.put( "response", mapType( r, schemas ) );

		}

		return m;

	}

	// ---- schema building (copied from SwaggerGenerator, slightly trimmed) ----
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

		if (type == String.class) {
			typeStr = "string";

		}

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

				if (_items != null)
					parentMap.put( "items", childMap.get( "items" ) );
				if (_format != null)
					parentMap.put( "format", childMap.get( "format" ) );
				if (_enumList != null)
					parentMap.put( "enum", childMap.get( "enum" ) );

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

		if (format != null) {
			schema.put( "format", format );

		}

		if (! enumList.isEmpty()) {
			schema.put( "enum", enumList );

		}

		if (! items.isEmpty()) {
			schema.put( "items", items );

		}

		if (typeStr.startsWith( "#" )) {
			schema.put( "$ref", typeStr );

		} else {
			schema.put( "type", typeStr );

		}

		return schema;

	}

}
