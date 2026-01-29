package com.byeolnaerim.watch.document.swagger.mvc;


import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.byeolnaerim.watch.document.anntation.SelectedRequestParam;
import com.byeolnaerim.watch.document.anntation.SelectedRequestPath;
import com.byeolnaerim.watch.document.anntation.SelectedResponseBody;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo;
import com.byeolnaerim.watch.document.swagger.functional.RouteInfo;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo.LayerPosition;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;


public final class MvcParser {

	// -----------------------------
	// Spring mapping annotation set
	// -----------------------------
	private static final Set<String> MAPPING_ANN_SIMPLE_NAMES = Set
		.of(
			"RequestMapping",
			"GetMapping",
			"PostMapping",
			"PutMapping",
			"DeleteMapping",
			"PatchMapping"
		);

	private static final Map<String, String> SHORTCUT_HTTP_METHOD = Map
		.of(
			"GetMapping",
			"GET",
			"PostMapping",
			"POST",
			"PutMapping",
			"PUT",
			"DeleteMapping",
			"DELETE",
			"PatchMapping",
			"PATCH"
		);

	private static final List<String> DEFAULT_REQUEST_MAPPING_METHODS = List.of( "GET", "POST", "PUT", "PATCH", "DELETE" );

	private MvcParser() {}

	// ============================
	// Public entry
	// ============================
	public static List<RouteInfo> parseRoutes(
		CtModel model
	) {

		if (model == null)
			return List.of();

		List<RouteInfo> routes = new ArrayList<>();

		// Controller 후보: @RestController/@Controller 있거나, mapping 메서드가 있는 타입
		Collection<CtType<?>> allTypes = model.getAllTypes();

		for (CtType<?> type : allTypes) {
			if (! isControllerLike( type ))
				continue;

			// class-level @RequestMapping
			CtAnnotation<?> classReqMapping = findAnn( type, "RequestMapping" );
			List<String> classPaths = (classReqMapping != null) ? extractPaths( classReqMapping ) : List.of( "" );
			List<String> classConsumes = (classReqMapping != null) ? extractStringArrayAttr( classReqMapping, "consumes" ) : List.of();
			List<String> classProduces = (classReqMapping != null) ? extractStringArrayAttr( classReqMapping, "produces" ) : List.of();

			for (CtMethod<?> method : safeMethods( type )) {
				if (! hasAnyMappingAnn( method ))
					continue;

				Mapping mapping = extractMapping( method );
				if (mapping.httpMethods.isEmpty())
					continue;

				// method consumes/produces 우선, 없으면 class-level fallback
				List<String> consumes = ! mapping.consumes.isEmpty() ? mapping.consumes : classConsumes;
				List<String> produces = ! mapping.produces.isEmpty() ? mapping.produces : classProduces;

				List<String> methodPaths = mapping.paths.isEmpty() ? List.of( "" ) : mapping.paths;

				for (String base : classPaths) {

					for (String p : methodPaths) {
						String fullPath = normalizeJoinPath( base, p );

						for (String httpMethod : mapping.httpMethods) {
							RouteInfo ri = new RouteInfo();
							ri.setHttpMethod( httpMethod );
							ri.setUrl( fullPath );
							ri.setEndpoint( extractEndpoint( fullPath ) );
							ri.setAcceptMediaTypes( consumes );

							// tag grouping: 첫 세그먼트 = parentGroup, 나머지 = childGroup
							String parent = extractFirstSegment( fullPath );

							if (parent == null || parent.isBlank()) {
								parent = type.getSimpleName();

							}

							ri.setParentGroup( parent );
							ri.setChildGroup( extractChildGroup( fullPath, parent ) );

							HandlerInfo hi = parseHandlerInfo( method, method.getFactory(), httpMethod, consumes, produces );
							ri.setHandlerInfo( hi );

							routes.add( ri );

						}

					}

				}

			}

		}

		return routes;

	}

	// ============================
	// Mapping extraction
	// ============================
	private static Mapping extractMapping(
		CtMethod<?> method
	) {

		Mapping m = new Mapping();

		// 우선 shortcut annotations
		for (CtAnnotation<?> ann : method.getAnnotations()) {
			String sn = ann.getAnnotationType().getSimpleName();
			if (! MAPPING_ANN_SIMPLE_NAMES.contains( sn ))
				continue;

			// paths
			m.paths.addAll( extractPaths( ann ) );
			// consumes/produces
			m.consumes.addAll( extractStringArrayAttr( ann, "consumes" ) );
			m.produces.addAll( extractStringArrayAttr( ann, "produces" ) );

			// http methods
			if (SHORTCUT_HTTP_METHOD.containsKey( sn )) {
				m.httpMethods.add( SHORTCUT_HTTP_METHOD.get( sn ) );

			} else if ("RequestMapping".equals( sn )) {
				List<String> fromAttr = extractRequestMappingMethods( ann );
				if (! fromAttr.isEmpty())
					m.httpMethods.addAll( fromAttr );

			}

		}

		// RequestMapping에 method 속성이 비어있으면 전체로 간주(너무 보수적이면 여기만 바꾸면 됨)
		if (m.httpMethods.isEmpty() && findAnn( method, "RequestMapping" ) != null) {
			m.httpMethods.addAll( DEFAULT_REQUEST_MAPPING_METHODS );

		}

		// paths가 아예 없으면 빈 path
		m.paths = m.paths.stream().distinct().collect( Collectors.toList() );
		m.httpMethods = m.httpMethods.stream().distinct().collect( Collectors.toList() );
		m.consumes = m.consumes.stream().distinct().collect( Collectors.toList() );
		m.produces = m.produces.stream().distinct().collect( Collectors.toList() );

		return m;

	}

	private static List<String> extractRequestMappingMethods(
		CtAnnotation<?> ann
	) {

		CtExpression<?> expr = ann.getValues().get( "method" );
		if (expr == null)
			return List.of();

		List<String> out = new ArrayList<>();

		if (expr instanceof CtFieldAccess<?> fa) {
			out.add( extractEnumConstantName( fa ) );

		} else if (expr instanceof CtNewArray<?> na) {

			for (CtExpression<?> e : na.getElements()) {
				if (e instanceof CtFieldAccess<?> fa2)
					out.add( extractEnumConstantName( fa2 ) );

			}

		}

		return out.stream().filter( Objects::nonNull ).map( String::toUpperCase ).distinct().collect( Collectors.toList() );

	}

	private static String extractEnumConstantName(
		CtFieldAccess<?> fa
	) {

		if (fa == null || fa.getVariable() == null)
			return null;
		return fa.getVariable().getSimpleName();

	}

	private static List<String> extractPaths(
		CtAnnotation<?> ann
	) {

		// path 우선, 없으면 value
		List<String> paths = extractStringArrayAttr( ann, "path" );
		if (! paths.isEmpty())
			return paths;
		return extractStringArrayAttr( ann, "value" );

	}

	private static List<String> extractStringArrayAttr(
		CtAnnotation<?> ann, String key
	) {

		if (ann == null)
			return List.of();
		CtExpression<?> expr = ann.getValues().get( key );
		if (expr == null)
			return List.of();

		List<String> out = new ArrayList<>();

		if (expr instanceof CtLiteral<?> lit) {
			Object v = lit.getValue();
			if (v instanceof String s)
				out.add( s );

		} else if (expr instanceof CtNewArray<?> na) {

			for (CtExpression<?> e : na.getElements()) {
				if (e instanceof CtLiteral<?> lit2 && lit2.getValue() instanceof String s2)
					out.add( s2 );
				else if (e != null)
					out.add( e.toString() ); // 상수(MediaType.APPLICATION_JSON_VALUE 등) fallback

			}

		} else {
			out.add( expr.toString() );

		}

		return out.stream().map( String::trim ).filter( s -> ! s.isBlank() ).collect( Collectors.toList() );

	}

	// ============================
	// HandlerInfo extraction
	// ============================
	private static HandlerInfo parseHandlerInfo(
		CtMethod<?> method, Factory factory, String httpMethod, List<String> consumes, List<String> produces
	) {

		HandlerInfo handler = new HandlerInfo();
		// produces를 HandlerInfo에 세팅(기존 필드가 있음) :contentReference[oaicite:7]{index=7}
		handler.setContentMediaTypes( produces != null ? produces : List.of() );

		// 1) params
		for (CtParameter<?> p : method.getParameters()) {
			if (shouldIgnoreFrameworkParam( p ))
				continue;

			ParamClassify classify = classifyParam( p, httpMethod, consumes );
			if (classify.kind == ParamKind.IGNORED)
				continue;

			if (classify.kind == ParamKind.MODEL_ATTRIBUTE_FLATTEN) {
				flattenPojoToQueryParams( p, handler );
				continue;

			}

			HandlerInfo.Info info = buildParamInfoFromTypeRef( classify.typeRef );
			info.setName( classify.name );
			info.setDefaultValue( classify.defaultValue );
			info.setRequired( classify.required );
			info.setNullable( classify.nullable );
			info.setPosition( classify.position );

			// 커스텀 @RequestParam/@RequestPath가 붙으면 최우선 override
			applyCustomParamOverrideIfPresent( p, info );

			// 분배
			switch (info.getPosition()) {
				case REQUEST_PATH -> handler.getPathVariableInfo().put( info.getName(), info );
				case REQUEST_STRING -> handler.getQueryStringInfo().put( info.getName(), info );
				case HEADER -> handler.getHeaderParams().put( info.getName(), info );
				case COOKIE -> handler.getCookieParams().put( info.getName(), info );
				case REQUEST_BODY -> {
					// requestBody는 schema key를 className으로 쓰는 구조 :contentReference[oaicite:8]{index=8}
					String key = schemaKey( info );
					handler.getRequestBodyInfo().put( key, info );

				}
				default -> handler.getQueryStringInfo().put( info.getName(), info );

			}

			// request/response body는 POJO field 확장
			if (info.getPosition() == LayerPosition.REQUEST_BODY) {
				parseClassFieldsSafely( classify.typeRef, info, new HashSet<>() );

			}

		}

		// 2) response (custom @ResponseBody 최우선)
		HandlerInfo.Info resp = buildResponseBodyInfo( method, factory );

		if (resp != null) {
			String key = schemaKey( resp );
			handler.getResponseBodyInfo().put( key, resp );

		}

		return handler;

	}

	private static HandlerInfo.Info buildResponseBodyInfo(
		CtMethod<?> method, Factory factory
	) {

		// custom @ResponseBody(type=...) 우선 :contentReference[oaicite:9]{index=9}
		CtAnnotation<?> custom = method.getAnnotation( factory.Type().createReference( SelectedResponseBody.class ) );

		if (custom != null && custom.getActualAnnotation() instanceof SelectedResponseBody rb) {
			Class<?> typeClass = rb.type();

			if (typeClass != null && typeClass != Void.class && typeClass != void.class) {
				CtTypeReference<?> tr = factory.Type().createReference( typeClass );
				HandlerInfo.Info info = buildParamInfoFromTypeRef( tr );
				info.setType( typeClass );
				info.setTypeRef( tr );
				info.setNullable( rb.nullable() );
				info.setPosition( LayerPosition.RESPONSE_BODY );
				parseClassFieldsSafely( tr, info, new HashSet<>() );
				return unwrapContainerResponse( info );

			}

		}

		// 기본: return type 기반
		CtTypeReference<?> ret = method.getType();
		if (ret == null)
			return null;

		String q = ret.getQualifiedName();
		if ("void".equals( q ) || "java.lang.Void".equals( q ) || "Void".equals( ret.getSimpleName() ))
			return null;

		HandlerInfo.Info info = buildParamInfoFromTypeRef( ret );
		info.setPosition( LayerPosition.RESPONSE_BODY );
		info = unwrapContainerResponse( info );

		parseClassFieldsSafely( info.getTypeRef(), info, new HashSet<>() );
		return info;

	}

	private static HandlerInfo.Info unwrapContainerResponse(
		HandlerInfo.Info info
	) {

		if (info == null)
			return null;

		String typeName = (info.getType() != null) ? info.getType().getName() : "";

		// Reactor
		if (("reactor.core.publisher.Mono".equals( typeName ) || "reactor.core.publisher.Flux".equals( typeName )) && ! info.getGenericTypes().isEmpty()) {
			return unwrapContainerResponse( info.getGenericTypes().get( 0 ) );

		}

		// ResponseEntity
		if (("org.springframework.http.ResponseEntity".equals( typeName )) && ! info.getGenericTypes().isEmpty()) { return unwrapContainerResponse( info.getGenericTypes().get( 0 ) ); }

		// Optional
		if (("java.util.Optional".equals( typeName )) && ! info.getGenericTypes().isEmpty()) { return unwrapContainerResponse( info.getGenericTypes().get( 0 ) ); }

		return info;

	}

	// ----------------------------
	// Param classification
	// ----------------------------
	private static ParamClassify classifyParam(
		CtParameter<?> p, String httpMethod, List<String> consumes
	) {

		ParamClassify c = new ParamClassify();

		// default
		c.name = p.getSimpleName();
		c.required = Boolean.TRUE;
		c.nullable = Boolean.FALSE;
		c.defaultValue = null;
		c.typeRef = p.getType();
		c.position = LayerPosition.REQUEST_STRING;
		c.kind = ParamKind.QUERY;

		// Spring annotations
		boolean isRequestBody = hasAnn( p, "RequestBody" );
		boolean isPathVar = hasAnn( p, "PathVariable" );
		boolean isRequestParam = hasAnn( p, "RequestParam" );
		boolean isHeader = hasAnn( p, "RequestHeader" );
		boolean isCookie = hasAnn( p, "CookieValue" );
		boolean isModelAttr = hasAnn( p, "ModelAttribute" );

		if (isRequestBody) {
			c.kind = ParamKind.BODY;
			c.position = LayerPosition.REQUEST_BODY;
			// Mono<T>/Flux<T> request body 케이스
			c.typeRef = unwrapTypeRefIfContainer( p.getType() );
			return c;

		}

		if (isPathVar) {
			c.kind = ParamKind.PATH;
			c.position = LayerPosition.REQUEST_PATH;
			applySpringLikeNameRequiredDefault( p, "PathVariable", c );
			return c;

		}

		if (isRequestParam) {
			c.kind = ParamKind.QUERY;
			c.position = LayerPosition.REQUEST_STRING;
			applySpringLikeNameRequiredDefault( p, "RequestParam", c );
			return c;

		}

		if (isHeader) {
			c.kind = ParamKind.HEADER;
			c.position = LayerPosition.HEADER;
			applySpringLikeNameRequiredDefault( p, "RequestHeader", c );
			return c;

		}

		if (isCookie) {
			c.kind = ParamKind.COOKIE;
			c.position = LayerPosition.COOKIE;
			applySpringLikeNameRequiredDefault( p, "CookieValue", c );
			return c;

		}

		// annotation 없을 때: 복합 타입이면 ModelAttribute로 보고 query로 flatten
		if (isModelAttr || isComplexPojo( p.getType() )) {
			// POST/PUT/PATCH + consumes json이면 body로 보는 옵션도 가능하지만,
			// Spring MVC 기본 바인딩(ModelAttribute) 존중해서 우선 flatten 쪽으로 둠.
			c.kind = ParamKind.MODEL_ATTRIBUTE_FLATTEN;
			c.position = LayerPosition.REQUEST_STRING;
			return c;

		}

		// 단순 타입은 query로 둠
		return c;

	}

	private static void applySpringLikeNameRequiredDefault(
		CtParameter<?> p, String annSimpleName, ParamClassify c
	) {

		CtAnnotation<?> ann = findAnn( p, annSimpleName );
		if (ann == null)
			return;

		// name/value
		String name = firstNonBlank(
			extractStringAttr( ann, "name" ),
			extractStringAttr( ann, "value" )
		);
		if (name != null && ! name.isBlank())
			c.name = name;

		// required
		Boolean req = extractBooleanAttr( ann, "required" );
		if (req != null)
			c.required = req;

		// defaultValue
		String def = extractStringAttr( ann, "defaultValue" );

		if (def != null && ! def.isBlank() && ! "ValueConstants.DEFAULT_NONE".equals( def )) {
			c.defaultValue = def;
			// defaultValue 있으면 required=false로 보는 쪽이 swagger에서 일반적
			c.required = Boolean.FALSE;

		}

	}

	// 커스텀 override: 최우선(RequestParam/RequestPath) :contentReference[oaicite:10]{index=10}
	// :contentReference[oaicite:11]{index=11}
	private static void applyCustomParamOverrideIfPresent(
		CtParameter<?> p, HandlerInfo.Info info
	) {

		if (p == null || info == null)
			return;

		CtAnnotation<?> q = p.getAnnotation( p.getFactory().Type().createReference( SelectedRequestParam.class ) );

		if (q != null && q.getActualAnnotation() instanceof SelectedRequestParam rp) {
			overrideParamInfoWithCustom( rp.key(), rp.defaultValue(), rp.required(), rp.nullable(), rp.type(), info );
			info.setPosition( LayerPosition.REQUEST_STRING );

		}

		CtAnnotation<?> pv = p.getAnnotation( p.getFactory().Type().createReference( SelectedRequestPath.class ) );

		if (pv != null && pv.getActualAnnotation() instanceof SelectedRequestPath rpath) {
			overrideParamInfoWithCustom( rpath.key(), rpath.defaultValue(), rpath.required(), rpath.nullable(), rpath.type(), info );
			info.setPosition( LayerPosition.REQUEST_PATH );

		}

	}

	private static void overrideParamInfoWithCustom(
		String key, String defaultValue, boolean required, boolean nullable, Class<?> type, HandlerInfo.Info info
	) {

		if (key != null && ! key.isBlank())
			info.setName( key );
		if (defaultValue != null && ! defaultValue.isBlank())
			info.setDefaultValue( defaultValue );
		info.setRequired( required );
		info.setNullable( nullable );

		if (type != null && type != Void.class && type != void.class) {
			info.setType( type );

		}

	}

	// ----------------------------
	// Flatten ModelAttribute
	// ----------------------------
	private static void flattenPojoToQueryParams(
		CtParameter<?> p, HandlerInfo handler
	) {

		if (p == null || handler == null)
			return;

		CtTypeReference<?> tr = p.getType();
		if (tr == null)
			return;

		HandlerInfo.Info root = buildParamInfoFromTypeRef( tr );
		parseClassFieldsSafely( tr, root, new HashSet<>() );

		for (Map.Entry<String, HandlerInfo.Info> e : root.getFields().entrySet()) {
			String fieldName = e.getKey();
			HandlerInfo.Info fi = e.getValue();
			fi.setPosition( LayerPosition.REQUEST_STRING );
			if (fi.getRequired() == null)
				fi.setRequired( Boolean.FALSE );

			String key = fieldName;

			if (handler.getQueryStringInfo().containsKey( key )) {
				key = p.getSimpleName() + "." + fieldName;

			}

			fi.setName( key );
			handler.getQueryStringInfo().put( key, fi );

		}

	}

	// ============================
	// Type/Field parsing (minimal copy)
	// ============================
	private static HandlerInfo.Info buildParamInfoFromTypeRef(
		CtTypeReference<?> typeRef
	) {

		HandlerInfo.Info info = new HandlerInfo.Info();
		info.setTypeRef( typeRef );

		Class<?> clazz = loadClassFromTypeReference( typeRef );
		info.setType( clazz );
		info.setName( typeRef != null ? typeRef.getSimpleName() : "Object" );
		info.setRequired( Boolean.TRUE );
		info.setNullable( Boolean.FALSE );

		// generics
		if (typeRef != null && typeRef.getActualTypeArguments() != null && ! typeRef.getActualTypeArguments().isEmpty()) {
			List<HandlerInfo.Info> generics = new ArrayList<>();

			for (CtTypeReference<?> ga : typeRef.getActualTypeArguments()) {
				HandlerInfo.Info gi = buildParamInfoFromTypeRef( ga );
				gi.setPosition( LayerPosition.GENERIC );
				generics.add( gi );

			}

			info.setGenericTypes( generics );

		}

		return info;

	}

	private static CtTypeReference<?> unwrapTypeRefIfContainer(
		CtTypeReference<?> tr
	) {

		if (tr == null)
			return null;
		String qn = tr.getQualifiedName();
		if (qn == null)
			return tr;

		if ("reactor.core.publisher.Mono".equals( qn ) || "reactor.core.publisher.Flux".equals( qn ) || "org.springframework.http.ResponseEntity".equals( qn ) || "java.util.Optional".equals( qn )) {

			if (tr.getActualTypeArguments() != null && ! tr.getActualTypeArguments().isEmpty()) { return unwrapTypeRefIfContainer( tr.getActualTypeArguments().get( 0 ) ); }

		}

		return tr;

	}

	private static void parseClassFieldsSafely(
		CtTypeReference<?> typeRef, HandlerInfo.Info target, Set<String> visited
	) {

		if (typeRef == null || target == null)
			return;

		// List/Map 같은 컨테이너면 내부 제너릭을 파싱하는게 더 의미있음
		String qn = typeRef.getQualifiedName();

		if (qn != null && (qn.startsWith( "java.util.List" ) || qn.startsWith( "java.util.Set" ))) {

			if (typeRef.getActualTypeArguments() != null && ! typeRef.getActualTypeArguments().isEmpty()) {
				CtTypeReference<?> inner = typeRef.getActualTypeArguments().get( 0 );
				// inner schema 확장
				HandlerInfo.Info innerInfo = buildParamInfoFromTypeRef( inner );
				parseClassFieldsSafely( inner, innerInfo, visited );
				return;

			}

		}

		if (! isComplexPojo( typeRef ))
			return;

		String key = typeRef.getQualifiedName();
		if (key == null)
			key = typeRef.getSimpleName();
		if (key == null)
			key = "Object";
		if (visited.contains( key ))
			return;
		visited.add( key );

		CtType<?> decl = typeRef.getTypeDeclaration();
		if (decl == null)
			return;

		// record 지원
		if (decl instanceof CtRecord rec) {

			for (CtRecordComponent rc : rec.getRecordComponents()) {
				if (rc == null || rc.getType() == null)
					continue;
				String fn = rc.getSimpleName();
				HandlerInfo.Info fi = buildParamInfoFromTypeRef( rc.getType() );
				fi.setName( fn );
				fi.setPosition( LayerPosition.FIELDS );
				target.getFields().put( fn, fi );

			}

			return;

		}

		for (CtField<?> f : decl.getFields()) {
			if (f == null || f.getType() == null)
				continue;
			if (f.isStatic())
				continue;

			String fn = f.getSimpleName();
			HandlerInfo.Info fi = buildParamInfoFromTypeRef( f.getType() );
			fi.setName( fn );
			fi.setPosition( LayerPosition.FIELDS );
			target.getFields().put( fn, fi );

		}

	}

	private static boolean isComplexPojo(
		CtTypeReference<?> tr
	) {

		if (tr == null)
			return false;

		String qn = tr.getQualifiedName();
		if (qn == null)
			return false;

		// primitive/boxed/String/Number/Date/UUID 등은 제외
		Class<?> cls = loadClassFromTypeReference( tr );

		if (cls != Object.class) {
			if (cls.isPrimitive())
				return false;
			if (cls == String.class)
				return false;
			if (Number.class.isAssignableFrom( cls ))
				return false;
			if (Boolean.class == cls || Character.class == cls)
				return false;
			if (Enum.class.isAssignableFrom( cls ))
				return false;

		}

		// java.* 기본타입 제외
		if (qn.startsWith( "java." ) || qn.startsWith( "javax." ) || qn.startsWith( "jakarta." )) {
			// 단, Map/List처럼 제너릭이 meaningful한 경우는 위에서 별도 처리
			return false;

		}

		return true;

	}

	private static Class<?> loadClassFromTypeReference(
		CtTypeReference<?> tr
	) {

		if (tr == null)
			return Object.class;

		String qn = tr.getQualifiedName();
		if (qn == null)
			return Object.class;

		// primitives
		return switch (qn) {
			case "boolean" -> boolean.class;
			case "byte" -> byte.class;
			case "short" -> short.class;
			case "int" -> int.class;
			case "long" -> long.class;
			case "float" -> float.class;
			case "double" -> double.class;
			case "char" -> char.class;
			default -> {

				try {
					yield Class.forName( qn );

				} catch (Throwable ignore) {
					yield Object.class;

				}

			}

		};

	}

	private static String schemaKey(
		HandlerInfo.Info info
	) {

		if (info == null)
			return "Object";
		if (info.getType() != null && info.getType() != Object.class)
			return info.getType().getSimpleName();
		if (info.getTypeRef() != null)
			return info.getTypeRef().getSimpleName();
		return "Object";

	}

	// ============================
	// Small utils
	// ============================
	private static boolean isControllerLike(
		CtType<?> type
	) {

		if (type == null)
			return false;
		if (hasAnn( type, "RestController" ) || hasAnn( type, "Controller" ))
			return true;

		// mapping method만 있어도 controller로 취급
		for (CtMethod<?> m : safeMethods( type )) {
			if (hasAnyMappingAnn( m ))
				return true;

		}

		return false;

	}

	private static List<CtMethod<?>> safeMethods(
		CtType<?> type
	) {

		try {
			return new ArrayList<>( type.getMethods() );

		} catch (Throwable t) {
			return List.of();

		}

	}

	private static boolean hasAnyMappingAnn(
		CtMethod<?> m
	) {

		if (m == null)
			return false;
		return m.getAnnotations().stream().anyMatch( a -> MAPPING_ANN_SIMPLE_NAMES.contains( a.getAnnotationType().getSimpleName() ) );

	}

	private static boolean shouldIgnoreFrameworkParam(
		CtParameter<?> p
	) {

		if (p == null || p.getType() == null)
			return true;
		String qn = p.getType().getQualifiedName();
		if (qn == null)
			return false;

		// 흔한 프레임워크/컨텍스트 객체들은 swagger param으로 빼기
		return qn.equals( "javax.servlet.http.HttpServletRequest" ) || qn.equals( "javax.servlet.http.HttpServletResponse" ) || qn.equals( "jakarta.servlet.http.HttpServletRequest" ) || qn
			.equals( "jakarta.servlet.http.HttpServletResponse" ) || qn.equals( "org.springframework.http.server.reactive.ServerHttpRequest" ) || qn
				.equals( "org.springframework.http.server.reactive.ServerHttpResponse" ) || qn
					.equals( "org.springframework.web.server.ServerWebExchange" ) || qn.equals( "java.security.Principal" ) || qn.equals( "org.springframework.security.core.Authentication" );

	}

	private static String normalizeJoinPath(
		String a, String b
	) {

		String left = (a == null) ? "" : a.trim();
		String right = (b == null) ? "" : b.trim();

		if (left.isBlank())
			left = "";
		if (right.isBlank())
			right = "";

		String joined;
		if (left.endsWith( "/" ) && right.startsWith( "/" ))
			joined = left + right.substring( 1 );
		else if (! left.endsWith( "/" ) && ! right.startsWith( "/" ))
			joined = left + "/" + right;
		else
			joined = left + right;

		joined = joined.replaceAll( "/{2,}", "/" );
		if (! joined.startsWith( "/" ))
			joined = "/" + joined;
		if (joined.length() > 1 && joined.endsWith( "/" ))
			joined = joined.substring( 0, joined.length() - 1 );
		return joined;

	}

	private static String extractEndpoint(
		String url
	) {

		if (url == null || url.isBlank())
			return "";
		String[] parts = url.split( "/" );

		for (int i = parts.length - 1; i >= 0; i--) {
			if (parts[i] != null && ! parts[i].isBlank())
				return parts[i];

		}

		return "";

	}

	private static String extractFirstSegment(
		String url
	) {

		if (url == null)
			return "";
		String[] parts = url.split( "/" );

		for (String p : parts) {
			if (p != null && ! p.isBlank())
				return p;

		}

		return "";

	}

	private static String extractChildGroup(
		String url, String parent
	) {

		if (url == null)
			return "";
		String normalized = url.startsWith( "/" ) ? url.substring( 1 ) : url;

		if (parent != null && ! parent.isBlank() && normalized.startsWith( parent )) {
			String rest = normalized.substring( parent.length() );
			rest = rest.startsWith( "/" ) ? rest.substring( 1 ) : rest;
			if (rest.isBlank())
				return parent;
			return parent + "-" + rest.replace( "/", "-" );

		}

		return parent != null ? parent : "";

	}

	private static boolean hasAnn(
		CtElement e, String simpleName
	) {

		return findAnn( e, simpleName ) != null;

	}

	private static CtAnnotation<?> findAnn(
		CtElement e, String simpleName
	) {

		if (e == null || simpleName == null)
			return null;

		for (CtAnnotation<?> a : e.getAnnotations()) {
			if (simpleName.equals( a.getAnnotationType().getSimpleName() ))
				return a;

		}

		return null;

	}

	private static String extractStringAttr(
		CtAnnotation<?> ann, String key
	) {

		if (ann == null)
			return null;
		CtExpression<?> expr = ann.getValues().get( key );
		if (expr == null)
			return null;

		if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof String s)
			return s;
		return expr.toString();

	}

	private static Boolean extractBooleanAttr(
		CtAnnotation<?> ann, String key
	) {

		if (ann == null)
			return null;
		CtExpression<?> expr = ann.getValues().get( key );
		if (expr == null)
			return null;

		if (expr instanceof CtLiteral<?> lit && lit.getValue() instanceof Boolean b)
			return b;

		// "true"/"false" string fallback
		try {
			return Boolean.parseBoolean( expr.toString() );

		} catch (Throwable t) {
			return null;

		}

	}

	private static String firstNonBlank(
		String... xs
	) {

		if (xs == null)
			return null;

		for (String x : xs) {
			if (x != null && ! x.isBlank())
				return x;

		}

		return null;

	}

	// ============================
	// Internal classes
	// ============================
	private static final class Mapping {

		private List<String> paths = new ArrayList<>();

		private List<String> httpMethods = new ArrayList<>();

		private List<String> consumes = new ArrayList<>();

		private List<String> produces = new ArrayList<>();

	}

	private enum ParamKind {
		QUERY, PATH, BODY, HEADER, COOKIE, MODEL_ATTRIBUTE_FLATTEN, IGNORED
	}

	private static final class ParamClassify {

		private ParamKind kind;

		private String name;

		private String defaultValue;

		private Boolean required;

		private Boolean nullable;

		private CtTypeReference<?> typeRef;

		private LayerPosition position;

	}

	// ============================
	// Manual test (optional)
	// ============================
	public static void main(
		String[] args
	) {

		File sourceDir = new File( "src/main/java" );

		Launcher launcher = new Launcher();
		launcher.addInputResource( sourceDir.getPath() );
		launcher.getEnvironment().setAutoImports( true );
		launcher.getEnvironment().setNoClasspath( true );
		launcher.buildModel();

		CtModel model = launcher.getModel();
		List<RouteInfo> routes = parseRoutes( model );
		System.out.println( "MVC routes: " + routes.size() );

	}

}
