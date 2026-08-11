package com.byeolnaerim.watch.document.swagger;


import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo.Info;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo.LayerPosition;
import com.byeolnaerim.watch.document.swagger.functional.HandlerParser;
import com.byeolnaerim.watch.document.swagger.functional.RouteInfo;
import com.byeolnaerim.watch.document.swagger.functional.RouteParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;
import tools.jackson.databind.json.JsonMapper;


/**
 * Generates Swagger/OpenAPI JSON from parsed route and handler metadata.
 * <p>This class converts {@link RouteInfo} and {@link HandlerInfo} structures
 * into a JSON document containing paths, request bodies, parameters, responses,
 * schemas, tags, and grouped tag metadata.</p>
 */
public class SwaggerGenerator {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

	/**
	 * Generates a Swagger/OpenAPI JSON document from the given route metadata.
	 *
	 * @param routeInfos
	 *            the parsed route metadata
	 * @param customTypeMapper
	 *            custom type mapper. If it mutates the given schema and returns {@code true},
	 *            the default type mapping stops and the mutated schema is used as-is.
	 *
	 * @return the generated Swagger JSON string
	 *
	 * @throws Exception
	 *             if JSON generation fails
	 */
	@SuppressWarnings({
		"unchecked", "rawtypes"
	})
	public static String generateSwaggerJson(
		List<RouteInfo> routeInfos
	)
		throws Exception {

		return generateSwaggerJson( routeInfos, null );

	}

	/**
	 * Generates a Swagger/OpenAPI JSON document from the given route metadata.
	 *
	 * @param routeInfos
	 *            the parsed route metadata
	 * 
	 * @return the generated Swagger JSON string
	 * 
	 * @throws Exception
	 *             if JSON generation fails
	 */
	@SuppressWarnings({
		"unchecked", "rawtypes"
	})
	public static String generateSwaggerJson(
		List<RouteInfo> routeInfos, BiPredicate<Class<?>, Map<String, Object>> customTypeMapper
	)
		throws Exception {

		Map<String, Object> swagger = new LinkedHashMap<>();

		// 기본 정보 설정
		swagger.put( "openapi", "3.0.3" );
		Map<String, Object> swaggerInfo = new LinkedHashMap<>();
		swaggerInfo.put( "title", "Generated API Documentation" );
		swaggerInfo.put( "version", "1.0.0" );
		swaggerInfo.put( "description", "This Swagger documentation was automatically generated using AST.For more details, please refer to webflux-fe-dev-assistant." );
		swagger.put( "info", swaggerInfo );

		Map<String, Object> server = new LinkedHashMap<>();
		server.put( "url", "http://localhost:8795" );
		server.put( "description", "Local server" );
		swagger.put( "servers", List.of( server ) );

		// Paths 및 Components 설정
		Map<String, LinkedHashMap> paths = new LinkedHashMap<>();
		Map<String, Object> components = new LinkedHashMap<>();
		Map<String, Object> schemas = new LinkedHashMap<>();

		Map<String, Object> parameters = new LinkedHashMap<>();
		List<Map<String, Object>> tags = new ArrayList<>();
		Set<String> tagNames = new HashSet<>();
		List<Map<String, Object>> tagGroups = new ArrayList<>();
		Map<String, Set<String>> groupHierarchy = new LinkedHashMap<>();

		routeInfos.stream().filter( e -> e.getHandlerInfo() != null ).forEach( routeInfo -> {

			String url = routeInfo.getUrl();
			String httpMethod = routeInfo.getHttpMethod().toLowerCase();
			String tagName = routeInfo.getParentGroup() + "/" + routeInfo.getChildGroup();

			// Paths 설정
			paths.putIfAbsent( url, new LinkedHashMap<>() );
			Map<String, Object> methodDetails = new LinkedHashMap<>();
			methodDetails.put( "summary", "API for " + routeInfo.getEndpoint() );
			methodDetails.put( "description", "Generated endpoint for " + url );
			// childGroup이 null인 경우 기본값 설정
			methodDetails.put( "tags", List.of( tagName ) );
			methodDetails.put( "security", generateSecurity( routeInfo.getSecuritySchemes() ) );

			// Request Body 설정
			if (! routeInfo.getHandlerInfo().getRequestBodyInfo().isEmpty()) {
				methodDetails.put( "requestBody", generateRequestBody( routeInfo.getHandlerInfo().getRequestBodyInfo(), schemas, customTypeMapper ) );

			}

			// Parameters 설정 (Query, Path)
			List<Map<String, Object>> allParams = new ArrayList<>();
			allParams.addAll( generateParameters( routeInfo.getHandlerInfo().getQueryStringInfo(), parameters, schemas, customTypeMapper ) );
			allParams.addAll( generateParameters( routeInfo.getHandlerInfo().getPathVariableInfo(), parameters, schemas, customTypeMapper ) );

			if (! allParams.isEmpty()) {
				methodDetails.put( "parameters", allParams );

			}

			// Response 설정
			if (! routeInfo.getHandlerInfo().getResponseInfoByStatusCode().isEmpty()) {
				methodDetails.put( "responses", generateResponses( routeInfo.getHandlerInfo().getResponseInfoByStatusCode(), schemas, customTypeMapper ) );

			} else if (! routeInfo.getHandlerInfo().getResponseBodyInfo().isEmpty()) {
				methodDetails.put( "responses", generateResponses( routeInfo.getHandlerInfo().getResponseBodyInfo(), schemas, customTypeMapper ) );

			}

			((Map) paths.get( url )).put( httpMethod, methodDetails );

			// Tags 생성
			if (tagNames.add( tagName )) {
				Map<String, Object> tag = new LinkedHashMap<>();
				tag.put( "name", tagName );
				tag.put( "description", "API for " + tagName );
				tags.add( tag );

			}

			// 그룹 계층 생성
			// if (! routeInfo.getChildGroup().trim().isBlank()) {
			groupHierarchy.computeIfAbsent( routeInfo.getParentGroup(), k -> new LinkedHashSet<>() ).add( tagName );

			// }

		} );

		// x-tagGroups 생성
		for (Map.Entry<String, Set<String>> entry : groupHierarchy.entrySet()) {
			Map<String, Object> tagGroup = new LinkedHashMap<>();
			tagGroup.put( "name", entry.getKey() );
			tagGroup.put( "tags", new ArrayList<>( entry.getValue() ) );
			tagGroups.add( tagGroup );

		}

		components.put( "schemas", schemas );

		components.put( "parameters", parameters );
		swagger.put( "paths", paths );
		swagger.put( "components", components );
		swagger.put( "tags", tags );
		swagger.put( "x-tagGroups", tagGroups );

		// Swagger JSON 출력
		return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString( swagger );

	}

	private static Map<String, Object> generateRequestBody(
		Map<String, HandlerInfo.Info> requestBodyInfo, Map<String, Object> schemas, BiPredicate<Class<?>, Map<String, Object>> customTypeMapper

	) {

		Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "required", true );

		Map<String, Object> content = new LinkedHashMap<>();
		requestBodyInfo.forEach( (className, info) -> {
			String schemaName = className;
			ensureSchema( schemaName, info, schemas, customTypeMapper );

			content.put( "application/json", Map.of( "schema", Map.of( "$ref", "#/components/schemas/" + schemaName ) ) );

		} );

		requestBody.put( "content", content );
		return requestBody;

	}

	private static List<Map<String, Object>> generateParameters(
		Map<String, HandlerInfo.Info> paramInfo, Map<String, Object> parameters, Map<String, Object> schemas, BiPredicate<Class<?>, Map<String, Object>> customTypeMapper


	) {

		return paramInfo.values().stream().map( info -> {
			String in = info.getPosition().equals( LayerPosition.REQUEST_PATH ) ? "path" : info.getPosition().equals( LayerPosition.HEADER ) ? "header"
				: info.getPosition().equals( LayerPosition.COOKIE ) ? "cookie" : "query";
			Map<String, Object> param = new LinkedHashMap<>();
			param.put( "name", info.getName() );
			param.put( "in", in );
			param.put( "required", info.getRequired() );
			param.put( "schema", mapType( info, schemas, customTypeMapper ) );

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
		Map<String, HandlerInfo.Info> responseInfoByStatusCode, Map<String, Object> schemas, BiPredicate<Class<?>, Map<String, Object>> customTypeMapper


	) {

		Map<String, Object> responses = new LinkedHashMap<>();

		responseInfoByStatusCode.forEach( (statusCode, info) -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put( "description", generateResponseDescription( statusCode ) );

			if (info != null) {
				Map<String, Object> responseContent = new LinkedHashMap<>();
				Map<String, Object> responseSchema = mapType( info, schemas, customTypeMapper );

				responseContent.put( "application/json", Map.of( "schema", responseSchema ) );
				response.put( "content", responseContent );

				if (isConcreteWrapperSchema( info )) {
					String responseSchemaName = resolveSchemaName( info );
					ensureSchema( responseSchemaName, info, schemas, customTypeMapper );

				}

			}

			responses.put( statusCode, response );

		} );

		return responses;

	}

	private static String generateResponseDescription(
		String statusCode
	) {

		return switch (statusCode) {
			case "200" -> "Successful response";
			case "201" -> "Created";
			case "202" -> "Accepted";
			case "204" -> "No content";
			case "400" -> "Bad request";
			case "401" -> "Unauthorized";
			case "403" -> "Forbidden";
			case "404" -> "Not found";
			case "409" -> "Conflict";
			case "422" -> "Unprocessable entity";
			case "500" -> "Internal server error";
			default -> "Response";

		};

	}

	private static boolean isConcreteWrapperSchema(
		Info info
	) {

		Class<?> type = (info != null) ? info.getType() : null;

		return info != null && type != null && ! info.getGenericTypes().isEmpty() && info.getFields().containsKey( "data" ) && ! java.util.Collection.class
			.isAssignableFrom( type ) && ! java.util.Map.class.isAssignableFrom( type ) && ! java.util.Optional.class.equals( type ) && ! Flux.class.isAssignableFrom( type ) && ! Mono.class
				.isAssignableFrom( type );

	}

	private static String resolveSchemaName(
		Info info
	) {

		if (info == null) {
			return "Object";

		}

		Class<?> type = info.getType();

		if (type == null || type == Object.class) {
			return (info.getTypeRef() != null && info.getTypeRef().getSimpleName() != null)
				? info.getTypeRef().getSimpleName()
				: "Object";

		}

		if (isConcreteWrapperSchema( info )) {
			StringBuilder nameBuilder = new StringBuilder( type.getSimpleName() );

			for (Info genericInfo : info.getGenericTypes()) {
				nameBuilder.append( "Of" ).append( buildGenericSchemaSuffix( genericInfo ) );

			}

			return nameBuilder.toString();

		}

		return type.getSimpleName();

	}

	private static String buildGenericSchemaSuffix(
		Info info
	) {

		if (info == null || info.getType() == null) {
			return "Object";

		}

		Class<?> type = info.getType();

		if (Flux.class.isAssignableFrom( type ) || List.class.isAssignableFrom( type )) {

			if (! info.getGenericTypes().isEmpty()) {
				return "ListOf" + buildGenericSchemaSuffix( info.getGenericTypes().get( 0 ) );

			}

			return "ListOfObject";

		}

		if (Mono.class.isAssignableFrom( type )) {

			if (! info.getGenericTypes().isEmpty()) {
				return buildGenericSchemaSuffix( info.getGenericTypes().get( 0 ) );

			}

			return "Object";

		}

		if (! info.getGenericTypes().isEmpty()) {
			StringBuilder nestedBuilder = new StringBuilder( type.getSimpleName() );

			for (Info genericInfo : info.getGenericTypes()) {
				nestedBuilder.append( "Of" ).append( buildGenericSchemaSuffix( genericInfo ) );

			}

			return nestedBuilder.toString();

		}

		return type.getSimpleName();

	}

	private static Map<String, Object> buildSchema(
		HandlerInfo.Info info, Map<String, Object> schemas, BiPredicate<Class<?>, Map<String, Object>> customTypeMapper
	) {

		Map<String, Object> schema = new LinkedHashMap<>();
		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();

		schema.put( "type", "object" );
		schema.put( "properties", properties );
		schema.put( "additionalProperties", false );

		info.getFields().forEach( (fieldName, fieldInfo) -> {

			Map<String, Object> fieldTypeMap = mapType( fieldInfo, schemas, customTypeMapper );
			Map<String, Object> property = new LinkedHashMap<>( fieldTypeMap );

			if (fieldInfo.getDescription() != null) {
				property.put( "description", fieldInfo.getDescription() );

			}

			if (fieldInfo.getExample() != null) {
				property.put( "example", fieldInfo.getExample() );

			}

			properties.put( fieldName, property );

			if (Boolean.TRUE.equals( fieldInfo.getRequired() )) {
				required.add( fieldName );

			}

		} );

		if (! required.isEmpty()) {
			schema.put( "required", required );

		}

		return schema;

	}

	private static void ensureSchema(
		String schemaName, HandlerInfo.Info info, Map<String, Object> schemas, BiPredicate<Class<?>, Map<String, Object>> customTypeMapper
	) {

		if (schemas.containsKey( schemaName )) { return; }

		Map<String, Object> schema = new LinkedHashMap<>();
		schemas.put( schemaName, schema );
		schema.putAll( buildSchema( info, schemas, customTypeMapper ) );

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
		Info info, Map<String, Object> schemas, BiPredicate<Class<?>, Map<String, Object>> customTypeMapper
	) {

		Class<?> type = info.getType();
		Map<String, Object> schema = new LinkedHashMap<>();
		String typeStr = null;
		String format = null;
		List<String> enumList = new ArrayList<>();

		if (customTypeMapper != null && customTypeMapper.test( type, schema )) {
			return schema;

		}

		if (type == null || type == Object.class) {
			schema.put( "type", "object" );
			return schema;

		}

		// Optional<T> 는 내부 T 로 내린다.
		if (Optional.class.isAssignableFrom( type )) {

			if (! info.getGenericTypes().isEmpty()) { return mapType( info.getGenericTypes().get( 0 ), schemas, customTypeMapper ); }

			schema.put( "type", "object" );
			return schema;

		}

		// Mono<T> 는 응답 스키마에서 바깥 래퍼로 취급하지 않고 T 로 내린다.
		if (Mono.class.isAssignableFrom( type )) {

			if (! info.getGenericTypes().isEmpty()) { return mapType( info.getGenericTypes().get( 0 ), schemas, customTypeMapper ); }

			schema.put( "type", "object" );
			return schema;

		}

		// Map<K, V> 는 object + additionalProperties 로 본다.
		if (Map.class.isAssignableFrom( type )) {
			schema.put( "type", "object" );

			if (info.getGenericTypes().size() >= 2) {
				schema.put( "additionalProperties", mapType( info.getGenericTypes().get( 1 ), schemas, customTypeMapper ) );

			} else {
				schema.put( "additionalProperties", true );

			}

			return schema;

		}

		// byte[] / Byte[] 는 바이너리 문자열로 본다.
		if (type == byte[].class || type == Byte[].class) {
			schema.put( "type", "string" );
			schema.put( "format", "byte" );
			return schema;

		}

		// Collection / Flux 는 모두 array 로 본다.
		if (java.util.Collection.class.isAssignableFrom( type ) || Flux.class.isAssignableFrom( type )) {
			schema.put( "type", "array" );

			if (! info.getGenericTypes().isEmpty()) {
				schema.put( "items", mapType( info.getGenericTypes().get( 0 ), schemas, customTypeMapper ) );

			} else {
				schema.put( "items", Map.of( "type", "object" ) );

			}

			return schema;

		}

		if (type == String.class) {
			typeStr = "string";

		}

		// OSS 기본 정책: long 은 TS number 로 바로 내리지 않고 string 으로 보수적으로 매핑
		if (type == Long.class || type == long.class) {
			typeStr = "integer";
			format = "int64";

		}

		if (type == Integer.class || type == int.class) {
			typeStr = "integer";
			format = "int32";

		}

		if (type == Byte.class || type == byte.class || type == Short.class || type == short.class) {
			typeStr = "integer";

		}

		if (type == Double.class || type == double.class) {
			typeStr = "number";
			format = "double";

		}

		if (type == Float.class || type == float.class) {
			typeStr = "number";
			format = "float";

		}

		// OSS 기본 정책: BigDecimal / BigInteger 는 string 으로 보수적으로 매핑
		if (type == BigDecimal.class || type == BigInteger.class) {
			typeStr = "string";

		}

		if (type == Boolean.class || type == boolean.class) {
			typeStr = "boolean";

		}

		if (type == java.time.LocalDate.class) {
			typeStr = "string";
			format = "date";

		}

		if (type == java.time.LocalDateTime.class || type == java.util.Date.class || type == java.time.Instant.class || type == java.time.OffsetDateTime.class || type == java.time.ZonedDateTime.class) {
			typeStr = "string";
			format = "date-time";

		}

		if (type == java.time.LocalTime.class) {
			typeStr = "string";
			format = "time";

		}

		if (type == java.util.UUID.class) {
			typeStr = "string";
			format = "uuid";

		}

		if (type.isEnum()) {
			typeStr = "string";
			enumList.addAll( RouteUtil.parserEnumValues( type ) );

		}

		if ((type.getSimpleName() != null && type.getSimpleName().contains( "ObjectId" ))) {
			typeStr = "string";

		}

		if (RouteUtil.isPojo( type )) {
			String schemaName = resolveSchemaName( info );
			typeStr = "#/components/schemas/" + schemaName;
			ensureSchema( schemaName, info, schemas, customTypeMapper );

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

		if (typeStr.startsWith( "#" )) {
			schema.put( "$ref", typeStr );

		} else {
			schema.put( "type", typeStr );

		}

		return schema;

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
