package com.byeolnaerim.watch.document.swagger.functional;


import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.anntation.SelectedRequestParam;
import com.byeolnaerim.watch.document.anntation.SelectedRequestPath;
import com.byeolnaerim.watch.document.anntation.SelectedResponseBody;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo.LayerPosition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;


/**
 * Parses functional-endpoint handler expressions and extracts {@link HandlerInfo} metadata.
 * <p>This parser supports lambda handlers and method references, recursively follows
 * nested method calls and lambda bodies, and attempts to infer query parameters,
 * path variables, request bodies, and response-body schemas from handler code.</p>
 */
public class HandlerParser {

	/**
	 * external jar에서 decompile 한 타입들만 별도 registry로 받는다.
	 * internal model은 건드리지 않는다.
	 */
	private final Map<String, CtType<?>> externalTypes;

	public HandlerParser() {

		this.externalTypes = Map.of();

	}

	public HandlerParser(
							Map<String, CtType<?>> externalTypes
	) {

		this.externalTypes = (externalTypes != null) ? externalTypes : Map.of();

	}

	// HandlerParser 내에 추가할 필드
	private Map<String, Boolean> queryParamsVars = new HashMap<>();

	private Map<String, Boolean> pathsParamsVars = new HashMap<>();

	private Set<String> processedTypes = new HashSet<>();

	private boolean hasResponseBodyAnnotationOverride = false;

	/**
	 * Parses the given handler expression and returns extracted handler metadata.
	 *
	 * @param handlerExpression
	 *            the handler lambda or method reference
	 * @param routeName
	 *            the logical route name used during parsing
	 * 
	 * @return the extracted handler metadata
	 */
	public HandlerInfo parseHandler(
		CtExpression<?> handlerExpression, String routeName
	) {

		queryParamsVars.clear();
		pathsParamsVars.clear();
		processedTypes.clear();
		hasResponseBodyAnnotationOverride = false;
		HandlerInfo handlerInfo = new HandlerInfo();

		// handlerExpression이 람다인지 메서드 참조인지 판별
		if (handlerExpression instanceof CtLambda<?> lambda) {
			parseLambdaHandler( lambda, handlerInfo, routeName );

		} else if (handlerExpression instanceof CtExecutableReferenceExpression<?, ?> methodRef) {
			parseMethodReferenceHandler( methodRef, handlerInfo, routeName );

		}

		return handlerInfo;

	}

	private void parseLambdaHandler(
		CtLambda<?> lambda, HandlerInfo handlerInfo, String routeName
	) {

		// 람다 본문(CtBlock)을 분석하여 request/query/pathvar/body/response 관련 호출 파악
		CtBlock<?> body = getLambdaBody( lambda );

		if (body != null) {
			parseHandlerBody( body, handlerInfo, routeName );

		}

	}

	/**
	 * internal 기준 기존 방식 우선.
	 * 못 찾을 때만 external registry에서 fallback.
	 */
	private CtType<?> resolveDeclaringType(
		CtExecutableReference<?> executableRef
	) {

		if (executableRef == null) {
			return null;

		}

		String qualifiedName = null;
		String simpleName = null;

		if (executableRef.getDeclaringType() != null) {
			qualifiedName = executableRef.getDeclaringType().getQualifiedName();
			simpleName = executableRef.getDeclaringType().getSimpleName();

			CtType<?> declaringType = executableRef.getDeclaringType().getTypeDeclaration();

			// source-backed type만 즉시 사용
			if (declaringType != null && ! declaringType.isShadow()) {
				return declaringType;

			}

		}

		// shadow 이거나 null 이면 external registry 우선
		CtType<?> externalDeclaringType = findExternalDeclaringType( qualifiedName, simpleName );

		if (externalDeclaringType != null) {
			return externalDeclaringType;

		}

		// shadow 는 body/field 파싱에 쓸모 없으므로 null 취급
		return null;

	}

	private CtType<?> resolveDeclaringType(
		CtExecutableReferenceExpression<?, ?> methodRef
	) {

		if (methodRef == null) {
			return null;

		}

		CtExecutableReference<?> executableRef = methodRef.getExecutable();

		if (executableRef == null) {
			return null;

		}

		CtType<?> declaringType = resolveDeclaringType( executableRef );

		if (declaringType != null) {
			return declaringType;

		}

		String targetQualifiedName = null;
		String targetSimpleName = null;

		CtExpression<?> targetExpr = methodRef.getTarget();

		if (targetExpr != null && targetExpr.getType() != null) {
			targetQualifiedName = targetExpr.getType().getQualifiedName();
			targetSimpleName = targetExpr.getType().getSimpleName();

		}

		CtType<?> externalDeclaringType = findExternalDeclaringType( targetQualifiedName, targetSimpleName );

		if (externalDeclaringType != null) {
			return externalDeclaringType;

		}

		return null;

	}

	private CtType<?> findExternalDeclaringType(
		String qualifiedName, String simpleName
	) {

		if (externalTypes == null || externalTypes.isEmpty()) {
			return null;

		}

		if (qualifiedName != null && ! qualifiedName.isBlank()) {
			CtType<?> exact = externalTypes.get( qualifiedName );

			if (exact != null) {
				return exact;

			}

		}

		// simpleName fallback은 유일할 때만
		if (simpleName != null && ! simpleName.isBlank()) {
			CtType<?> found = null;

			for (CtType<?> type : externalTypes.values()) {

				if (simpleName.equals( type.getSimpleName() )) {

					if (found != null) {
						return null; // ambiguous

					}

					found = type;

				}

			}

			return found;

		}

		return null;

	}

	private CtTypeReference<?> resolveSourceBackedTypeReference(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) {
			return null;

		}

		CtType<?> typeDecl = typeRef.getTypeDeclaration();

		if (typeDecl != null && ! typeDecl.isShadow()) {
			return typeRef;

		}

		CtType<?> externalType = findExternalDeclaringType(
			typeRef.getQualifiedName(),
			typeRef.getSimpleName()
		);

		if (externalType == null) {
			return typeRef;

		}

		CtTypeReference<?> resolvedRef = externalType.getReference().clone();

		List<CtTypeReference<?>> actualTypeArgs = typeRef.getActualTypeArguments();

		if (actualTypeArgs != null && ! actualTypeArgs.isEmpty()) {
			resolvedRef
				.setActualTypeArguments(
					actualTypeArgs
						.stream()
						.map( this::resolveSourceBackedTypeReference )
						.collect( Collectors.toList() )
				);

		}

		return resolvedRef;

	}

	private CtType<?> resolveSourceBackedType(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) {
			return null;

		}

		CtType<?> typeDecl = typeRef.getTypeDeclaration();

		if (typeDecl != null && ! typeDecl.isShadow()) {
			return typeDecl;

		}

		return findExternalDeclaringType(
			typeRef.getQualifiedName(),
			typeRef.getSimpleName()
		);

	}

	private void parseMethodReferenceHandler(
		CtExecutableReferenceExpression<?, ?> methodRef, HandlerInfo handlerInfo, String routeName
	) {

		CtExecutableReference<?> executableRef = methodRef.getExecutable();

		if (executableRef == null) {
			return;

		}

		// 메서드 참조에서 참조하는 메서드를 찾아야 한다.
		// internal 기준 기존 방식 우선, 없으면 external fallback
		CtType<?> declaringType = resolveDeclaringType( methodRef );

		if (declaringType != null) {
			// 메서드 이름과 파라미터 타입 등을 통해 CtMethod를 찾는다.
			List<CtMethod<?>> candidates = declaringType
				.getMethods()
				.stream()
				.filter( m -> {
					m.getReference().getActualTypeArguments();
					return m.getSimpleName().equals( executableRef.getSimpleName() );

				} )
				// 파라미터 타입 매칭 로직 필요. 여기서는 단순히 이름 맞추는 정도로 가정
				.collect( Collectors.toList() );

			// 여기서는 매칭되는 첫 번째 메서드를 사용
			if (! candidates.isEmpty()) {
				CtMethod<?> method = candidates.get( 0 );

				if (method.getBody() != null) {
					parseHandlerBody( method.getBody(), handlerInfo, routeName );

				}

			}

		}

	}

	private CtBlock<?> getLambdaBody(
		CtLambda<?> lambda
	) {

		CtStatement body = lambda.getBody();

		if (body instanceof CtBlock<?>) { return (CtBlock<?>) body; }

		return null;

	}

	private void parseHandlerBody(
		CtBlock<?> body, HandlerInfo handlerInfo, String routeName
	) {

		// [디버깅 1단계] =======================================================
		// System.out.println( "\n[DEBUG] 1. Parsing Handler Body for Route: " + routeName );
		// ===================================================================

		// 블록 내부의 로컬 변수들을 먼저 파싱
		parseLocalVariables( body );

		// 블록 내부의 Invocation들을 순회하며,
		// - request/response 등 분석 (analyzeInvocationForRequestResponse)
		// - 메소드 참조가 있는 경우 해당 메소드의 본문을 재귀적으로 파싱
		parseInvocations( body, handlerInfo, routeName );

		// System.out.println( routeName + " ::: " + handlerInfo.getResponseBodyInfo() );

		if (handlerInfo.getResponseBodyInfo() == null || handlerInfo.getResponseBodyInfo().isEmpty()) {

			// 본문 내의 return 관련 체인의 최종 호출도 추적 (체이닝 누락 보완 ex) flatMap.flatMap 내부의 responseBody 파싱이 안되는 현상)
			// 20250813
			List<CtReturn<?>> returnStatements = body.getElements( new TypeFilter<>( CtReturn.class ) );

			for (CtReturn<?> returnStmt : returnStatements) {
				CtExpression<?> returnedExpression = returnStmt.getReturnedExpression();

				if (returnedExpression instanceof CtInvocation) {
					parseInvocationChain( (CtInvocation<?>) returnedExpression, handlerInfo, routeName );

				}

			}

		}

	}

	/**
	 * body 내의 CtLocalVariable들을 분석하여, request.queryParams() 나
	 * request.pathVariables() 등을 사용하는 변수가 있으면 기록
	 */
	private void parseLocalVariables(
		CtBlock<?> body
	) {

		List<CtLocalVariable<?>> localVars = body.getElements( new TypeFilter<>( CtLocalVariable.class ) );

		for (CtLocalVariable<?> localVar : localVars) {

			if (localVar.getAssignment() instanceof CtInvocation<?> assignInv) {

				if (matchesCall( assignInv, "queryParams" ) && isTargetRequest( assignInv )) {
					// var anyVar = request.queryParams();
					queryParamsVars.put( localVar.getSimpleName(), true );

				}

				if (matchesCall( assignInv, "pathVariables" ) && isTargetRequest( assignInv )) {
					pathsParamsVars.put( localVar.getSimpleName(), true );

				}

			}

		}

	}

	/**
	 * body 내의 Invocation들을 순회하면서:
	 * 1) 현재 invocation 분석 (analyzeInvocationForRequestResponse)
	 * 2) invocation이 참조하는 메서드 선언부를 찾아, ServerRequest 파라미터가 있으면
	 * 해당 메서드의 본문을 재귀적으로 parseHandlerBody 수행
	 */
	private void parseInvocations(
		CtBlock<?> body, HandlerInfo handlerInfo, String routeName
	) {

		List<CtInvocation<?>> invocations = body.getElements( new TypeFilter<>( CtInvocation.class ) );

		for (CtInvocation<?> inv : invocations) {
			// request/query/pathvar/body/response 등 분석
			analyzeInvocationForRequestResponse( inv, handlerInfo, routeName );

			// 이 invocation이 참조하는 메소드의 선언부가 있는지 찾고, 재귀 분석
			CtExecutableReference<?> execRef = inv.getExecutable();

			if (execRef != null) {
				CtType<?> declaringType = resolveDeclaringType( execRef );

				if (declaringType != null) {
					List<CtMethod<?>> candidateMethods = declaringType
						.getMethods()
						.stream()
						.filter( m -> m.getSimpleName().equals( execRef.getSimpleName() ) )
						.collect( Collectors.toList() );

					for (CtMethod<?> method : candidateMethods) {
						boolean hasServerRequestParam = method
							.getParameters()
							.stream()
							.anyMatch( p -> p.getType() != null && "ServerRequest".equals( p.getType().getSimpleName() ) );

						if (hasServerRequestParam && method.getBody() != null) {
							parseHandlerBody( method.getBody(), handlerInfo, routeName );

						}

					}

				}

			}

			// **flatMap**, **map**, **filter** 등의 람다 인수 안에 숨어 있는 핸들러 코드도 재귀 파싱
			for (CtExpression<?> arg : inv.getArguments()) {

				if (arg instanceof CtLambda<?> lambda) {
					CtBlock<?> lambdaBody = getLambdaBody( lambda );

					if (lambdaBody != null) {
						// 람다 블록 자체를 먼저 파싱
						parseHandlerBody( lambdaBody, handlerInfo, routeName );

						// ==== NEW: 람다 내부에서 호출되는 메서드들도 다시 따라가 재귀 파싱 ====
						List<CtInvocation<?>> innerInvs = lambdaBody.getElements( new TypeFilter<>( CtInvocation.class ) );

						for (CtInvocation<?> innerInv : innerInvs) {
							CtExecutableReference<?> innerExecRef = innerInv.getExecutable();
							if (innerExecRef == null)
								continue;

							CtType<?> innerDeclaringType = resolveDeclaringType( innerExecRef );
							if (innerDeclaringType == null)
								continue;

							List<CtMethod<?>> innerCandidates = innerDeclaringType
								.getMethods()
								.stream()
								.filter( m -> m.getSimpleName().equals( innerExecRef.getSimpleName() ) )
								.collect( Collectors.toList() );

							for (CtMethod<?> m : innerCandidates) {
								boolean hasServerRequestParam = m
									.getParameters()
									.stream()
									.anyMatch( p -> p.getType() != null && "ServerRequest".equals( p.getType().getSimpleName() ) );

								if (hasServerRequestParam && m.getBody() != null) {
									parseHandlerBody( m.getBody(), handlerInfo, routeName );

								}

							}

						}

					}

				} else if (arg instanceof CtExecutableReferenceExpression<?, ?> methodRef) {
					// 메서드 참조 형태도 동일하게 처리하되 null 방어
					CtExecutableReference<?> ref = ((CtExecutableReferenceExpression<?, ?>) methodRef).getExecutable();

					if (ref != null) {
						// 1) 기존 처리
						parseMethodReferenceHandler(
							(CtExecutableReferenceExpression<?, ?>) methodRef,
							handlerInfo,
							routeName
						);

						// 2) NEW: 메서드 참조가 가리키는 선언부를 찾아 재귀 파싱
						CtType<?> declaringType = resolveDeclaringType( (CtExecutableReferenceExpression<?, ?>) methodRef );

						if (declaringType != null) {
							// 이름 기준 후보 수집(오버로드 고려 시 시그니처 비교로 보강 가능)
							List<CtMethod<?>> candidates = declaringType
								.getMethods()
								.stream()
								.filter( m -> m.getSimpleName().equals( ref.getSimpleName() ) )
								.collect( Collectors.toList() );

							for (CtMethod<?> m : candidates) {
								boolean hasServerRequestParam = m
									.getParameters()
									.stream()
									.anyMatch( p -> p.getType() != null && "ServerRequest".equals( p.getType().getSimpleName() ) );

								if (hasServerRequestParam && m.getBody() != null) {
									parseHandlerBody( m.getBody(), handlerInfo, routeName );

								}

							}

						}

					}

				}

			}

		}

	}

	private void parseInvocationChain(
		CtInvocation<?> invocation, HandlerInfo handlerInfo, String routeName
	) {

		if (invocation == null)
			return;

		// [디버깅 2단계] =======================================================
		// System.out.println( "[DEBUG] 2. Traversing Invocation Chain: " +
		// invocation.getExecutable().getSimpleName() );
		// ===================================================================

		// 1. 현재 호출(invocation) 자체를 분석
		analyzeInvocationForRequestResponse( invocation, handlerInfo, routeName );

		// 2. 현재 호출의 '대상(target)'이 또 다른 호출이라면, 체인의 이전 단계를 계속 추적
		if (invocation.getTarget() instanceof CtInvocation) {
			parseInvocationChain( (CtInvocation<?>) invocation.getTarget(), handlerInfo, routeName );

		}

		// 3. 현재 호출의 '인자(argument)'가 람다이면, 그 람다 내부를 분석
		for (CtExpression<?> arg : invocation.getArguments()) {

			if (arg instanceof CtLambda<?> lambda) {
				// [디버깅 3단계] ===================================================
				// System.out.println( "[DEBUG] 3. Found Lambda in argument of -> " +
				// invocation.getExecutable().getSimpleName() );
				// ===============================================================


				CtExpression<?> returnedExpr = findReturnedExpressionInLambda( lambda );

				if (returnedExpr == null) {
					continue;

				} else if (returnedExpr instanceof CtBlock) { // 람다의 body가 블록인 경우
					parseHandlerBody( (CtBlock<?>) returnedExpr, handlerInfo, routeName );

				} else if (returnedExpr instanceof CtInvocation) { // 람다의 body가 표현식인 경우
					parseInvocationChain( (CtInvocation<?>) returnedExpr, handlerInfo, routeName );

				}

			}

		}

	}

	private CtExpression<?> findReturnedExpressionInLambda(
		CtLambda<?> lambda
	) {

		CtElement body = lambda.getBody();
		if (body == null)
			return null;
		// [디버깅 4단계] =========================================================
		// System.out.println( "[DEBUG] 4. Analyzing Lambda Body. Body Type: " +
		// body.getClass().getSimpleName() );
		// =====================================================================

		// Expression body: () -> data
		if (body instanceof CtExpression) { return (CtExpression<?>) body; }

		// Block body: () -> { return data; }
		if (body instanceof CtBlock) {
			CtBlock<?> blockBody = (CtBlock<?>) body;

			// 1) 명시적 return 우선
			List<CtReturn<?>> returnStatements = blockBody.getElements( new TypeFilter<>( CtReturn.class ) );

			if (! returnStatements.isEmpty()) { return returnStatements.get( 0 ).getReturnedExpression(); }

			// 2) 최상위 문(statement) 중에서 표현식 문 찾기 (예: 메서드 호출)
			for (CtStatement st : blockBody.getStatements()) {
				if (st instanceof CtReturn)
					continue;

				if (st instanceof CtExpression) {
					// CtInvocation 등은 CtExpression이기도 합니다.
					// System.out.println( "[DEBUG] 4. Using top-level expression statement: " +
					// st.toString().substring( 0, Math.min( 120, st.toString().length() ) ) );
					return (CtExpression<?>) st;

				}

				// 지역변수 선언에서 초기화 식이 있는 경우도 표현식으로 활용
				if (st instanceof CtLocalVariable) {
					CtExpression<?> init = ((CtLocalVariable<?>) st).getDefaultExpression();

					if (init != null) {
						// System.out.println( "[DEBUG] 4. Using local variable init expr: " + init.toString().substring( 0,
						// Math.min( 120, init.toString().length() ) ) );
						return init;

					}

				}

			}

			// 3) 적절한 표현식이 없으면 null
			// System.out.println( "[DEBUG] 4. No return/expr found in lambda block." );
			return null;

		}

		return null;

	}


	private void analyzeInvocationForRequestResponse(
		CtInvocation<?> inv, HandlerInfo handlerInfo, String routeName
	) {

		// String simpleName = inv.getExecutable().getSimpleName();

		// request.* 호출 분석 (queryParam, pathVariable)

		// request.queryParams().getFirst("x")
		if (isRequestQueryParamsGetFirstDirectCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			addParamInfo( handlerInfo, key, inv, LayerPosition.REQUEST_STRING );

		}

		// request.queryParams().get("x")
		if (isRequestQueryParamsGetDirectCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			String defaultVal = findOrElseDefaultValue( inv );
			addParamInfo( handlerInfo, key, defaultVal, inv, LayerPosition.REQUEST_STRING );

		}

		// request.queryParams().getOrDefault("x", ...)
		if (isRequestQueryParamsGetOrDefaultDirectCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			addParamInfo( handlerInfo, key, null, inv, LayerPosition.REQUEST_STRING );

		}

		// request.queryParam("key") -> query string 파싱
		// 기존 request.queryParam(...) 처리
		if (isRequestQueryParamCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			String defaultVal = findOrElseDefaultValue( inv );
			addParamInfo( handlerInfo, key, defaultVal, inv, LayerPosition.REQUEST_STRING );

		}

		// request queryParams.get
		if (isQueryParamsGetCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			String defaultVal = findOrElseDefaultValue( inv );
			addParamInfo( handlerInfo, key, defaultVal, inv, LayerPosition.REQUEST_STRING );

		}

		// request.queryParam(...).getFirst
		if (isQueryParamsGetFirstCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			addParamInfo( handlerInfo, key, inv, LayerPosition.REQUEST_STRING );

		}

		// reuqest.queryParam.getOrDefault
		if (isQueryParamsGetOrDefaultCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			// String defaultVal = extractStringArgument( inv, 1 );
			addParamInfo( handlerInfo, key, null, inv, LayerPosition.REQUEST_STRING );

		}

		// request.pathVariable("var") -> path variable 파싱
		if (isRequestPathVariableCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			addParamInfo( handlerInfo, key, key, inv, LayerPosition.REQUEST_PATH );
			// HandlerInfo.ParamInfo pInfo = new HandlerInfo.ParamInfo();
			// pInfo.setName( varName );
			// pInfo.setRequired( true ); // pathVar는 보통 필수
			// handlerInfo.getPathVariableInfo().put( varName, pInfo );

		}

		if (isRequestPathVariablesGetCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			String defaultVal = findOrElseDefaultValue( inv );
			addParamInfo( handlerInfo, key, defaultVal, inv, LayerPosition.REQUEST_PATH );

		}

		if (isRequestPathVariablesGetFirstCall( inv )) {
			String key = extractStringArgument( inv, 0 );
			String defaultVal = findOrElseDefaultValue( inv );
			addParamInfo( handlerInfo, key, defaultVal, inv, LayerPosition.REQUEST_PATH );

		}

		if (isRequestPathVariablesGetOrDefaultCall( inv )) {
			String key = extractStringArgument( inv, 1 );
			// String defaultVal = findOrElseDefaultValue( inv );
			addParamInfo( handlerInfo, key, null, inv, LayerPosition.REQUEST_PATH );

		}

		// request body 파싱
		// request.bodyToMono(Xxx.class), request.bodyToFlux(Xxx.class)
		boolean isBodyToXCall = isBodyToXCall( inv );
		// accountService.validateSignatureAndParseBody(request, Xxx.class)
		boolean isValidateSignatureAndParseBodyCall = isValidateSignatureAndParseBodyCall( inv );

		if (isBodyToXCall || isValidateSignatureAndParseBodyCall) {
			int targetIndex = isBodyToXCall ? 0 : 1;
			CtExpression<?> arg = inv.getArguments().get( targetIndex );
			Class<?> bodyClass = extractClassArgument( inv, targetIndex );
			CtTypeReference<?> bodyClassRef = extractTypeRefArgument( inv, targetIndex );

			HandlerInfo.Info requestBodyInfo = new HandlerInfo.Info();

			requestBodyInfo.setType( bodyClass );
			requestBodyInfo.setTypeRef( bodyClassRef );

			parseClassFields( arg.getFactory().Type().createReference( bodyClass ), requestBodyInfo );

			if (! arg.getReferencedTypes().isEmpty()) {

				var refs = inv
					.getArguments()
					.get( targetIndex )
					.getReferencedTypes()
					.stream()
					.filter(
						e -> ! "Object".equals( e.getSimpleName() ) && ! bodyClass.getSimpleName().equals( e.getSimpleName() )
					)
					.toList();
				refs.forEach( e -> {
					parseClassFields( e, requestBodyInfo );

				} );
				requestBodyInfo
					.setGenericTypes(
						refs
							.stream()
							.map( e -> {
								var generic = buildParamInfoFromTypeRef( e );
								generic.setPosition( LayerPosition.GENERIC );
								return generic;

							} )
							.filter( e -> ! e.getType().equals( Object.class ) )
							.toList()
					);

			}

			handlerInfo.getRequestBodyInfo().put( bodyClass.getSimpleName(), requestBodyInfo );

		}

		// response 파싱
		// ok().contentType(...).body(...)
		// 이런 체인 호출을 따라 올라가 body, bodyValue에 전달된 타입 파악
		if (isOkResponseCallChain( inv )) {
			// body(...) 또는 bodyValue(...) 호출 찾아 Response body 정보 추출
			// MediaType, ResponseWrapper 등
			parseResponseBodyFromOkChain( inv, handlerInfo );

		}

	}

	private void addParamInfo(
		HandlerInfo handlerInfo, String key, CtInvocation<?> inv, LayerPosition position
	) {

		addParamInfo( handlerInfo, key, null, inv, position );

	}

	private void addParamInfo(
		HandlerInfo handlerInfo, String key, String defaultValue, CtInvocation<?> inv, LayerPosition position
	) {

		CtLocalVariable<?> variable = determineFinalAssignedType( inv );
		CtTypeReference<?> finalTypeRef = variable == null ? inv.getType() : variable.getType();
		// Class<?> finalType = loadClassFromTypeReference( finalTypeRef );
		HandlerInfo.Info pInfo = buildParamInfoFromTypeRef( finalTypeRef );
		pInfo.setName( key );
		pInfo.setDefaultValue( defaultValue );
		pInfo.setRequired( defaultValue != null && ! defaultValue.isBlank() );
		pInfo.setPosition( position );
		// pInfo.setType( finalType );
		applyAnnotationsToParamInfo( variable, pInfo );

		if (pInfo.getPosition().equals( LayerPosition.REQUEST_STRING )) {
			handlerInfo.getQueryStringInfo().put( pInfo.getName(), pInfo );

		} else if (pInfo.getPosition().equals( LayerPosition.REQUEST_PATH )) {
			handlerInfo.getPathVariableInfo().put( pInfo.getName(), pInfo );

		}

	}

	/**
	 * inv: request.queryParam("accountName") 같은 CtInvocation
	 * 최종적으로 이 inv 결과가 대입되는 로컬 변수(예: Integer aaa = ...)를 찾아 해당 로컬 변수 타입 반환
	 */
	private CtLocalVariable<?> determineFinalAssignedType(
		CtInvocation<?> inv
	) {

		CtElement current = inv;

		while (current != null) {

			if (current instanceof CtLocalVariable<?> lv) {
				// lv.getType()가 최종 타입
				return lv;

			} else if (current instanceof CtAssignment<?, ?> assign
				// 대입문의 경우 대입 대상 변수 타입 확인
				&& assign.getAssigned() instanceof CtVariableWrite<?> varWrite
				// varWrite.getVariable()에서 선언된 변수 찾아 타입 확인
				&& varWrite.getVariable().getDeclaration() instanceof CtLocalVariable<?> localVar) { return localVar; }

			current = current.getParent();

		}

		// 로컬 변수나 대입문의 상위 구조를 못찾으면 invocation 자체의 리턴 타입 사용
		return null;
		// return inv.getType();

	}


	/**
	 * CtTypeReference로부터 Class<?>를 로딩
	 */
	private Class<?> loadClassFromTypeReference(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) {
			return Object.class;

		}

		String qName = typeRef.getQualifiedName();

		if (qName == null || qName.isBlank()) {
			return Object.class;

		}

		// Primitive 타입 처리
		switch (qName) {
			case "boolean":
				return boolean.class;
			case "byte":
				return byte.class;
			case "short":
				return short.class;
			case "int":
				return int.class;
			case "long":
				return long.class;
			case "float":
				return float.class;
			case "double":
				return double.class;
			case "char":
				return char.class;
			case "void":
				return void.class;
			default:
				// primitive 타입이 아닌 경우 Class.forName으로 로딩 시도
				break;

		}

		try {
			return Class.forName( qName );

		} catch (ClassNotFoundException e) {
			return Object.class;

		}

	}

	/**
	 * CtTypeReference를 ParamInfo로 변환하는 메서드.
	 * 제너릭 타입이 있을 경우 재귀적으로 처리하여 genericTypes 리스트에 추가.
	 */
	private HandlerInfo.Info buildParamInfoFromTypeRef(
		CtTypeReference<?> typeRef
	) {

		typeRef = resolveSourceBackedTypeReference( typeRef );

		HandlerInfo.Info pInfo = new HandlerInfo.Info();

		if (typeRef == null) {
			pInfo.setType( Object.class );
			return pInfo;

		}

		// 기본 타입 설정
		Class<?> rawType = loadClassFromTypeReference( typeRef );
		pInfo.setType( rawType );
		pInfo.setTypeRef( typeRef );

		// 제너릭 타입 파라미터 처리
		List<CtTypeReference<?>> actualTypeArgs = typeRef.getActualTypeArguments();

		if (actualTypeArgs != null && ! actualTypeArgs.isEmpty()) {
			List<HandlerInfo.Info> genericParams = new ArrayList<>();

			for (CtTypeReference<?> argRef : actualTypeArgs) {
				CtTypeReference<?> resolvedArgRef = resolveSourceBackedTypeReference( argRef );

				HandlerInfo.Info genericParamInfo = buildParamInfoFromTypeRef( resolvedArgRef );
				genericParamInfo.setPosition( LayerPosition.GENERIC );

				if (RouteUtil.isPojo( genericParamInfo.getType() )) {
					parseClassFields( resolvedArgRef, genericParamInfo );

					if (genericParamInfo.getFields().isEmpty()) {
						parseClassFields(
							resolvedArgRef.getFactory().Type().createReference( genericParamInfo.getType() ),
							genericParamInfo
						);

					}

				}

				genericParams.add( genericParamInfo );

			}

			pInfo.setGenericTypes( genericParams );

		}

		return pInfo;

	}

	// request.queryParams() 자체인지
	private boolean isRequestQueryParamsCall(
		CtInvocation<?> inv
	) {

		return inv != null && matchesCall( inv, "queryParams" ) && isTargetRequest( inv );

	}

	// request.queryParams().getFirst("x")
	private boolean isRequestQueryParamsGetFirstDirectCall(
		CtInvocation<?> inv
	) {

		if (inv == null)
			return false;
		if (! "getFirst".equals( inv.getExecutable().getSimpleName() ))
			return false;
		if (inv.getArguments().size() != 1)
			return false;

		CtExpression<?> target = inv.getTarget();
		return (target instanceof CtInvocation<?> tInv) && isRequestQueryParamsCall( tInv );

	}

	// request.queryParams().get("x")
	private boolean isRequestQueryParamsGetDirectCall(
		CtInvocation<?> inv
	) {

		if (inv == null)
			return false;
		if (! "get".equals( inv.getExecutable().getSimpleName() ))
			return false;
		if (inv.getArguments().size() != 1)
			return false;

		CtExpression<?> target = inv.getTarget();
		return (target instanceof CtInvocation<?> tInv) && isRequestQueryParamsCall( tInv );

	}

	// request.queryParams().getOrDefault("x", ...)
	private boolean isRequestQueryParamsGetOrDefaultDirectCall(
		CtInvocation<?> inv
	) {

		if (inv == null)
			return false;
		if (! "getOrDefault".equals( inv.getExecutable().getSimpleName() ))
			return false;
		if (inv.getArguments().size() != 2)
			return false;

		CtExpression<?> target = inv.getTarget();
		return (target instanceof CtInvocation<?> tInv) && isRequestQueryParamsCall( tInv );

	}

	private boolean isQueryParamsGetCall(
		CtInvocation<?> inv
	) {

		return isQueryParamsVar( inv ) && inv.getExecutable().getSimpleName().equals( "get" ) && inv.getArguments().size() == 1;

	}

	private boolean isQueryParamsGetFirstCall(
		CtInvocation<?> inv
	) {

		return isQueryParamsVar( inv ) && inv.getExecutable().getSimpleName().equals( "getFirst" ) && inv.getArguments().size() == 1;

	}

	private boolean isQueryParamsGetOrDefaultCall(
		CtInvocation<?> inv
	) {

		return isQueryParamsVar( inv ) && inv.getExecutable().getSimpleName().equals( "getOrDefault" ) && inv.getArguments().size() == 2;

	}

	private boolean isQueryParamsVar(
		CtInvocation<?> inv
	) {

		// inv의 target이 local variable이고, 그 이름이 queryParamsVars에 등록되어 있으면 true
		CtExpression<?> target = inv.getTarget();

		if (target != null) {
			String targetStr = target.toString();
			// target이 예를 들어 "anyVar" 형태일 경우
			return queryParamsVars.containsKey( targetStr );

		}

		return false;

	}

	private boolean isPathsParamsVar(
		CtInvocation<?> inv
	) {

		// inv의 target이 local variable이고, 그 이름이 queryParamsVars에 등록되어 있으면 true
		CtExpression<?> target = inv.getTarget();

		if (target != null) {
			String targetStr = target.toString();
			// target이 예를 들어 "anyVar" 형태일 경우
			return pathsParamsVars.containsKey( targetStr );

		}

		return false;

	}


	private boolean isRequestQueryParamCall(
		CtInvocation<?> inv
	) {

		// inv가 request.queryParam("xxx") 형태인지 체크
		return matchesCall( inv, "queryParam" ) && isTargetRequest( inv );

	}


	private boolean isRequestPathVariableCall(
		CtInvocation<?> inv
	) {

		return matchesCall( inv, "pathVariable" ) && isTargetRequest( inv );

	}

	private boolean isRequestPathVariablesGetCall(
		CtInvocation<?> inv
	) {

		return isPathsParamsVar( inv ) && inv.getExecutable().getSimpleName().equals( "get" ) && inv.getArguments().size() == 1;

	}

	private boolean isRequestPathVariablesGetFirstCall(
		CtInvocation<?> inv
	) {

		return isPathsParamsVar( inv ) && inv.getExecutable().getSimpleName().equals( "getFirst" ) && inv.getArguments().size() == 1;

	}

	private boolean isRequestPathVariablesGetOrDefaultCall(
		CtInvocation<?> inv
	) {

		return isPathsParamsVar( inv ) && inv.getExecutable().getSimpleName().equals( "getOrDefault" ) && inv.getArguments().size() == 2;

	}


	private boolean isBodyToXCall(
		CtInvocation<?> inv
	) {

		// bodyToMono(Xxx.class), bodyToFlux(Xxx.class)
		String name = inv.getExecutable().getSimpleName();
		return (name.equals( "bodyToMono" ) || name.equals( "bodyToFlux" )) && isTargetRequest( inv );

	}

	private boolean isValidateSignatureAndParseBodyCall(
		CtInvocation<?> inv
	) {

		// accountService.validateSignatureAndParseBody(request, Xxx.class)
		return inv.getArguments().size() > 1 && inv.getExecutable().getSimpleName().equals( "validateSignatureAndParseBody" );

	}

	private boolean isOkResponseCallChain(
		CtInvocation<?> inv
	) {

		// ok().contentType(...).body(...) 체인 일부인지 파악
		String name = inv.getExecutable().getSimpleName();
		return name.equals( "ok" ) || name.equals( "contentType" ) || name.equals( "body" ) || name.equals( "bodyValue" );

	}

	private boolean matchesCall(
		CtInvocation<?> inv, String methodName
	) {

		return inv.getExecutable().getSimpleName().equals( methodName );

	}

	private boolean isTargetRequest(
		CtInvocation<?> inv
	) {

		CtExpression<?> target = inv.getTarget();
		if (target == null)
			return false;

		CtTypeReference<?> t = target.getType();
		// 타입이 ServerRequest면 통과 (변수명이 뭐든)
		if (t != null && "ServerRequest".equals( t.getSimpleName() ))
			return true;

		// 또는 "request"라는 이름도 허용
		String s = target.toString();
		return "request".equals( s );

	}

	private String extractStringArgument(
		CtInvocation<?> inv, int index
	) {

		if (inv.getArguments().size() > index) {
			CtExpression<?> arg = inv.getArguments().get( index );

			if (arg instanceof CtLiteral<?> lit) {

				if (lit.getValue() instanceof String str) {
					return str;

				}

			} else {
				return arg.toString();

			}

		}

		return null;

	}

	/**
	 * inv를 기준으로 orElse(...) 호출을 찾고, orElse 인자를 defaultValue로 반환.
	 * orElse(...)가 없으면 null 반환.
	 */
	private String findOrElseDefaultValue(
		CtInvocation<?> inv
	) {

		// inv가 queryParam(...) 호출이라면 inv.getParent()나 inv.getTarget()를 추적하여 orElse 호출 검사
		CtExpression<?> target = inv.getTarget();

		if (target instanceof CtInvocation<?> parentInv) {

			// parentInv가 orElse 호출인지 체크
			if (isOrElseCall( parentInv )) {
				// orElse(...)의 인자 추출
				return extractStringArgument( parentInv, 0 );

			}

		}

		// target이 orElse가 아닐 경우, 추가로 parent를 따라 올라가며 확인할 수도 있음
		CtElement current = inv.getParent();

		while (current != null) {

			if (current instanceof CtInvocation<?> upInv) {

				if (isOrElseCall( upInv )) { return extractStringArgument( upInv, 0 ); }

			}

			current = current.getParent();

		}

		return null;

	}

	/**
	 * orElse(...) 호출 식별 메서드
	 */
	private boolean isOrElseCall(
		CtInvocation<?> inv
	) {

		return inv.getExecutable().getSimpleName().equals( "orElse" ) && inv.getArguments().size() == 1;

	}

	private Class<?> extractClassArgument(
		CtInvocation<?> inv, int index
	) {

		if (inv.getArguments().size() <= index) {
			return Object.class; // 인덱스 범위 밖이면 기본 Object.class 반환

		}

		CtExpression<?> arg = inv.getArguments().get( index );

		// Xxx.class 형태는 일반적으로 CtFieldAccess 형태이며,
		// target이 CtTypeAccess로, CtTypeAccess에서 CtTypeReference를 얻을 수 있음
		if (arg instanceof CtFieldAccess<?> fieldAccess) {

			// 예: Xxx.class 에서 fieldAccess.getVariable().getSimpleName()는 "class"
			// fieldAccess.getTarget()는 CtTypeAccess 형태일 것.
			if ("class".equals( fieldAccess.getVariable().getSimpleName() )) {
				CtExpression<?> target = fieldAccess.getTarget();


				if (target instanceof CtTypeAccess<?> typeAccess) {
					CtTypeReference<?> typeRef = typeAccess.getAccessedType();

					if (typeRef != null) {

						try {
							return loadClassFromTypeReference( typeRef );
							// return typeRef.getActualClass();

						} catch (Exception e) {
							e.printStackTrace();
							return Object.class;

						}

					}

				}

			}

		}

		// Xxx.class 형태가 아닌 경우 기본값 반환
		return Object.class;

	}

	private CtTypeReference<?> extractTypeRefArgument(
		CtInvocation<?> inv, int index
	) {

		if (inv.getArguments().size() <= index)
			return null;

		CtExpression<?> arg = inv.getArguments().get( index );

		if (arg instanceof CtFieldAccess<?> fa && "class".equals( fa.getVariable().getSimpleName() ) && fa.getTarget() instanceof CtTypeAccess<?> ta) {
			return resolveSourceBackedTypeReference( ta.getAccessedType() );

		}

		if (arg instanceof CtNewClass<?> newClass && newClass.getAnonymousClass() != null) {
			CtTypeReference<?> superClass = newClass.getAnonymousClass().getSuperclass();

			if (superClass != null && "org.springframework.core.ParameterizedTypeReference".equals( superClass.getQualifiedName() ) && superClass.getActualTypeArguments() != null && ! superClass
				.getActualTypeArguments()
				.isEmpty()) {
				return resolveSourceBackedTypeReference( superClass.getActualTypeArguments().get( 0 ) );

			}

		}

		CtTypeReference<?> argTypeRef = resolveSourceBackedTypeReference( arg.getType() );

		if (argTypeRef != null && "org.springframework.core.ParameterizedTypeReference".equals( argTypeRef.getQualifiedName() ) && argTypeRef.getActualTypeArguments() != null && ! argTypeRef
			.getActualTypeArguments()
			.isEmpty()) {
			return resolveSourceBackedTypeReference( argTypeRef.getActualTypeArguments().get( 0 ) );

		}

		return null;

	}

	private void parseResponseBodyFromOkChain(
		CtInvocation<?> inv, HandlerInfo handlerInfo
	) {

		// ok().contentType(...).body(...) or bodyValue(...)
		String name = inv.getExecutable().getSimpleName();

		// @ResponseBody가 붙어있으면 그게 최우선
		if ((name.equals( "body" ) || name.equals( "bodyValue" )) && ! inv.getArguments().isEmpty()) {
			CtExpression<?> firstArgForAnn = inv.getArguments().get( 0 );
			CtAnnotation<?> rbAnn = findResponseBodyAnnotationRecursive( firstArgForAnn );

			if (rbAnn != null) {
				HandlerInfo.Info annotated = buildResponseBodyInfoFromAnnotation( rbAnn, inv.getFactory() );

				if (annotated != null) {
					hasResponseBodyAnnotationOverride = true;
					handlerInfo.getResponseBodyInfo().clear();
					String key = (annotated.getType() != null && annotated.getType() != Object.class)
						? annotated.getType().getSimpleName()
						: (annotated.getTypeRef() != null ? annotated.getTypeRef().getSimpleName() : "Object");
					handlerInfo.getResponseBodyInfo().put( key, annotated );
					return;

				}

			}

			// 이미 @ResponseBody로 확정된 상태면, 추론으로 들어오는 responseBody는 무시
			if (hasResponseBodyAnnotationOverride) { return; }

		}

		if (name.equals( "body" ) && ! inv.getArguments().isEmpty()) {


			CtExpression<?> firstArg = inv.getArguments().get( 0 );

			CtTypeReference<?> firstArgTypeRef = firstArg.getType();
			boolean isParseFailedFlag = false;
			// [최종 디버깅] =================================================================
			// String typeName = (firstArgTypeRef != null) ? firstArgTypeRef.getQualifiedName() : "NULL";
			// System.out
			// .println(
			// "[FINAL_DEBUG] Type Inferred for .body() argument: " + typeName + "---" + (firstArgTypeRef ==
			// null ? "[empty]"
			// : firstArgTypeRef
			// .getReferencedTypes())
			// );
			// ==============================================================================

			CtInvocation<?> responseFactoryInvocation = null;


			if (firstArg instanceof CtInvocation<?> ctInvocation) {
				responseFactoryInvocation = ctInvocation;

			} else {
				List<CtInvocation<?>> nestedInvocations = firstArg.getElements( new TypeFilter<>( CtInvocation.class ) );

				if (! nestedInvocations.isEmpty()) {
					responseFactoryInvocation = nestedInvocations.get( nestedInvocations.size() - 1 );

				}

			}

			if (responseFactoryInvocation != null) {
				CtTypeReference<?> payloadTypeRef = manuallyInferResponseType( responseFactoryInvocation );


				if (payloadTypeRef != null && firstArgTypeRef != null) {
					CtTypeReference<?> resolvedFirstArgTypeRef = resolveSourceBackedTypeReference( firstArgTypeRef );


					if (resolvedFirstArgTypeRef != null && "reactor.core.publisher.Mono"
						.equals( resolvedFirstArgTypeRef.getQualifiedName() ) && resolvedFirstArgTypeRef.getActualTypeArguments().size() == 1) {

						CtTypeReference<?> outerGenericRef = resolveSourceBackedTypeReference(
							resolvedFirstArgTypeRef.getActualTypeArguments().get( 0 )
						);

						if (outerGenericRef != null && outerGenericRef.getActualTypeArguments().size() == 1) {

							CtTypeReference<?> wrapperPayloadRef = resolveSourceBackedTypeReference(
								outerGenericRef.getActualTypeArguments().get( 0 )
							);


							if (wrapperPayloadRef != null && ("reactor.core.publisher.Mono".equals( wrapperPayloadRef.getQualifiedName() ) || "reactor.core.publisher.Flux"
								.equals( wrapperPayloadRef.getQualifiedName() )) && (wrapperPayloadRef.getActualTypeArguments() == null || wrapperPayloadRef.getActualTypeArguments().isEmpty())) {

								CtTypeReference<?> repairedWrapperPayloadRef = wrapperPayloadRef.clone();
								repairedWrapperPayloadRef.setActualTypeArguments( List.of( resolveSourceBackedTypeReference( payloadTypeRef ) ) );

								CtTypeReference<?> repairedOuterGenericRef = outerGenericRef.clone();
								repairedOuterGenericRef.setActualTypeArguments( List.of( repairedWrapperPayloadRef ) );

								CtTypeReference<?> repairedFirstArgTypeRef = resolvedFirstArgTypeRef.clone();
								repairedFirstArgTypeRef.setActualTypeArguments( List.of( repairedOuterGenericRef ) );

								firstArgTypeRef = repairedFirstArgTypeRef;
								isParseFailedFlag = true;


							}

						}

					}

				}

			}

			if (firstArgTypeRef == null && responseFactoryInvocation != null) {
				firstArgTypeRef = manuallyInferResponseType( responseFactoryInvocation );
				isParseFailedFlag = true;

			}

			HandlerInfo.Info rawResponseInfo = buildParamInfoFromTypeRef( firstArgTypeRef );
			rawResponseInfo.setPosition( LayerPosition.RESPONSE_BODY );

			HandlerInfo.Info pInfo = rawResponseInfo;
			Class<?> publisherType = rawResponseInfo.getType();

			// top-level 에서는 Mono만 벗기고, Flux는 유지해야 array 로 문서화된다.
			if (publisherType != null && Mono.class.equals( publisherType ) && ! rawResponseInfo.getGenericTypes().isEmpty()) {
				pInfo = rawResponseInfo.getGenericTypes().get( 0 );

			}

			boolean envelope = isEnvelopeInfo( pInfo );

			if (envelope) {
				CtTypeReference<?> envelopeTypeRef = pInfo.getTypeRef();

				if (envelopeTypeRef == null) {
					envelopeTypeRef = firstArgTypeRef;

				}

				parseClassFields( envelopeTypeRef, pInfo );

				pInfo.setPosition( LayerPosition.RESPONSE_BODY );

				handlerInfo
					.getResponseBodyInfo()
					.put(
						pInfo.getType().getSimpleName(),
						pInfo
					);

			} else if (isParseFailedFlag) {

				if (pInfo.getGenericTypes().isEmpty()) {
					handlerInfo.getResponseBodyInfo().put( pInfo.getType().getSimpleName(), pInfo );

				} else {
					handlerInfo
						.getResponseBodyInfo()
						.put(
							pInfo
								.getGenericTypes()
								.get( 0 )
								.getType()
								.getSimpleName(),
							pInfo
						);

				}

			} else {
				CtTypeReference<?> declaredElementTypeRef = (inv.getArguments().size() > 1)
					? extractTypeRefArgument( inv, 1 )
					: null;

				HandlerInfo.Info declaredElementInfo = null;

				if (declaredElementTypeRef != null) {
					declaredElementInfo = buildParamInfoFromTypeRef( declaredElementTypeRef );
					declaredElementInfo.setPosition( LayerPosition.GENERIC );

					if (declaredElementInfo.getTypeRef() != null && RouteUtil.isPojo( declaredElementInfo.getType() ) && declaredElementInfo.getFields().isEmpty()) {
						parseClassFields( declaredElementInfo.getTypeRef(), declaredElementInfo );

					}

				}

				HandlerInfo.Info finalInfo;

				if (publisherType != null && Flux.class.equals( publisherType )) {
					finalInfo = new HandlerInfo.Info();
					finalInfo.setType( Flux.class );
					finalInfo.setTypeRef( rawResponseInfo.getTypeRef() );
					finalInfo.setPosition( LayerPosition.RESPONSE_BODY );

					HandlerInfo.Info elementInfo = declaredElementInfo;

					if (elementInfo == null && ! rawResponseInfo.getGenericTypes().isEmpty()) {
						elementInfo = rawResponseInfo.getGenericTypes().get( 0 );

					}

					if (elementInfo == null) {
						elementInfo = new HandlerInfo.Info();
						elementInfo.setType( Object.class );

					}

					elementInfo.setPosition( LayerPosition.GENERIC );
					finalInfo.setGenericTypes( List.of( elementInfo ) );

				} else if (declaredElementInfo != null) {
					finalInfo = declaredElementInfo;
					finalInfo.setPosition( LayerPosition.RESPONSE_BODY );

				} else {
					finalInfo = pInfo;
					finalInfo.setPosition( LayerPosition.RESPONSE_BODY );

				}

				if (finalInfo.getTypeRef() != null && RouteUtil.isPojo( finalInfo.getType() ) && finalInfo.getFields().isEmpty()) {
					parseClassFields( finalInfo.getTypeRef(), finalInfo );

				}

				if (finalInfo.getGenericTypes().isEmpty()) {
					handlerInfo.getResponseBodyInfo().put( finalInfo.getType().getSimpleName(), finalInfo );

				} else {
					handlerInfo
						.getResponseBodyInfo()
						.put(
							finalInfo
								.getGenericTypes()
								.get( 0 )
								.getType()
								.getSimpleName(),
							finalInfo
						);

				}

			}

		} else if (name.equals( "bodyValue" )) {

			// bodyValue( Object value )
			if (! inv.getArguments().isEmpty()) {
				CtExpression<?> firstArg = inv.getArguments().get( 0 );
				CtTypeReference<?> valTypeRef = firstArg.getType();
				HandlerInfo.Info pInfo = buildParamInfoFromTypeRef( valTypeRef );

				handlerInfo.getResponseBodyInfo().put( pInfo.getType().getSimpleName(), pInfo );

				if (valTypeRef != null) {
					parseClassFields( valTypeRef, pInfo );

				}

				handlerInfo
					.getResponseBodyInfo()
					.put(
						(pInfo.getType() != null && pInfo.getType() != Object.class)
							? pInfo.getType().getSimpleName()
							: (valTypeRef != null ? valTypeRef.getSimpleName() : "Object"),
						pInfo
					);

			}

		} else if (name.equals( "contentType" )) {
			handlerInfo
				.setContentMediaTypes(
					inv
						.getArguments()
						.stream()
						.filter( e -> e.getType() != null && e.getType().getActualClass().equals( MediaType.class ) && e instanceof CtFieldAccess )
						.map( e -> e.toString() )
						.collect( Collectors.toList() )
				);

		}

	}

	private CtTypeReference<?> tryInferRawReactorTypeFromVariableInitializer(
		CtExpression<?> dataArgument, CtTypeReference<?> rawReactorTypeRef
	) {

		if (dataArgument == null || rawReactorTypeRef == null) {
			return null;

		}

		String rawQualifiedName = rawReactorTypeRef.getQualifiedName();

		if (! "reactor.core.publisher.Mono".equals( rawQualifiedName ) && ! "reactor.core.publisher.Flux".equals( rawQualifiedName )) {
			return null;

		}

		CtVariable<?> varDecl = extractVariableDeclaration( dataArgument );

		if (! (varDecl instanceof CtLocalVariable<?> localVar)) { return null; }

		CtExpression<?> init = localVar.getDefaultExpression();

		if (init == null) {
			return null;

		}

		List<CtInvocation<?>> nestedInvocations = init.getElements( new TypeFilter<>( CtInvocation.class ) );
		CtTypeReference<?> bestMatch = null;

		for (int i = 0; i < nestedInvocations.size(); i++) {
			CtInvocation<?> nestedInvocation = nestedInvocations.get( i );
			CtTypeReference<?> nestedTypeRef = resolveSourceBackedTypeReference( nestedInvocation.getType() );

			if (nestedTypeRef == null) {
				continue;

			}

			if (! rawQualifiedName.equals( nestedTypeRef.getQualifiedName() )) {
				continue;

			}

			if (nestedTypeRef.getActualTypeArguments() == null || nestedTypeRef.getActualTypeArguments().isEmpty()) {
				continue;

			}

			bestMatch = nestedTypeRef;

		}

		return bestMatch;

	}

	private CtTypeReference<?> resolveActualArgumentTypeForGenericInference(
		CtExpression<?> argumentExpression
	) {

		if (argumentExpression == null) {
			return null;

		}

		CtTypeReference<?> actualTypeRef = resolveSourceBackedTypeReference( argumentExpression.getType() );

		if (actualTypeRef == null) {
			return null;

		}

		String qName = actualTypeRef.getQualifiedName();

		if (("reactor.core.publisher.Mono".equals( qName ) || "reactor.core.publisher.Flux"
			.equals( qName )) && (actualTypeRef.getActualTypeArguments() == null || actualTypeRef.getActualTypeArguments().isEmpty())) {

			CtTypeReference<?> repairedTypeRef = tryInferRawReactorTypeFromVariableInitializer( argumentExpression, actualTypeRef );

			if (repairedTypeRef != null) {
				return resolveSourceBackedTypeReference( repairedTypeRef );

			}

		}

		return actualTypeRef;

	}

	private void collectTypeParameterNames(
		CtTypeReference<?> typeRef, Set<String> names
	) {

		typeRef = resolveSourceBackedTypeReference( typeRef );

		if (typeRef == null) {
			return;

		}

		if (typeRef instanceof CtTypeParameterReference typeParameterReference) {
			String typeParameterName = typeParameterReference.getSimpleName();

			if (typeParameterReference.getDeclaration() != null) {
				typeParameterName = typeParameterReference.getDeclaration().getSimpleName();

			}

			if (typeParameterName != null && ! typeParameterName.isBlank()) {
				names.add( typeParameterName );

			}

			return;

		}

		List<CtTypeReference<?>> actualTypeArguments = typeRef.getActualTypeArguments();

		if (actualTypeArguments == null || actualTypeArguments.isEmpty()) {
			return;

		}

		for (CtTypeReference<?> actualTypeArgument : actualTypeArguments) {
			collectTypeParameterNames( actualTypeArgument, names );

		}

	}

	private void bindTypeParameters(
		CtTypeReference<?> formalTypeRef, CtTypeReference<?> actualTypeRef, Map<String, CtTypeReference<?>> bindings
	) {

		formalTypeRef = resolveSourceBackedTypeReference( formalTypeRef );
		actualTypeRef = resolveSourceBackedTypeReference( actualTypeRef );

		if (formalTypeRef == null || actualTypeRef == null) {
			return;

		}

		if (formalTypeRef instanceof CtTypeParameterReference typeParameterReference) {
			String typeParameterName = typeParameterReference.getSimpleName();

			if (typeParameterReference.getDeclaration() != null) {
				typeParameterName = typeParameterReference.getDeclaration().getSimpleName();

			}

			if (typeParameterName != null && ! typeParameterName.isBlank()) {
				bindings.putIfAbsent( typeParameterName, actualTypeRef );

			}

			return;

		}

		String formalQualifiedName = formalTypeRef.getQualifiedName();
		String actualQualifiedName = actualTypeRef.getQualifiedName();

		if (formalQualifiedName == null || actualQualifiedName == null) {
			return;

		}

		if (! formalQualifiedName.equals( actualQualifiedName )) {
			return;

		}

		List<CtTypeReference<?>> formalTypeArguments = formalTypeRef.getActualTypeArguments();
		List<CtTypeReference<?>> actualTypeArguments = actualTypeRef.getActualTypeArguments();

		if (formalTypeArguments == null || actualTypeArguments == null) {
			return;

		}

		int loopSize = Math.min( formalTypeArguments.size(), actualTypeArguments.size() );

		for (int i = 0; i < loopSize; i++) {
			bindTypeParameters( formalTypeArguments.get( i ), actualTypeArguments.get( i ), bindings );

		}

	}

	private CtTypeReference<?> extractBoundTypeFromReturnType(
		CtTypeReference<?> returnTypeRef, Map<String, CtTypeReference<?>> bindings
	) {

		returnTypeRef = resolveSourceBackedTypeReference( returnTypeRef );

		if (returnTypeRef == null) {
			return null;

		}

		if (returnTypeRef instanceof CtTypeParameterReference typeParameterReference) {
			String typeParameterName = typeParameterReference.getSimpleName();

			if (typeParameterReference.getDeclaration() != null) {
				typeParameterName = typeParameterReference.getDeclaration().getSimpleName();

			}

			return bindings.get( typeParameterName );

		}

		List<CtTypeReference<?>> actualTypeArguments = returnTypeRef.getActualTypeArguments();

		if (actualTypeArguments == null || actualTypeArguments.isEmpty()) {
			return null;

		}

		for (CtTypeReference<?> actualTypeArgument : actualTypeArguments) {
			CtTypeReference<?> boundTypeRef = extractBoundTypeFromReturnType( actualTypeArgument, bindings );

			if (boundTypeRef != null) {
				return boundTypeRef;

			}

		}

		return null;

	}

	/**
	 * spoon으로 제너릭 타입을 정확하게 가져올 수 없을 때 수동 파서
	 * 
	 * @param factoryMethodCall
	 * 
	 * @return
	 */
	private CtTypeReference<?> manuallyInferResponseType(
		CtInvocation<?> factoryMethodCall
	) {

		CtExecutableReference<?> executableReference = factoryMethodCall.getExecutable();

		if (executableReference == null) {
			return null;

		}

		CtType<?> declaringType = resolveDeclaringType( executableReference );

		if (declaringType == null) {
			return null;

		}

		List<CtMethod<?>> candidates = declaringType
			.getMethods()
			.stream()
			.filter( m -> m.getSimpleName().equals( executableReference.getSimpleName() ) )
			.filter( m -> m.getParameters().size() == factoryMethodCall.getArguments().size() )
			.collect( Collectors.toList() );

		for (int c = 0; c < candidates.size(); c++) {
			CtMethod<?> candidate = candidates.get( c );

			CtTypeReference<?> returnTypeRef = resolveSourceBackedTypeReference( candidate.getType() );

			if (returnTypeRef == null) {
				continue;

			}

			Set<String> returnTypeParameterNames = new HashSet<>();
			collectTypeParameterNames( returnTypeRef, returnTypeParameterNames );

			if (returnTypeParameterNames.isEmpty()) {
				continue;

			}

			Map<String, CtTypeReference<?>> bindings = new HashMap<>();

			int loopSize = Math.min( candidate.getParameters().size(), factoryMethodCall.getArguments().size() );

			for (int i = 0; i < loopSize; i++) {
				CtTypeReference<?> formalParameterTypeRef = resolveSourceBackedTypeReference(
					candidate.getParameters().get( i ).getType()
				);
				CtExpression<?> actualArgumentExpression = factoryMethodCall.getArguments().get( i );
				CtTypeReference<?> actualArgumentTypeRef = resolveActualArgumentTypeForGenericInference( actualArgumentExpression );

				bindTypeParameters( formalParameterTypeRef, actualArgumentTypeRef, bindings );

			}

			boolean allReturnTypeParametersBound = returnTypeParameterNames
				.stream()
				.allMatch( bindings::containsKey );

			if (! allReturnTypeParametersBound) {
				continue;

			}

			CtTypeReference<?> boundTypeRef = extractBoundTypeFromReturnType( returnTypeRef, bindings );

			if (boundTypeRef != null) {
				return resolveSourceBackedTypeReference( boundTypeRef );

			}

		}

		return null;

	}

	// JDK 컨테이너 타입들 (List, Map, Optional 등) 필터용
	private boolean isJdkContainerType(
		Class<?> clazz
	) {

		if (clazz == null) { return false; }

		String pkg = clazz.getPackageName();

		if (! pkg.startsWith( "java." )) { return false; }

		return java.util.Collection.class.isAssignableFrom( clazz ) || java.util.Map.class.isAssignableFrom( clazz ) || java.util.Optional.class.equals( clazz );

	}

	// "이 Info가 제너릭 래핑 타입(Envelope) 역할이냐?"
	private boolean isEnvelopeInfo(
		HandlerInfo.Info info
	) {

		if (info == null || info.getType() == null) { return false; }

		Class<?> clazz = info.getType();

		// Reactor는 이미 따로 언래핑하고 있으니 제외
		if (isReactorType( clazz )) { return false; }

		// JDK 컨테이너(List/Map/Optional)는 우리가 말하는 'Envelope'가 아님
		if (isJdkContainerType( clazz )) { return false; }

		// 제너릭 타입 파라미터가 실제로 파싱되어 있어야 "T를 감싸는 무언가"라고 볼 수 있음
		return info.getGenericTypes() != null && ! info.getGenericTypes().isEmpty();

	}

	private boolean isIgnoredResponseTypeRef(
		CtTypeReference<?> ref, Class<?> envelopeClass
	) {

		if (ref == null) { return true; }

		String simple = ref.getSimpleName();

		if (simple == null) { return true; }

		simple = simple.trim();

		// 완전한 Object 타입은 버림
		if ("Object".equals( simple )) { return true; }

		// Reactor / Sinks 타입은 버림
		if ("Flux".equals( simple ) || "Mono".equals( simple ) || "Sinks".equals( simple )) { return true; }

		// Envelope 타입 자기 자신은 버림
		if (envelopeClass != null) {

			if (envelopeClass.getSimpleName().equals( simple )) { return true; }

			String qName = ref.getQualifiedName();

			if (qName != null && envelopeClass.getName().equals( qName )) { return true; }

		}

		return false;

	}

	private CtTypeReference<?> resolveGenericFieldType(
		CtTypeReference<?> ownerTypeRef, CtType<?> ownerTypeDecl, CtTypeReference<?> fieldType, HandlerInfo.Info ownerInfo
	) {

		fieldType = resolveSourceBackedTypeReference( fieldType );

		if (fieldType == null) {
			return null;

		}

		// ResponseWrapper<T>.data 같은 경우 T를 실제 타입 인자로 치환
		if (fieldType instanceof CtTypeParameterReference) {
			CtTypeParameterReference typeParamRef = (CtTypeParameterReference) fieldType;

			String typeParamName = typeParamRef.getSimpleName();

			if (typeParamRef.getDeclaration() != null) {
				typeParamName = typeParamRef.getDeclaration().getSimpleName();

			}

			List<spoon.reflect.declaration.CtTypeParameter> formalTypeParams = (ownerTypeDecl != null) ? ownerTypeDecl.getFormalCtTypeParameters() : List.of();

			List<CtTypeReference<?>> actualTypeArgs = (ownerTypeRef != null) ? ownerTypeRef.getActualTypeArguments() : List.of();

			for (int i = 0; i < formalTypeParams.size(); i++) {

				if (! formalTypeParams.get( i ).getSimpleName().equals( typeParamName )) {
					continue;

				}

				// 1) ownerTypeRef에 actual type arg가 있으면 그걸 최우선 사용
				if (actualTypeArgs.size() > i && actualTypeArgs.get( i ) != null) {
					return resolveSourceBackedTypeReference( actualTypeArgs.get( i ) );

				}

				// 2) fallback: 이미 buildParamInfoFromTypeRef로 파싱된 generic info 사용
				if (ownerInfo != null && ownerInfo.getGenericTypes() != null && ownerInfo.getGenericTypes().size() > i) {
					HandlerInfo.Info genericInfo = ownerInfo.getGenericTypes().get( i );

					if (genericInfo.getTypeRef() != null) {
						return resolveSourceBackedTypeReference( genericInfo.getTypeRef() );

					}

					if (genericInfo.getType() != null && genericInfo.getType() != Object.class && ownerTypeRef != null) {

						return ownerTypeRef
							.getFactory()
							.Type()
							.createReference( genericInfo.getType() );

					}

				}

			}

		}

		return fieldType;

	}

	private void parseClassFields(
		CtTypeReference<?> _wrapperRef, HandlerInfo.Info pInfo
	) {

		if (_wrapperRef == null) { return; }

		CtTypeReference<?> wrapperRef = resolveSourceBackedTypeReference( _wrapperRef );

		if (wrapperRef == null || wrapperRef.getQualifiedName() == null) { return; }

		// 이미 처리된 타입이면 중단 (순환 참조 방지)
		if (processedTypes.contains( wrapperRef.getQualifiedName() )) { return; }

		processedTypes.add( wrapperRef.getQualifiedName() );

		try {
			CtType<?> wrapperTypeDecl = resolveSourceBackedType( wrapperRef );

			if (wrapperTypeDecl == null) { return; }

			Optional.ofNullable( wrapperTypeDecl.getFields() ).orElse( Collections.emptyList() ).forEach( field -> {
				CtTypeReference<?> fieldType = resolveGenericFieldType(
					wrapperRef,
					wrapperTypeDecl,
					field.getType(),
					pInfo
				);

				if (fieldType == null || fieldType.getQualifiedName() == null) { return; }

				// 제너릭 타입이 자기 자신을 참조하는 경우 방지
				if (fieldType
					.getActualTypeArguments()
					.stream()
					.anyMatch( e -> wrapperRef.getQualifiedName().equals( e.getQualifiedName() ) )) {

					HandlerInfo.Info selfRefInfo = buildPartialInfo( field.getReference(), fieldType );
					pInfo.addField( field.getSimpleName(), selfRefInfo );
					return;

				}

				// 자기 자신을 직접 참조하는 경우 방지
				if (wrapperRef.getQualifiedName().equals( fieldType.getQualifiedName() )) {
					HandlerInfo.Info selfRefInfo = buildPartialInfo( field.getReference(), fieldType );
					pInfo.addField( field.getSimpleName(), selfRefInfo );
					return;

				}

				HandlerInfo.Info fieldInfo = buildParamInfoFromTypeRef( fieldType );
				fieldInfo.setPosition( LayerPosition.FIELDS );

				if (fieldInfo.getName() == null) {
					fieldInfo.setName( field.getSimpleName() );

				}

				Class<?> fieldClass = fieldInfo.getType();

				if (fieldClass != null && fieldClass.isEnum()) {
					fieldInfo.setExample( RouteUtil.parserEnumValues( fieldClass ).toString() );

				} else {
					CtType<?> nestedTypeDecl = resolveSourceBackedType( fieldType );
					String qName = fieldType.getQualifiedName();

					boolean canDescend = nestedTypeDecl != null && qName != null && ! qName.startsWith( "java." ) && ! qName.startsWith( "javax." ) && ! qName.startsWith( "jakarta." ) && ! qName
						.startsWith( "reactor." );

					if (canDescend) {
						parseClassFields( fieldType, fieldInfo );

					}

				}

				pInfo.addField( field.getSimpleName(), fieldInfo );

			} );

		} finally {
			processedTypes.remove( wrapperRef.getQualifiedName() );

		}

	}


	/**
	 * "동일 타입"이거나 "자기 자신을 제너릭으로 포함"하는 경우,
	 * 필드 정보만 넣고 더 이상 파고들지 않기 위한 헬퍼 메서드
	 */
	private HandlerInfo.Info buildPartialInfo(
		CtFieldReference<?> field, CtTypeReference<?> fieldType
	) {

		HandlerInfo.Info info = new HandlerInfo.Info();
		info.setName( field.getSimpleName() );
		info.setType( loadClassFromTypeReference( fieldType ) );
		info.setTypeRef( fieldType );
		// 필요 시 필드 타입으로 설정, 혹은 Object.class 등
		// 아래처럼 확장 정보도 일부 넣어줄 수 있음
		info.setPosition( LayerPosition.FIELDS );
		return info;

	}

	private void unwrapReactorTypes(
		HandlerInfo.Info pInfo
	) {

		// genericTypes 내부 Mono/Flux 언래핑
		pInfo
			.setGenericTypes(
				pInfo
					.getGenericTypes()
					.stream()
					.map( this::unwrapIfReactorType )
					.collect( Collectors.toList() )
			);

		for (HandlerInfo.Info gi : pInfo.getGenericTypes()) {
			unwrapReactorTypes( gi );

		}

		// fields 내부 Mono/Flux 언래핑 + 실제 map 반영
		List<String> fieldNames = new ArrayList<>( pInfo.getFields().keySet() );

		for (String fieldName : fieldNames) {
			HandlerInfo.Info fieldInfo = pInfo.getFields().get( fieldName );

			if (fieldInfo == null) {
				continue;

			}

			HandlerInfo.Info unwrappedFieldInfo = unwrapIfReactorType( fieldInfo );
			unwrapReactorTypes( unwrappedFieldInfo );

			if (unwrappedFieldInfo != fieldInfo) {
				unwrappedFieldInfo.setName( fieldName );
				unwrappedFieldInfo.setPosition( LayerPosition.FIELDS );
				pInfo.getFields().put( fieldName, unwrappedFieldInfo );

			}

		}

	}

	// Mono나Flux인지 확인해서 언래핑하는 메서드
	private HandlerInfo.Info unwrapIfReactorType(
		HandlerInfo.Info pInfo
	) {

		if (pInfo.getType() != null) {
			String typeName = pInfo.getType().getName();

			if (("java.lang.Object".equals( typeName ) || "reactor.core.publisher.Mono".equals( typeName ) || "reactor.core.publisher.Flux".equals( typeName ) || "reactor.core.publisher.Sinks"
				.equals( typeName ))//
				&& ! pInfo.getGenericTypes().isEmpty()) {
				// Mono<T> 혹은 Flux<T>에서 T를 꺼낸다.
				HandlerInfo.Info inner = pInfo.getGenericTypes().get( 0 );
				// pInfo를 inner로 교체
				pInfo = inner;
				unwrapIfReactorType( pInfo );

			}

		}

		return pInfo;

	}

	private boolean isReactorType(
		Class<?> clazz
	) {

		return (Mono.class.equals( clazz ) || Flux.class.equals( clazz ));

	}

	private Class<?> determineExpressionType(
		CtExpression<?> expr
	) {

		CtTypeReference<?> typeRef = expr.getType();

		if (typeRef == null) {
			return Object.class;

		}

		return loadClassFromTypeReference( typeRef );

	}

	/**
	 * 변수나 파라미터에 @RequestQuery, @RequestPath 어노테이션이 있으면 ParamInfo에 반영
	 * 
	 * @return
	 */
	private void applyAnnotationsToParamInfo(
		CtVariable<?> var, HandlerInfo.Info pInfo
	) {

		if (var == null) {
			return;

		}

		CtAnnotation<?> requestQueryAnn = var.getAnnotation( var.getFactory().Type().createReference( SelectedRequestParam.class ) );

		if (requestQueryAnn != null) {
			overrideParamInfoWithAnnotation( pInfo, requestQueryAnn );
			pInfo.setPosition( LayerPosition.REQUEST_STRING );

		}

		CtAnnotation<?> requestPathAnn = var.getAnnotation( var.getFactory().Type().createReference( SelectedRequestPath.class ) );

		if (requestPathAnn != null) {
			overrideParamInfoWithAnnotation( pInfo, requestPathAnn );
			pInfo.setPosition( LayerPosition.REQUEST_PATH );

		}

	}

	private void overrideParamInfoWithAnnotation(
		HandlerInfo.Info pInfo, CtAnnotation<?> ann
	) {

		String key;
		String defaultValue;
		Boolean required;
		Boolean nullable;
		Class<?> typeClass;

		if (ann.getActualAnnotation() instanceof SelectedRequestParam requestParam) {
			key = requestParam.key();
			defaultValue = requestParam.defaultValue();
			required = requestParam.required();
			nullable = requestParam.nullable();
			typeClass = requestParam.type();

		} else if (ann.getActualAnnotation() instanceof SelectedRequestPath requestPath) {
			key = requestPath.key();
			defaultValue = requestPath.defaultValue();
			required = requestPath.required();
			nullable = requestPath.nullable();
			typeClass = requestPath.type();

		} else {
			return;

		}

		if (key != null && ! key.isBlank()) {
			pInfo.setName( key );

		}

		if (! defaultValue.isEmpty()) {
			pInfo.setDefaultValue( defaultValue );

		}

		if (required != null) {
			pInfo.setRequired( required );

		}

		if (nullable != null) {
			pInfo.setNullable( nullable );

		}

		// nullable 처리하려면 ParamInfo에 필드 추가 필요
		if (typeClass != Void.class && typeClass != void.class) {
			pInfo.setType( typeClass );

		}

		return;

	}

	// =========================
	// ResponseBody annotation
	// =========================

	private CtAnnotation<?> findResponseBodyAnnotationRecursive(
		CtExpression<?> expr
	) {

		if (expr == null)
			return null;

		// 1) 변수에 붙은 @ResponseBody 찾기 (local var / parameter)
		CtVariable<?> varDecl = extractVariableDeclaration( expr );

		if (varDecl != null) {
			CtAnnotation<?> ann = varDecl.getAnnotation( varDecl.getFactory().Type().createReference( SelectedResponseBody.class ) );
			if (ann != null)
				return ann;

		}

		// 2) 표현식이 invocation이면 (a) 메서드에 붙은 @ResponseBody (b) target/args 재귀
		if (expr instanceof CtInvocation<?> inv) {
			CtAnnotation<?> methodAnn = findResponseBodyOnInvokedMethod( inv );
			if (methodAnn != null)
				return methodAnn;

			if (inv.getTarget() instanceof CtExpression<?> t) {
				CtAnnotation<?> a = findResponseBodyAnnotationRecursive( t );
				if (a != null)
					return a;

			}

			for (CtExpression<?> a : inv.getArguments()) {
				CtAnnotation<?> x = findResponseBodyAnnotationRecursive( a );
				if (x != null)
					return x;

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
		if (execRef == null)
			return null;

		CtType<?> declaringType = resolveDeclaringType( execRef );
		if (declaringType == null)
			return null;

		var annType = inv.getFactory().Type().createReference( SelectedResponseBody.class );
		List<CtMethod<?>> candidates = declaringType
			.getMethods()
			.stream()
			.filter( m -> m.getSimpleName().equals( execRef.getSimpleName() ) )
			.toList();

		for (CtMethod<?> m : candidates) {
			CtAnnotation<?> ann = m.getAnnotation( annType );
			if (ann != null)
				return ann;

		}

		return null;

	}

	private HandlerInfo.Info buildResponseBodyInfoFromAnnotation(
		CtAnnotation<?> ann, Factory factory
	) {

		if (! (ann.getActualAnnotation() instanceof SelectedResponseBody rb))
			return null;
		Class<?> typeClass = rb.type();
		if (typeClass == null || typeClass == Void.class || typeClass == void.class)
			return null;

		CtTypeReference<?> typeRef = factory.Type().createReference( typeClass );
		HandlerInfo.Info info = buildParamInfoFromTypeRef( typeRef );
		info.setType( typeClass );
		info.setTypeRef( typeRef );
		info.setNullable( rb.nullable() );
		info.setPosition( LayerPosition.RESPONSE_BODY );

		// 필드 파싱 (POJO/record/프로젝트 패키지 등 기존 조건에 맞춰 확장)
		if (typeRef != null) {
			parseClassFields( typeRef, info );

		}

		return unwrapIfReactorType( info );

	}

	public static void main(
		String abc[]
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

		// @Bean + RouterFunction<ServerResponse> 메서드 찾기
		List<CtMethod<?>> routerMethods = model
			.getElements(
				(CtMethod<?> m) -> m.getAnnotations().stream().anyMatch( a -> a.getAnnotationType().getSimpleName().equals( "Bean" ) ) && m.getType().getSimpleName().contains( "RouterFunction" )
			);

		for (CtMethod<?> routerMethod : routerMethods) {
			String routeMethodName = routerMethod.getSimpleName();
			// System.out.println( "=== Parsing routes in method: " + routeMethodName + " ===" );

			// 해당 메서드 내 GET/POST/PUT/DELETE 호출 모두 찾기
			@SuppressWarnings("rawtypes")
			List<CtInvocation> httpCalls = routerMethod
				.getElements( new TypeFilter<>( CtInvocation.class ) )
				.stream()
				.filter( inv -> RouteParser.HTTP_METHODS.contains( inv.getExecutable().getSimpleName() ) )
				.toList();

			for (CtInvocation<?> httpCall : httpCalls) {
				RouteInfo info = RouteParser.extractRouteInfoFromHttpCall( httpCall, routeMethodName );
				HandlerParser aaa = new HandlerParser();
				HandlerInfo handlerInfo = aaa.parseHandler( info.getHandlerInfoCtExpression(), RouteUtil.convertPathToMethodName( info.getUrl() ) );
				CtExpression<?> xxx = info.getHandlerInfoCtExpression();

				// if (xxx instanceof CtLambda<?> lambda) {
				// System.out.println( info.getUrl() + "::::" + lambda.getSimpleName() );
				//
				// } else if (xxx instanceof CtExecutableReferenceExpression<?, ?> methodRef) {
				// System.out.println( info.getUrl() + "::::" + methodRef.getExecutable().getSimpleName() );
				//
				// }
				if (info.getUrl().contains( "property/get-list" )) {
					System.out.println( handlerInfo.getResponseBodyInfo() );

				}

				System.out.println();

			}

		}

	}

}
