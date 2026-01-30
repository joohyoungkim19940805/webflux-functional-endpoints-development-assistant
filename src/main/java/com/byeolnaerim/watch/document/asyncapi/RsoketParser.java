package com.byeolnaerim.watch.document.asyncapi;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.anntation.SelectedResponseBody;

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
 * Spring RSocket(@Controller + @MessageMapping) 기반 엔드포인트를 파싱해서
 * swagger.json 비슷한 형태로 쓸 수 있는 정보(RsoketRouteInfo)를 생성.
 * 요구사항:
 * - @Controller 붙은 클래스 중 @MessageMapping 메소드가 있고 return 타입이 Mono<>/Flux<> 인 것만 수집
 * - Mono<>/Flux<>의 제너릭 리턴 타입 파싱
 * - 커스텀 @ResponseBody가 있으면 그걸 우선 파싱
 * - RequestParam/RequestPath는 rsoket에서 미사용
 * - 매개변수 타입 파싱
 * - @DestinationVariable 파싱
 *
 * ※ swagger 모듈(HandlerInfo) 의존 제거: RsoketTypeInfo로 통일
 */
public class RsoketParser {

	private final Set<String> processedTypes = new HashSet<>();

	/**
	 * 소스 디렉토리(기본: src/main/java)를 Spoon으로 스캔해서 RSocket route 목록 생성.
	 */
	public List<RsoketRouteInfo> extractRsoketRoutes(
		String watchDirectory
	) {

		Launcher launcher = new Launcher();
		launcher.addInputResource(watchDirectory);
		launcher.getEnvironment().setAutoImports(true);
		launcher.getEnvironment().setNoClasspath(true);
		launcher.buildModel();

		return extractRsoketRoutes(launcher.getModel().getAllTypes());
	}

	/**
	 * CtType 집합(모델의 모든 타입)에서 컨트롤러/메시지매핑을 찾아 route 목록 생성.
	 */
	public List<RsoketRouteInfo> extractRsoketRoutes(
		Iterable<CtType<?>> allTypes
	) {

		List<RsoketRouteInfo> out = new ArrayList<>();

		for (CtType<?> controllerType : allTypes) {

			if (!hasAnnotation(controllerType, "Controller")) {
				continue;
			}

			CtAnnotation<?> classMessageMapping = getAnnotationBySimpleName(controllerType, "MessageMapping");
			List<String> classMappings = extractStringArrayFromAnnotation(classMessageMapping);

			if (classMappings.isEmpty()) {
				classMappings = List.of("");
			}

			// @MessageMapping + Mono/Flux 메소드만 수집
			List<CtMethod<?>> messageMethods = controllerType
				.getMethods()
				.stream()
				.filter(m -> hasAnnotation(m, "MessageMapping"))
				.filter(m -> isMonoOrFlux(m.getType()))
				.toList();

			if (messageMethods.isEmpty()) {
				continue;
			}

			for (CtMethod<?> m : messageMethods) {
				CtAnnotation<?> mmAnn = getAnnotationBySimpleName(m, "MessageMapping");
				List<String> methodMappings = extractStringArrayFromAnnotation(mmAnn);

				if (methodMappings.isEmpty()) {
					methodMappings = List.of("");
				}

				String publisher = m.getType() != null ? m.getType().getSimpleName() : "";
				RsoketHandlerInfo handlerInfo = parseMessageMappingMethod(m);

				for (String cm : classMappings) {
					for (String mm : methodMappings) {
						RsoketRouteInfo info = new RsoketRouteInfo();
						info.setController(controllerType.getQualifiedName());
						info.setControllerSimpleName(controllerType.getSimpleName());
						info.setMethod(m.getSimpleName());
						info.setPublisher(publisher);
						info.setDestination(joinDestination(cm, mm));
						info.setHandlerInfo(handlerInfo);
						out.add(info);
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
			CtAnnotation<?> destVarAnn = getAnnotationBySimpleName(p, "DestinationVariable");

			RsoketTypeInfo pInfo = buildParamInfoFromTypeRef(p.getType());
			pInfo.setName(p.getSimpleName());
			pInfo = unwrapIfReactorType(pInfo);

			if (destVarAnn != null) {
				String key = extractFirstStringFromAnnotation(destVarAnn);

				if (key != null && !key.isBlank()) {
					pInfo.setName(key);
				}

				info.getDestinationVariableInfo().put(pInfo.getName(), pInfo);
			} else {
				info.getPayloadInfo().put(pInfo.getName(), pInfo);
			}
		}

		// ---- response ----
		RsoketTypeInfo resp = parseResponseBody(method);

		if (resp != null) {
			String key;
			if (resp.getType() != null && resp.getType() != Object.class) {
				key = resp.getType().getSimpleName();
			} else {
				key = method.getSimpleName() + "Response";
			}
			info.getResponseBodyInfo().put(key, resp);
		}

		return info;
	}

	private RsoketTypeInfo parseResponseBody(
		CtMethod<?> method
	) {

		Factory factory = method.getFactory();
		CtTypeReference<SelectedResponseBody> rbAnnType = factory.Type().createReference(SelectedResponseBody.class);

		// 1) method-level @ResponseBody 우선
		CtAnnotation<?> methodAnn = method.getAnnotation(rbAnnType);
		RsoketTypeInfo annInfo = buildResponseBodyInfoFromAnnotation(methodAnn, factory);
		if (annInfo != null) { return annInfo; }

		// 2) return expr 내부에서 @ResponseBody 찾기 (local var / param / invoked method)
		CtExpression<?> returned = findFirstReturnExpression(method);
		CtAnnotation<?> found = findResponseBodyAnnotationRecursive(returned);
		annInfo = buildResponseBodyInfoFromAnnotation(found, factory);
		if (annInfo != null) { return annInfo; }

		// 3) 리턴 타입에서 Mono/Flux 제너릭을 파싱
		RsoketTypeInfo info = buildParamInfoFromTypeRef(method.getType());
		RsoketTypeInfo unwrapped = unwrapIfReactorType(info);

		// POJO면 필드 파싱 보강
		if (unwrapped.getType() != null && RouteUtil.isPojo(unwrapped.getType())) {
			CtTypeReference<?> tRef = unwrapped.getTypeRef();

			if (tRef == null) {
				tRef = factory.Type().createReference(unwrapped.getType());
			}

			if (unwrapped.getFields().isEmpty()) {
				parseClassFields(tRef, unwrapped);
			}
		}

		return unwrapped;
	}

	private CtExpression<?> findFirstReturnExpression(
		CtMethod<?> m
	) {

		if (m == null || m.getBody() == null) { return null; }

		List<CtReturn<?>> returns = m.getBody().getElements(new TypeFilter<>(CtReturn.class));
		if (returns.isEmpty()) { return null; }

		CtReturn<?> r = returns.get(0);
		return r.getReturnedExpression();
	}

	// =========================
	// Annotation helpers
	// =========================

	private boolean hasAnnotation(
		CtElement el, String simpleName
	) {
		return getAnnotationBySimpleName(el, simpleName) != null;
	}

	private CtAnnotation<?> getAnnotationBySimpleName(
		CtElement el, String simpleName
	) {

		if (el == null || el.getAnnotations() == null) { return null; }

		return el
			.getAnnotations()
			.stream()
			.filter(a -> a.getAnnotationType() != null && simpleName.equals(a.getAnnotationType().getSimpleName()))
			.findFirst()
			.orElse(null);
	}

	/**
	 * @MessageMapping(value={"a","b"}) 처럼 문자열 배열을 최대한 뽑아낸다.
	 * 값이 문자열 리터럴이 아니면 toString() 결과를 그대로 사용한다.
	 */
	private List<String> extractStringArrayFromAnnotation(
		CtAnnotation<?> ann
	) {

		if (ann == null) { return List.of(); }

		CtExpression<?> valueExpr = ann.getValue("value");

		if (valueExpr == null) {
			// 혹시 value가 아닌 케이스 대비
			valueExpr = ann.getValue("destination");
		}

		if (valueExpr == null) { return List.of(); }

		if (valueExpr instanceof CtLiteral<?> lit) {
			Object v = lit.getValue();
			if (v != null) { return List.of(String.valueOf(v)); }
			return List.of();
		}

		if (valueExpr instanceof CtNewArray<?> arr) {
			List<String> vals = new ArrayList<>();

			for (CtExpression<?> e : arr.getElements()) {
				String s = extractString(e);
				if (s != null) {
					vals.add(s);
				}
			}

			return vals;
		}

		String s = extractString(valueExpr);
		return (s == null) ? List.of() : List.of(s);
	}

	private String extractFirstStringFromAnnotation(
		CtAnnotation<?> ann
	) {

		List<String> arr = extractStringArrayFromAnnotation(ann);
		if (!arr.isEmpty()) { return arr.get(0); }

		CtExpression<?> nameExpr = ann.getValue("name");
		return extractString(nameExpr);
	}

	private String extractString(
		CtExpression<?> expr
	) {

		if (expr == null) { return null; }

		if (expr instanceof CtLiteral<?> lit) {
			Object v = lit.getValue();
			return v == null ? null : String.valueOf(v);
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

		if (a.endsWith(".") || b.startsWith(".") || a.endsWith("/") || b.startsWith("/")) { return a + b; }
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
		return "Mono".equals(simple)
			|| "Flux".equals(simple)
			|| "reactor.core.publisher.Mono".equals(q)
			|| "reactor.core.publisher.Flux".equals(q);
	}

	private Class<?> loadClassFromTypeReference(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) { return Object.class; }

		String qName = typeRef.getQualifiedName();
		if (qName == null) { return Object.class; }

		switch (qName) {
			case "boolean": return boolean.class;
			case "byte": return byte.class;
			case "short": return short.class;
			case "int": return int.class;
			case "long": return long.class;
			case "float": return float.class;
			case "double": return double.class;
			case "char": return char.class;
			case "void": return void.class;
			default: break;
		}

		try {
			return Class.forName(qName);
		} catch (ClassNotFoundException e) {
			return Object.class;
		}
	}

	/**
	 * CtTypeReference를 RsoketTypeInfo로 변환. 제너릭은 recursive.
	 */
	private RsoketTypeInfo buildParamInfoFromTypeRef(
		CtTypeReference<?> typeRef
	) {

		RsoketTypeInfo pInfo = new RsoketTypeInfo();

		if (typeRef == null) {
			pInfo.setType(Object.class);
			return pInfo;
		}

		Class<?> rawType = loadClassFromTypeReference(typeRef);
		pInfo.setType(rawType);
		pInfo.setTypeRef(typeRef);

		List<CtTypeReference<?>> actualTypeArgs = typeRef.getActualTypeArguments();

		if (actualTypeArgs != null && !actualTypeArgs.isEmpty()) {
			List<RsoketTypeInfo> genericParams = new ArrayList<>();

			for (CtTypeReference<?> argRef : actualTypeArgs) {
				RsoketTypeInfo genericParamInfo = buildParamInfoFromTypeRef(argRef);

				if (RouteUtil.isPojo(genericParamInfo.getType())) {
					parseClassFields(argRef, genericParamInfo);

					if (genericParamInfo.getFields().isEmpty()) {
						parseClassFields(argRef.getFactory().Type().createReference(genericParamInfo.getType()), genericParamInfo);
					}
				}

				genericParams.add(genericParamInfo);
			}

			pInfo.setGenericTypes(genericParams);
		}

		return pInfo;
	}

	private void parseClassFields(
		CtTypeReference<?> wrapperRef, RsoketTypeInfo pInfo
	) {

		if (wrapperRef == null || wrapperRef.getQualifiedName() == null) { return; }

		if (processedTypes.contains(wrapperRef.getQualifiedName())) { return; }
		processedTypes.add(wrapperRef.getQualifiedName());

		wrapperRef.getDeclaredFields().forEach(field -> {
			CtTypeReference<?> fieldType = field.getType();

			// generic self-reference 방지
			var args = fieldType.getActualTypeArguments();
			if (args != null && args.stream().anyMatch(e -> e.getSimpleName().equals(wrapperRef.getSimpleName()))) {
				RsoketTypeInfo selfRefInfo = buildPartialInfo(field, fieldType);
				pInfo.addField(field.getSimpleName(), selfRefInfo);
				return;
			}

			// direct self-reference 방지
			if (fieldType.getQualifiedName() != null && fieldType.getQualifiedName().equals(wrapperRef.getQualifiedName())) {
				RsoketTypeInfo selfRefInfo = buildPartialInfo(field, fieldType);
				pInfo.addField(field.getSimpleName(), selfRefInfo);
				return;
			}

			RsoketTypeInfo fieldInfo = buildParamInfoFromTypeRef(fieldType);

			if (fieldInfo.getName() == null) {
				fieldInfo.setName(field.getSimpleName());
			}

			if (fieldInfo.getType() != null && fieldInfo.getType().isEnum()) {
				fieldInfo.setExample(RouteUtil.parserEnumValues(fieldInfo.getType()).toString());
			} else if (fieldInfo.getType() != null
				&& (fieldInfo.getType().isRecord()
					|| (fieldInfo.getType().getPackageName() != null && fieldInfo.getType().getPackageName().startsWith("com.byeolnaerim")))) {
				parseClassFields(wrapperRef.getFactory().Type().createReference(fieldInfo.getType()), fieldInfo);
			}

			pInfo.addField(field.getSimpleName(), fieldInfo);
		});

		processedTypes.remove(wrapperRef.getQualifiedName());
	}

	private RsoketTypeInfo buildPartialInfo(
		CtFieldReference<?> field, CtTypeReference<?> fieldType
	) {

		RsoketTypeInfo info = new RsoketTypeInfo();
		info.setName(field.getSimpleName());
		info.setType(loadClassFromTypeReference(fieldType));
		info.setTypeRef(fieldType);
		return info;
	}

	private RsoketTypeInfo unwrapIfReactorType(
		RsoketTypeInfo pInfo
	) {

		if (pInfo == null) { return null; }

		if (pInfo.getType() != null) {
			String typeName = pInfo.getType().getName();

			if (("java.lang.Object".equals(typeName)
				|| "reactor.core.publisher.Mono".equals(typeName)
				|| "reactor.core.publisher.Flux".equals(typeName)
				|| "reactor.core.publisher.Sinks".equals(typeName))
				&& pInfo.getGenericTypes() != null
				&& !pInfo.getGenericTypes().isEmpty()) {

				RsoketTypeInfo outer = pInfo;
				RsoketTypeInfo inner = pInfo.getGenericTypes().get(0);

				// name/nullable 같은 메타는 보존 (특히 파라미터명)
				if (inner.getName() == null && outer.getName() != null) {
					inner.setName(outer.getName());
				}
				if (inner.getNullable() == null && outer.getNullable() != null) {
					inner.setNullable(outer.getNullable());
				}

				pInfo = inner;
				return unwrapIfReactorType(pInfo);
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

		CtVariable<?> varDecl = extractVariableDeclaration(expr);

		if (varDecl != null) {
			CtAnnotation<?> ann = varDecl.getAnnotation(varDecl.getFactory().Type().createReference(SelectedResponseBody.class));
			if (ann != null) { return ann; }
		}

		if (expr instanceof CtInvocation<?> inv) {
			CtAnnotation<?> methodAnn = findResponseBodyOnInvokedMethod(inv);
			if (methodAnn != null) { return methodAnn; }

			if (inv.getTarget() instanceof CtExpression<?> t) {
				CtAnnotation<?> a = findResponseBodyAnnotationRecursive(t);
				if (a != null) { return a; }
			}

			for (CtExpression<?> a : inv.getArguments()) {
				CtAnnotation<?> x = findResponseBodyAnnotationRecursive(a);
				if (x != null) { return x; }
			}
		}

		return null;
	}

	private CtVariable<?> extractVariableDeclaration(
		CtExpression<?> expr
	) {
		if (expr instanceof CtVariableRead<?> vr && vr.getVariable() != null) {
			return vr.getVariable().getDeclaration();
		}
		return null;
	}

	private CtAnnotation<?> findResponseBodyOnInvokedMethod(
		CtInvocation<?> inv
	) {

		CtExecutableReference<?> execRef = inv.getExecutable();

		if (execRef == null || execRef.getDeclaringType() == null) { return null; }

		CtType<?> declaringType = execRef.getDeclaringType().getTypeDeclaration();
		if (declaringType == null) { return null; }

		var annType = inv.getFactory().Type().createReference(SelectedResponseBody.class);

		List<CtMethod<?>> candidates = declaringType
			.getMethods()
			.stream()
			.filter(m -> m.getSimpleName().equals(execRef.getSimpleName()))
			.toList();

		for (CtMethod<?> m : candidates) {
			CtAnnotation<?> ann = m.getAnnotation(annType);
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

			CtTypeReference<?> typeRef = factory.Type().createReference(typeClass);
			RsoketTypeInfo info = buildParamInfoFromTypeRef(typeRef);
			info.setType(typeClass);
			info.setTypeRef(typeRef);
			info.setNullable(rb.nullable());

			if (typeRef != null) {
				parseClassFields(typeRef, info);
			}

			return unwrapIfReactorType(info);
		}

		// fallback: Spoon 표현식으로 최소한 value만
		CtExpression<?> typeExpr = ann.getValue("type");
		if (typeExpr == null) { return null; }

		// typeExpr.toString()이 "SomeClass.class" 형태인 경우가 많아서, 여기서는 안전하게 Object 처리
		RsoketTypeInfo info = new RsoketTypeInfo();
		info.setType(Object.class);
		return info;
	}

	public static void main(
		String[] args
	) throws Exception {

		File sourceDir = new File("src/main/java");
		RsoketParser parser = new RsoketParser();
		List<RsoketRouteInfo> routes = parser.extractRsoketRoutes(sourceDir.getPath());
		System.out.println(routes.stream().map(RsoketRouteInfo::toString).collect(Collectors.joining("\n")));
	}
}
