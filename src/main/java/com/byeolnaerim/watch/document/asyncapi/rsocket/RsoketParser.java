package com.byeolnaerim.watch.document.asyncapi.rsocket;


import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.anntation.SelectedResponseBody;
import com.byeolnaerim.watch.document.common.RsoketTypeInfoParser;
import com.byeolnaerim.watch.document.common.TypeInfoParser;
import spoon.Launcher;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;


/**
 * Parses Spring RSocket endpoints and converts them into {@link RsoketRouteInfo} metadata.
 * <p>This parser scans {@code @Controller} classes, collects {@code @MessageMapping} methods
 * whose return type is {@code Mono} or {@code Flux}, extracts destination mappings,
 * request payload metadata, destination-variable metadata, and response-body metadata.</p>
 * <p>Response parsing gives priority to
 * {@link com.byeolnaerim.watch.document.anntation.SelectedResponseBody}
 * when present.</p>
 */
/**
 * Parses Spring RSocket endpoints and converts them into {@link RsoketRouteInfo} metadata.
 * <p>This parser scans {@code @Controller} classes, collects {@code @MessageMapping} methods
 * whose return type is {@code Mono} or {@code Flux}, extracts destination mappings,
 * request payload metadata, destination-variable metadata, and response-body metadata.</p>
 * <p>Response parsing gives priority to
 * {@link com.byeolnaerim.watch.document.anntation.SelectedResponseBody}
 * when present.</p>
 */
public class RsoketParser {

	private final Set<String> processedTypes = new HashSet<>();

	private final Map<String, CtType<?>> externalTypes;

	private final RsoketTypeInfoParser typeInfoParser;

	public RsoketParser() {

		this.externalTypes = Map.of();
		this.typeInfoParser = new RsoketTypeInfoParser( this.externalTypes );

	}

	public RsoketParser(
						Map<String, CtType<?>> externalTypes
	) {

		this.externalTypes = externalTypes != null ? externalTypes : Map.of();
		this.typeInfoParser = new RsoketTypeInfoParser( this.externalTypes );

	}

	/**
	 * Scans the given source directory with Spoon and extracts parsed RSocket route metadata.
	 *
	 * @param watchDirectory
	 *            the source directory to scan
	 * 
	 * @return the extracted RSocket routes
	 */
	public List<RsoketRouteInfo> extractRsoketRoutes(
		String watchDirectory
	) {

		Launcher launcher = new Launcher();
		launcher.addInputResource( watchDirectory );
		launcher.getEnvironment().setAutoImports( true );
		launcher.getEnvironment().setNoClasspath( true );
		launcher.buildModel();

		return extractRsoketRoutes( launcher.getModel().getAllTypes() );

	}

	/**
	 * Extracts parsed RSocket route metadata from the given Spoon types.
	 *
	 * @param allTypes
	 *            all types from the Spoon model
	 * 
	 * @return the extracted RSocket routes
	 */
	public List<RsoketRouteInfo> extractRsoketRoutes(
		Iterable<CtType<?>> allTypes
	) {

		List<RsoketRouteInfo> out = new ArrayList<>();

		for (CtType<?> controllerType : allTypes) {

			if (! hasAnnotation( controllerType, "Controller" )) {
				continue;

			}

			CtAnnotation<?> classMessageMapping = getAnnotationBySimpleName( controllerType, "MessageMapping" );
			List<String> classMappings = extractStringArrayFromAnnotation( classMessageMapping );

			if (classMappings.isEmpty()) {
				classMappings = List.of( "" );

			}

			// @MessageMapping + Mono/Flux 메소드만 수집
			List<CtMethod<?>> messageMethods = controllerType
				.getMethods()
				.stream()
				.filter( m -> hasAnnotation( m, "MessageMapping" ) )
				.filter( m -> isMonoOrFlux( m.getType() ) )
				.toList();

			if (messageMethods.isEmpty()) {
				continue;

			}

			for (CtMethod<?> m : messageMethods) {
				CtAnnotation<?> mmAnn = getAnnotationBySimpleName( m, "MessageMapping" );
				List<String> methodMappings = extractStringArrayFromAnnotation( mmAnn );

				if (methodMappings.isEmpty()) {
					methodMappings = List.of( "" );

				}

				String publisher = m.getType() != null ? m.getType().getSimpleName() : "";
				RsoketHandlerInfo handlerInfo = parseMessageMappingMethod( m );

				for (String cm : classMappings) {

					for (String mm : methodMappings) {
						RsoketRouteInfo info = new RsoketRouteInfo();
						info.setController( controllerType.getQualifiedName() );
						info.setControllerSimpleName( controllerType.getSimpleName() );
						info.setMethod( m.getSimpleName() );
						info.setPublisher( publisher );
						info.setDestination( joinDestination( cm, mm ) );
						info.setHandlerInfo( handlerInfo );
						out.add( info );

					}

				}

			}

		}

		return out;

	}

	private RsoketHandlerInfo parseMessageMappingMethod(
		CtMethod<?> method
	) {

		RsoketHandlerInfo info = new RsoketHandlerInfo();

		// ---- parameters ----
		for (CtParameter<?> p : method.getParameters()) {
			CtAnnotation<?> destVarAnn = getAnnotationBySimpleName( p, "DestinationVariable" );

			RsoketTypeInfo pInfo = buildParamInfoFromTypeRef( p.getType() );
			pInfo.setName( p.getSimpleName() );
			pInfo = unwrapIfReactorType( pInfo );

			if (destVarAnn != null) {
				String key = extractFirstStringFromAnnotation( destVarAnn );

				if (key != null && ! key.isBlank()) {
					pInfo.setName( key );

				}

				info.getDestinationVariableInfo().put( pInfo.getName(), pInfo );

			} else {
				info.getPayloadInfo().put( pInfo.getName(), pInfo );

			}

		}

		// ---- response ----
		RsoketTypeInfo resp = parseResponseBody( method );

		if (resp != null) {
			String key;

			if (resp.getType() != null && resp.getType() != Object.class) {
				key = resp.getType().getSimpleName();

			} else {
				key = method.getSimpleName() + "Response";

			}

			info.getResponseBodyInfo().put( key, resp );

		}

		return info;

	}

	private RsoketTypeInfo parseResponseBody(
		CtMethod<?> method
	) {

		Factory factory = method.getFactory();
		CtTypeReference<SelectedResponseBody> rbAnnType = factory.Type().createReference( SelectedResponseBody.class );

		// 1) method-level @ResponseBody 우선
		CtAnnotation<?> methodAnn = method.getAnnotation( rbAnnType );
		RsoketTypeInfo annInfo = buildResponseBodyInfoFromAnnotation( methodAnn, factory );

		if (annInfo != null) { return annInfo; }

		// 2) return expr 내부에서 @ResponseBody 찾기 (local var / param / invoked method)
		CtExpression<?> returned = findFirstReturnExpression( method );
		CtAnnotation<?> found = findResponseBodyAnnotationRecursive( returned );
		annInfo = buildResponseBodyInfoFromAnnotation( found, factory );

		if (annInfo != null) { return annInfo; }

		// 3) 리턴 타입에서 Mono/Flux 제너릭을 파싱
		RsoketTypeInfo info = buildParamInfoFromTypeRef( method.getType() );
		RsoketTypeInfo unwrapped = unwrapIfReactorType( info );

		// POJO면 필드 파싱 보강
		if (unwrapped.getType() != null && RouteUtil.isPojo( unwrapped.getType() )) {
			CtTypeReference<?> tRef = unwrapped.getTypeRef();

			if (tRef == null) {
				tRef = factory.Type().createReference( unwrapped.getType() );

			}

			if (unwrapped.getFields().isEmpty()) {
				parseClassFields( tRef, unwrapped );

			}

		}

		return unwrapped;

	}

	private CtExpression<?> findFirstReturnExpression(
		CtMethod<?> m
	) {

		if (m == null || m.getBody() == null) { return null; }

		List<CtReturn<?>> returns = m.getBody().getElements( new TypeFilter<>( CtReturn.class ) );

		if (returns.isEmpty()) { return null; }

		CtReturn<?> r = returns.get( 0 );
		return r.getReturnedExpression();

	}

	// =========================
	// Annotation helpers
	// =========================

	private boolean hasAnnotation(
		CtElement el, String simpleName
	) {

		return getAnnotationBySimpleName( el, simpleName ) != null;

	}

	private CtAnnotation<?> getAnnotationBySimpleName(
		CtElement el, String simpleName
	) {

		if (el == null || el.getAnnotations() == null) { return null; }

		return el
			.getAnnotations()
			.stream()
			.filter( a -> a.getAnnotationType() != null && simpleName.equals( a.getAnnotationType().getSimpleName() ) )
			.findFirst()
			.orElse( null );

	}

	/**
	 * @MessageMapping(value={"a","b"}) 처럼 문자열 배열을 최대한 뽑아낸다.
	 * 값이 문자열 리터럴이 아니면 toString() 결과를 그대로 사용한다.
	 */
	private List<String> extractStringArrayFromAnnotation(
		CtAnnotation<?> ann
	) {

		if (ann == null) { return List.of(); }

		CtExpression<?> valueExpr = ann.getValue( "value" );

		if (valueExpr == null) {
			// 혹시 value가 아닌 케이스 대비
			valueExpr = ann.getValue( "destination" );

		}

		if (valueExpr == null) { return List.of(); }

		if (valueExpr instanceof CtLiteral<?> lit) {
			Object v = lit.getValue();

			if (v != null) { return List.of( String.valueOf( v ) ); }

			return List.of();

		}

		if (valueExpr instanceof CtNewArray<?> arr) {
			List<String> vals = new ArrayList<>();

			for (CtExpression<?> e : arr.getElements()) {
				String s = extractString( e );

				if (s != null) {
					vals.add( s );

				}

			}

			return vals;

		}

		String s = extractString( valueExpr );
		return (s == null) ? List.of() : List.of( s );

	}

	private String extractFirstStringFromAnnotation(
		CtAnnotation<?> ann
	) {

		List<String> arr = extractStringArrayFromAnnotation( ann );

		if (! arr.isEmpty()) { return arr.get( 0 ); }

		CtExpression<?> nameExpr = ann.getValue( "name" );
		return extractString( nameExpr );

	}

	private String extractString(
		CtExpression<?> expr
	) {

		if (expr == null) { return null; }

		if (expr instanceof CtLiteral<?> lit) {
			Object v = lit.getValue();
			return v == null ? null : String.valueOf( v );

		}

		return expr.toString();

	}

	private String joinDestination(
		String prefix, String leaf
	) {

		String a = prefix == null ? "" : prefix.trim();
		String b = leaf == null ? "" : leaf.trim();

		if (a.isEmpty()) { return b; }

		if (b.isEmpty()) { return a; }

		if (a.endsWith( "." ) || b.startsWith( "." ) || a.endsWith( "/" ) || b.startsWith( "/" )) { return a + b; }

		return a + "." + b;

	}

	// =========================
	// Type parsing
	// =========================

	private boolean isMonoOrFlux(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) { return false; }

		String simple = typeRef.getSimpleName();
		String q = typeRef.getQualifiedName();
		return "Mono".equals( simple ) || "Flux".equals( simple ) || "reactor.core.publisher.Mono".equals( q ) || "reactor.core.publisher.Flux".equals( q );

	}

	private Class<?> loadClassFromTypeReference(
		CtTypeReference<?> typeRef
	) {

		return TypeInfoParser.loadClassFromTypeReference( typeRef );

	}


	/**
	 * CtTypeReference를 RsoketTypeInfo로 변환. 제너릭은 recursive.
	 */
	private RsoketTypeInfo buildParamInfoFromTypeRef(
		CtTypeReference<?> typeRef
	) {

		return typeInfoParser.buildInfo( typeRef );

	}

	private boolean shouldParseFields(
		CtTypeReference<?> typeRef, Class<?> rawType
	) {

		if (typeRef == null) { return false; }

		String qName = typeRef.getQualifiedName();

		if (qName == null || qName.startsWith( "java." ) || qName.startsWith( "javax." ) || qName.startsWith( "jakarta." ) || qName.startsWith( "reactor." )) { return false; }

		if (rawType != null && rawType != Object.class && RouteUtil.isPojo( rawType )) { return true; }

		return typeRef.getTypeDeclaration() != null;

	}

	private void parseClassFields(
		CtTypeReference<?> wrapperRef, RsoketTypeInfo pInfo
	) {

		typeInfoParser.parseFields( wrapperRef, pInfo );

	}

	private boolean isLikelyRecordAccessor(
		CtMethod<?> method, RsoketTypeInfo ownerInfo
	) {

		String name = method.getSimpleName();

		if (name == null || name.isBlank()) { return false; }

		if (ownerInfo.getFields().containsKey( name )) { return false; }

		if (List.of( "toString", "hashCode", "clone", "getClass" ).contains( name )) { return false; }

		if ("equals".equals( name )) { return false; }

		if (name.startsWith( "get" ) || name.startsWith( "set" ) || name.startsWith( "is" )) { return false; }

		return true;

	}

	private RsoketTypeInfo buildPartialInfo(
		CtFieldReference<?> field, CtTypeReference<?> fieldType
	) {

		RsoketTypeInfo info = new RsoketTypeInfo();
		info.setName( field.getSimpleName() );
		info.setType( loadClassFromTypeReference( fieldType ) );
		info.setTypeRef( fieldType );
		return info;

	}

	private RsoketTypeInfo unwrapIfReactorType(
		RsoketTypeInfo pInfo
	) {

		if (pInfo == null) { return null; }

		if (pInfo.getType() != null) {
			String typeName = pInfo.getType().getName();

			if (("java.lang.Object".equals( typeName ) || "reactor.core.publisher.Mono".equals( typeName ) || "reactor.core.publisher.Flux".equals( typeName ) || "reactor.core.publisher.Sinks"
				.equals( typeName )) && pInfo.getGenericTypes() != null && ! pInfo.getGenericTypes().isEmpty()) {

				RsoketTypeInfo outer = pInfo;
				RsoketTypeInfo inner = pInfo.getGenericTypes().get( 0 );

				// name/nullable 같은 메타는 보존 (특히 파라미터명)
				if (inner.getName() == null && outer.getName() != null) {
					inner.setName( outer.getName() );

				}

				if (inner.getNullable() == null && outer.getNullable() != null) {
					inner.setNullable( outer.getNullable() );

				}

				pInfo = inner;
				return unwrapIfReactorType( pInfo );

			}

		}

		return pInfo;

	}

	// =========================
	// ResponseBody annotation
	// =========================

	private CtAnnotation<?> findResponseBodyAnnotationRecursive(
		CtExpression<?> expr
	) {

		if (expr == null) { return null; }

		CtVariable<?> varDecl = extractVariableDeclaration( expr );

		if (varDecl != null) {
			CtAnnotation<?> ann = varDecl.getAnnotation( varDecl.getFactory().Type().createReference( SelectedResponseBody.class ) );

			if (ann != null) { return ann; }

		}

		if (expr instanceof CtInvocation<?> inv) {
			CtAnnotation<?> methodAnn = findResponseBodyOnInvokedMethod( inv );

			if (methodAnn != null) { return methodAnn; }

			if (inv.getTarget() instanceof CtExpression<?> t) {
				CtAnnotation<?> a = findResponseBodyAnnotationRecursive( t );

				if (a != null) { return a; }

			}

			for (CtExpression<?> a : inv.getArguments()) {
				CtAnnotation<?> x = findResponseBodyAnnotationRecursive( a );

				if (x != null) { return x; }

			}

		}

		return null;

	}

	private CtVariable<?> extractVariableDeclaration(
		CtExpression<?> expr
	) {

		if (expr instanceof CtVariableRead<?> vr && vr.getVariable() != null) { return vr.getVariable().getDeclaration(); }

		return null;

	}

	private CtAnnotation<?> findResponseBodyOnInvokedMethod(
		CtInvocation<?> inv
	) {

		CtExecutableReference<?> execRef = inv.getExecutable();

		if (execRef == null || execRef.getDeclaringType() == null) { return null; }

		CtType<?> declaringType = execRef.getDeclaringType().getTypeDeclaration();

		if (declaringType == null) { return null; }

		var annType = inv.getFactory().Type().createReference( SelectedResponseBody.class );

		List<CtMethod<?>> candidates = declaringType
			.getMethods()
			.stream()
			.filter( m -> m.getSimpleName().equals( execRef.getSimpleName() ) )
			.toList();

		for (CtMethod<?> m : candidates) {
			CtAnnotation<?> ann = m.getAnnotation( annType );

			if (ann != null) { return ann; }

		}

		return null;

	}

	private RsoketTypeInfo buildResponseBodyInfoFromAnnotation(
		CtAnnotation<?> ann, Factory factory
	) {

		if (ann == null) { return null; }

		// classpath가 정상인 경우엔 실제 annotation 인스턴스로 처리
		if (ann.getActualAnnotation() instanceof SelectedResponseBody rb) {
			Class<?> typeClass = rb.type();

			if (typeClass == null || typeClass == Void.class || typeClass == void.class) { return null; }

			CtTypeReference<?> typeRef = factory.Type().createReference( typeClass );
			RsoketTypeInfo info = buildParamInfoFromTypeRef( typeRef );
			info.setType( typeClass );
			info.setTypeRef( typeRef );
			info.setNullable( rb.nullable() );

			if (typeRef != null) {
				parseClassFields( typeRef, info );

			}

			return unwrapIfReactorType( info );

		}

		// fallback: Spoon 표현식으로 최소한 value만
		CtExpression<?> typeExpr = ann.getValue( "type" );

		if (typeExpr == null) { return null; }

		// typeExpr.toString()이 "SomeClass.class" 형태인 경우가 많아서, 여기서는 안전하게 Object 처리
		RsoketTypeInfo info = new RsoketTypeInfo();
		info.setType( Object.class );
		return info;

	}

	public static void main(
		String[] args
	)
		throws Exception {

		File sourceDir = new File( "src/main/java" );
		RsoketParser parser = new RsoketParser();
		List<RsoketRouteInfo> routes = parser.extractRsoketRoutes( sourceDir.getPath() );
		System.out.println( routes.stream().map( RsoketRouteInfo::toString ).collect( Collectors.joining( "\n" ) ) );

	}

}
