package com.byeolnaerim.watch.swagger;


import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.swagger.HandlerInfo.Info;
import com.byeolnaerim.watch.swagger.HandlerInfo.LayerPosition;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;
import tools.jackson.databind.json.JsonMapper;


public class SwaggerGenerator {

public static String generateSwaggerJson(
		List<RouteInfo> routeInfos
	)
		throws Exception {

		// 기존 호출 유지 (기본: merge)
		return generateSwaggerJson( routeInfos, false );

	}

	@SuppressWarnings({
		"unchecked", "rawtypes"
	})
	public static String generateSwaggerJson(
		List<RouteInfo> routeInfos, boolean mergeSchemasOnConflict
	)
		throws Exception {

		Map<String, Object> swagger = new LinkedHashMap<>();

		// 기본 정보 설정
		swagger.put( "openapi", "3.0.3" );
		swagger
			.put(
				"info",
				Map
					.of(
						"title",
						"Generated API Documentation",
						"version",
						"1.0.0",
						"description",
						"This Swagger documentation was automatically generated using AST.For more details, please refer to webflux-functional-endpoint-auto-code-generator-support."
					)
			);
		swagger
			.put(
				"servers",
				List
					.of(
						Map
							.of(
								"url",
								"http://localhost:8795",
								"description",
								"Local server"
							)
					)
			);

		// Paths 및 Components 설정
		Map<String, LinkedHashMap> paths = new LinkedHashMap<>();
		Map<String, Object> components = new LinkedHashMap<>();
		// Map<String, Object> schemas = new LinkedHashMap<>();
		SchemaStore schemaStore = new SchemaStore( mergeSchemasOnConflict );
		Map<String, Object> parameters = new LinkedHashMap<>();
		List<Map<String, Object>> tags = new ArrayList<>();
		List<Map<String, Object>> tagGroups = new ArrayList<>();
		Map<String, List<String>> groupHierarchy = new LinkedHashMap<>();

		routeInfos.stream().filter( e -> e.getHandlerInfo() != null ).forEach( routeInfo -> {

			String url = routeInfo.getUrl();
			String httpMethod = routeInfo.getHttpMethod().toLowerCase();

			// Paths 설정
			paths.putIfAbsent( url, new LinkedHashMap<>() );
			Map<String, Object> methodDetails = new LinkedHashMap<>();
			methodDetails.put( "summary", "API for " + routeInfo.getEndpoint() );
			methodDetails.put( "description", "Generated endpoint for " + url );
			// childGroup이 null인 경우 기본값 설정
			methodDetails.put( "tags", List.of( routeInfo.getParentGroup() + "/" + routeInfo.getChildGroup() ) );
			methodDetails.put( "security", generateSecurity( routeInfo.getSecuritySchemes() ) );

			// Request Body 설정
			if (! routeInfo.getHandlerInfo().getRequestBodyInfo().isEmpty()) {
				// methodDetails.put( "requestBody", generateRequestBody(
				// routeInfo.getHandlerInfo().getRequestBodyInfo(), schemas ) );
				methodDetails.put( "requestBody", generateRequestBody( routeInfo.getHandlerInfo().getRequestBodyInfo(), schemaStore, url ) );
			}

			// Parameters 설정 (Query, Path)
			List<Map<String, Object>> allParams = new ArrayList<>();
			// allParams.addAll( generateParameters( routeInfo.getHandlerInfo().getQueryStringInfo(),
			// parameters, schemas ) );
			// allParams.addAll( generateParameters( routeInfo.getHandlerInfo().getPathVariableInfo(),
			// parameters, schemas ) );
			allParams.addAll( generateParameters( routeInfo.getHandlerInfo().getQueryStringInfo(), parameters, schemaStore, url ) );
			allParams.addAll( generateParameters( routeInfo.getHandlerInfo().getPathVariableInfo(), parameters, schemaStore, url ) );


			if (! allParams.isEmpty()) {
				methodDetails.put( "parameters", allParams );

			}

			// Response 설정
			if (! routeInfo.getHandlerInfo().getResponseBodyInfo().isEmpty()) {
				// methodDetails.put( "responses", generateResponses(
				// routeInfo.getHandlerInfo().getResponseBodyInfo(), schemas ) );
				methodDetails.put( "responses", generateResponses( routeInfo.getHandlerInfo().getResponseBodyInfo(), schemaStore, url ) );
			}

			((Map) paths.get( url )).put( httpMethod, methodDetails );

			// Tags 생성
			Map<String, Object> tag = Map
				.of(
					"name",
					routeInfo.getParentGroup() + "/" + routeInfo.getChildGroup(),
					"description",
					"API for " + routeInfo.getParentGroup() + "/" + routeInfo.getChildGroup()
				);

			if (tags.stream().noneMatch( t -> t.get( "name" ).equals( routeInfo.getChildGroup() ) )) {
				tags.add( tag );

			}

			// 그룹 계층 생성
			// if (! routeInfo.getChildGroup().trim().isBlank()) {
			groupHierarchy.computeIfAbsent( routeInfo.getParentGroup(), k -> new ArrayList<>() ).add( routeInfo.getParentGroup() + "/" + routeInfo.getChildGroup() );

			// }

		} );

		// x-tagGroups 생성
		for (Map.Entry<String, List<String>> entry : groupHierarchy.entrySet()) {
			tagGroups
				.add(
					Map
						.of(
							"name",
							entry.getKey(),
							"tags",
							entry.getValue()
						)
				);

		}

		// components.put( "schemas", schemas );
		components.put( "schemas", schemaStore.schemas );
		components.put( "parameters", parameters );
		swagger.put( "paths", paths );
		swagger.put( "components", components );
		swagger.put( "tags", tags );
		swagger.put( "x-tagGroups", tagGroups );

		// Swagger JSON 출력
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString( swagger );

	}

	private static Map<String, Object> generateRequestBody(
		Map<String, HandlerInfo.Info> requestBodyInfo, // Map<String, Object> schemas,
		SchemaStore schemaStore, String originUrl
	) {

		Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "required", true );

		Map<String, Object> content = new LinkedHashMap<>();
		requestBodyInfo.forEach( (className, info) -> {
			String schemaName = className;
			// schemas.putIfAbsent( schemaName, buildSchema( info, schemas ) );
			schemaStore.putSchema( schemaName, buildSchema( info, schemaStore, originUrl ), originUrl );
			content.put( "application/json", Map.of( "schema", Map.of( "$ref", "#/components/schemas/" + schemaName ) ) );

		} );

		requestBody.put( "content", content );
		return requestBody;

	}

	private static List<Map<String, Object>> generateParameters(
		Map<String, HandlerInfo.Info> paramInfo, Map<String, Object> parameters,
		// Map<String, Object> schemas
		SchemaStore schemaStore, String originUrl
	) {

		return paramInfo.values().stream().map( info -> {
			String in = info.getPosition().equals( LayerPosition.REQUEST_PATH ) ? "path" : info.getPosition().equals( LayerPosition.HEADER ) ? "header"
				: info.getPosition().equals( LayerPosition.COOKIE ) ? "cookie" : "query";
			Map<String, Object> param = new LinkedHashMap<>();
			param.put( "name", info.getName() );
			param.put( "in", in );
			param.put( "required", info.getRequired() );
			// param.put( "schema", mapType( info, schemas ) );
			param.put( "schema", mapType( info, schemaStore, originUrl ) );
			param.put( "description", info.getDescription() );

			if (info.getDefaultValue() != null) {
				param.put( "example", info.getDefaultValue() );

			}

			String paramName = in + "." + info.getName();
			parameters.put( paramName, param );
			return param;

		} ).collect( Collectors.toList() );

	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> generateResponses(
		Map<String, HandlerInfo.Info> responseBodyInfo,
		// Map<String, Object> schemas
		SchemaStore schemaStore, String originUrl
	) {

		Map<String, Object> responses = new LinkedHashMap<>();
		Map<String, Object> responseContent = new LinkedHashMap<>();

		responseBodyInfo.forEach( (className, info) -> {

			// if (schemas.containsKey( className ) && schemas.get( className ) instanceof Map map) {
			// map.putAll( buildSchema( info, schemas ) );
			//
			// } else {
			// schemas.putIfAbsent( className, buildSchema( info, schemas ) );
			//
			// }
			schemaStore.putSchema( className, buildSchema( info, schemaStore, originUrl ), originUrl );


			responseContent.put( "application/json", Map.of( "schema", Map.of( "$ref", "#/components/schemas/" + className ) ) );

			if (! info.getGenericTypes().isEmpty()) {
				info.getGenericTypes().forEach( e -> {
					String key = e.getType().getSimpleName();

					// if (schemas.containsKey( key ) && schemas.get( key ) instanceof Map map) {
					// map.putAll( buildSchema( info, schemas ) );
					//
					// } else {
					// schemas.putIfAbsent( key, buildSchema( e, schemas ) );
					//
					// }
					schemaStore.putSchema( key, buildSchema( e, schemaStore, originUrl ), originUrl );

				} );

			}

		} );

		responses
			.put(
				"200",
				Map
					.of(
						"description",
						"Successful response",
						"content",
						responseContent
					)
			);

		return responses;

	}

	private static Map<String, Object> buildSchema(
		HandlerInfo.Info info,
		// Map<String, Object> schemas
		SchemaStore schemaStore, String originUrl
	) {

		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put( "type", "object" );
		schema.put( "properties", new LinkedHashMap<>() );
		schema.put( "additionalProperties", false );
		info.getFields().forEach( (fieldName, fieldInfo) -> {

			// Map<String, Object> property = new LinkedHashMap<>();
			// Map<String, Object> fieldTypeMap = mapType( fieldInfo, schemas );
			Map<String, Object> fieldTypeMap = mapType( fieldInfo, schemaStore, originUrl );

			Map<String, Object> property = new LinkedHashMap<>( fieldTypeMap );

			// property.put( "type", fieldTypeMap.get( "type" ) );

			// if (fieldTypeMap.containsKey( "enum" )) {
			// property.put( "enum", fieldTypeMap.get( "enum" ) );
			//
			// }

			if (fieldInfo.getDescription() != null) {
				property.put( "description", fieldInfo.getDescription() );

			}

			if (fieldInfo.getExample() != null) {
				property.put( "example", fieldInfo.getExample() );

			}

			((Map<String, Object>) schema.get( "properties" )).put( fieldName, property );


		} );

		return schema;

	}

	private static List<Map<String, ArrayList<Object>>> generateSecurity(
		List<String> securitySchemes
	) {

		return securitySchemes
			.stream()
			.map( scheme -> Map.of( scheme, new ArrayList<>() ) )
			.collect( Collectors.toList() );

	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapType(
		Info info,
		// Map<String, Object> schemas
		SchemaStore schemaStore, String originUrl
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

		if (type == Double.class || type == double.class || type == Float.class || type == float.class) {
			typeStr = "number";

		}

		if (List.class.isAssignableFrom( type )) {
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

				/**
				 * 중첩구조 처리
				 * "schema": {
				 * "type": "array",
				 * "items": {
				 * "type": "array",
				 * "items": {
				 * "$ref": "#/components/schemas/CustomObject"
				 * }
				 * }
				 * }
				 */
				if (i == 0) {
					// prevMap = mapType( info.getGenericTypes().get( i ), schemas );
					prevMap = mapType( info.getGenericTypes().get( i ), schemaStore, originUrl );
					putMap.accept( items, prevMap );
					continue;

				}

				@SuppressWarnings("unchecked")
				Map<String, Object> _items = (Map<String, Object>) prevMap.get( "items" );
				// Map<String, Object> nextMap = mapType( info.getGenericTypes().get( i ), schemas );
				Map<String, Object> nextMap = mapType( info.getGenericTypes().get( i ), schemaStore, originUrl );

				putMap.accept( _items, nextMap );
				prevMap = nextMap;

			}

		}

		if (type == java.time.LocalDateTime.class || type == java.time.LocalDate.class || type == java.time.LocalTime.class || type == java.util.Date.class || type == java.time.Instant.class) {
			typeStr = "string"; // Swagger에서는 날짜와 시간을 string으로 표현
			format = type.equals( java.time.LocalDateTime.class ) ? "date-time" : type.equals( java.time.LocalDate.class ) ? "date" : type.equals( java.time.LocalTime.class ) ? "time" : "date-time";

		}

		if (type.isEnum()) {
			typeStr = "string"; // Enum도 Swagger에서는 기본적으로 문자열로 매핑
			enumList.addAll( RouteUtil.parserEnumValues( type ) );

		}

		if (RouteUtil.isPojo( type )) {
			typeStr = "#/components/schemas/" + type.getSimpleName(); // 사용자 정의 클래스는 Schema로 참조
			// System.out.println( info );

			// if (schemas.containsKey( type.getSimpleName() ) && schemas.get( type.getSimpleName() ) instanceof
			// Map map) {
			// map.putAll( buildSchema( info, schemas ) );
			//
			// } else {
			// schemas.putIfAbsent( type.getSimpleName(), buildSchema( info, schemas ) );
			//
			// }
			schemaStore.putSchema( type.getSimpleName(), buildSchema( info, schemaStore, originUrl ), originUrl );
		}

		if (type == org.bson.types.ObjectId.class) {
			typeStr = "string";

		}

		if (typeStr == null)
			typeStr = "object"; // 기본적으로 기타 객체 타입은 object로 처리

		if (format != null)
			schema.put( "format", format );

		if (! enumList.isEmpty())
			schema.put( "enum", enumList );

		if (items.size() != 0)
			schema.put( "items", items );

		if (typeStr.startsWith( "#" )) {
			schema.put( "$ref", typeStr );

		} else {
			schema.put( "type", typeStr );

		}

		return schema;

	}


	private static final class SchemaStore {

		private final boolean mergeOnConflict;

		private final Map<String, Object> schemas = new LinkedHashMap<>();

		private final Map<String, Set<String>> origins = new LinkedHashMap<>();

		private SchemaStore(
							boolean mergeOnConflict
		) {

			this.mergeOnConflict = mergeOnConflict;

		}

		@SuppressWarnings("unchecked")
		void putSchema(
			String name, Map<String, Object> incoming, String originUrl
		) {

			Set<String> prevOriginsSnapshot = origins.containsKey( name )
				? new LinkedHashSet<>( origins.get( name ) )
				: Set.of();

			origins.computeIfAbsent( name, k -> new LinkedHashSet<>() ).add( originUrl );

			Object existingObj = schemas.get( name );

			if (existingObj == null) {
				schemas.put( name, incoming );
				return;

			}

			if (! (existingObj instanceof Map)) {
				warnSchemaConflict( name, prevOriginsSnapshot, originUrl );

				if (! mergeOnConflict) {
					schemas.put( name, incoming );

				}

				return;

			}

			Map<String, Object> existing = (Map<String, Object>) existingObj;

			if (existing.equals( incoming )) {
				return; // 동일 정의면 조용히 통과

			}

			warnSchemaConflict( name, prevOriginsSnapshot, originUrl );

			if (mergeOnConflict) {
				mergeSchemaMaps( name, existing, incoming, prevOriginsSnapshot, originUrl );

			} else {
				schemas.put( name, incoming ); // 덮어쓰기

			}

		}

		private void warnSchemaConflict(
			String schemaName, Set<String> existingOrigins, String newOrigin
		) {

			System.err.println( "⚠️ [SwaggerGenerator] Schema가 충돌합니다. : '" + schemaName + "'" );
			System.err.println( "   - 기존 정의 URL: " + (existingOrigins.isEmpty() ? "(unknown)" : existingOrigins) );
			System.err.println( "   - 새 정의 URL: " + newOrigin );
			System.err.println( "   - 설정: mergeSchemasOnConflict=" + mergeOnConflict + " (" + (mergeOnConflict ? "merge(병합)" : "overwrite(새 정의로 덮어쓰기)") + ")" );

		}

		@SuppressWarnings("unchecked")
		private void mergeSchemaMaps(
			String schemaName, Map<String, Object> target, Map<String, Object> incoming, Set<String> existingOrigins, String newOrigin
		) {

			// properties만 “간단 merge”: 없는 필드만 추가, 같은 필드인데 내용 다르면 경고
			Object tPropsObj = target.get( "properties" );
			Object iPropsObj = incoming.get( "properties" );

			if (tPropsObj instanceof Map tProps && iPropsObj instanceof Map iProps) {

				for (Object k : iProps.keySet()) {
					String propName = String.valueOf( k );
					Object tVal = tProps.get( propName );
					Object iVal = iProps.get( propName );

					if (tVal == null) {
						tProps.put( propName, iVal );
						continue;

					}

					if (! Objects.equals( tVal, iVal )) {
						System.err.println( "[SwaggerGenerator] Property가 충돌합니다. : '" + schemaName + "." + propName + "'" );
						System.err.println( "   - 기존 정의 URL: " + (existingOrigins.isEmpty() ? "(unknown)" : existingOrigins) );
						System.err.println( "   - 새 정의 URL: " + newOrigin );
						System.err.println( "   - merge 모드이므로 기존 값과 새 정의 값을 합칩니다.\n" );

					}

				}

			} else if (tPropsObj == null && iPropsObj instanceof Map) {
				target.put( "properties", new LinkedHashMap<>( (Map<String, Object>) iPropsObj ) );

			}

			// 나머지 키는 “간단 처리”: 없으면 넣고, 있으면 다르면 경고만 (기존 유지)
			for (Map.Entry<String, Object> e : incoming.entrySet()) {
				String key = e.getKey();
				if ("properties".equals( key ))
					continue;

				if (! target.containsKey( key )) {
					target.put( key, e.getValue() );

				} else if (! Objects.equals( target.get( key ), e.getValue() )) {
					System.err.println( "[SwaggerGenerator] Schema가 충돌합니다. : '" + schemaName + "." + key + "'" );
					System.err.println( "   - 기존 정의 URL: " + (existingOrigins.isEmpty() ? "(unknown)" : existingOrigins) );
					System.err.println( "   - 새 정의 URL: " + newOrigin );
					System.err.println( "   - merge 모드이므로 기존 값과 새 정의 값을 합칩니다.\n" );

				}

			}

		}

	}
	public static void main(
		String[] args
	)
		throws Exception {

		// MainRouter.java 의 실제 경로를 지정
		File sourceDir = new File( "src/main/java" );

		Launcher launcher = new Launcher();
		launcher.addInputResource( sourceDir.getPath() );
		launcher.getEnvironment().setAutoImports( true );
		launcher.getEnvironment().setNoClasspath( true );
		launcher.buildModel();

		CtModel model = launcher.getModel();
		Set<String> HTTP_METHODS = new HashSet<>( Arrays.asList( "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "TRACE" ) );

		// @Bean + RouterFunction<ServerResponse> 메서드 찾기
		List<CtMethod<?>> routerMethods = model
			.getElements(
				(CtMethod<?> m) -> m.getAnnotations().stream().anyMatch( a -> a.getAnnotationType().getSimpleName().equals( "Bean" ) ) && m.getType().getSimpleName().contains( "RouterFunction" )
			);

		List<RouteInfo> routeInfos = new ArrayList<>();

		for (CtMethod<?> routerMethod : routerMethods) {
			String routeMethodName = routerMethod.getSimpleName();
			System.out.println( "=== Parsing routes in method: " + routeMethodName + " ===" );

			// 해당 메서드 내 GET/POST/PUT/DELETE 호출 모두 찾기
			@SuppressWarnings("rawtypes")
			List<CtInvocation> httpCalls = routerMethod
				.getElements( new TypeFilter<>( CtInvocation.class ) )
				.stream()
				.filter( inv -> HTTP_METHODS.contains( inv.getExecutable().getSimpleName() ) )
				.toList();
			HandlerParser handlerParser = new HandlerParser();

			for (CtInvocation<?> httpCall : httpCalls) {
				RouteInfo info = RouteParser.extractRouteInfoFromHttpCall( httpCall, routeMethodName );

				if (info != null) {

					// if (routeMethodName.equals( "object" )) {
					routeInfos.add( info );

					info
						.setHandlerInfo(
							handlerParser
								.parseHandler(
									info.getHandlerInfoCtExpression(),
									RouteUtil.convertPathToMethodName( info.getUrl() )
								)
						);

					// }

				}

			}

		}

		// 테스트 데이터

		// RouteInfo와 HandlerInfo를 채워넣는 로직 필요
		System.out.println( generateSwaggerJson( routeInfos ) );

	}

}
