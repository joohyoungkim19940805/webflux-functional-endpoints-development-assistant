package com.byeolnaerim.watch.swagger;


import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.swagger.HandlerInfo.LayerPosition;
import com.byeolnaerim.watch.swagger.anntation.RequestParam;
import com.byeolnaerim.watch.swagger.anntation.RequestPath;
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
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;


public class HandlerParser {

	// private final CtModel model;
	//
	// public HandlerParser(
	// CtModel model
	// ) {
	//
	// this.model = model;
	//
	// }
	// HandlerParser 내에 추가할 필드
	private Map<String, Boolean> queryParamsVars = new HashMap<>();

	private Map<String, Boolean> pathsParamsVars = new HashMap<>();

	private Set<String> processedTypes = new HashSet<>();

	public HandlerInfo parseHandler(
		CtExpression<?> handlerExpression, String routeName
	) {
    	queryParamsVars.clear();
    	pathsParamsVars.clear();
    	processedTypes.clear();
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

	private void parseMethodReferenceHandler(
		CtExecutableReferenceExpression<?, ?> methodRef, HandlerInfo handlerInfo, String routeName
	) {

		CtExecutableReference<?> executableRef = methodRef.getExecutable();

		// 메서드 참조에서 참조하는 메서드를 찾아야 한다.
		// model 내에서 해당 메서드 정의(CtMethod)를 찾는다.
		CtType<?> declaringType = executableRef.getDeclaringType().getTypeDeclaration();

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
				if(method.getBody() != null){
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
				CtType<?> declaringType = null;

				if (execRef.getDeclaringType() != null) {
					declaringType = execRef.getDeclaringType().getTypeDeclaration();

				}

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
							if (innerExecRef == null || innerExecRef.getDeclaringType() == null)
								continue;

							CtType<?> innerDeclaringType = innerExecRef.getDeclaringType().getTypeDeclaration();
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

					if (ref != null && ref.getDeclaringType() != null) {
						// 1) 기존 처리
						parseMethodReferenceHandler(
							(CtExecutableReferenceExpression<?, ?>) methodRef,
							handlerInfo,
							routeName
						);

						// 2) NEW: 메서드 참조가 가리키는 선언부를 찾아 재귀 파싱
						CtType<?> declaringType = ref.getDeclaringType().getTypeDeclaration();

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

		if (position.equals( LayerPosition.REQUEST_STRING )) {
			handlerInfo.getQueryStringInfo().put( key, pInfo );

		} else if (position.equals( LayerPosition.REQUEST_PATH )) {
			handlerInfo.getPathVariableInfo().put( key, pInfo );

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
				HandlerInfo.Info genericParamInfo = buildParamInfoFromTypeRef( argRef );
				genericParamInfo.setPosition( LayerPosition.GENERIC );

				if (RouteUtil.isPojo( genericParamInfo.getType() )) {
					parseClassFields( argRef, genericParamInfo );

					if (genericParamInfo.getFields().isEmpty()) {
						parseClassFields(
							argRef.getFactory().Type().createReference( genericParamInfo.getType() ),
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

		if (arg instanceof CtFieldAccess<?> fa && "class".equals( fa.getVariable().getSimpleName() ) && fa.getTarget() instanceof CtTypeAccess<?> ta) { return ta.getAccessedType(); }

		return null;

	}
	
	private void parseResponseBodyFromOkChain(
		CtInvocation<?> inv, HandlerInfo handlerInfo
	) {

		// ok().contentType(...).body(...) or bodyValue(...)
		String name = inv.getExecutable().getSimpleName();

		if (name.equals( "body" ) && ! inv.getArguments().isEmpty()) {

			// body(...) 호출 처리
			// 첫 번째 인자 파싱

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

			if (firstArgTypeRef == null) {
				firstArgTypeRef = manuallyInferResponseType( (CtInvocation<?>) firstArg );
				isParseFailedFlag = true;

			}

			HandlerInfo.Info pInfo = buildParamInfoFromTypeRef( firstArgTypeRef );


			pInfo.setPosition( LayerPosition.RESPONSE_BODY );
			// Mono나 Flux 타입이면 언래핑
			pInfo = unwrapIfReactorType( pInfo );


			// System.out.println( "pInfo:::" + pInfo );

			// 언래핑 결과가 ResponseWrapper인지 확인
			if (isEnvelopeInfo( pInfo )) {
				// pInfo = pInfo.getGenericTypes().get( 0 );
				// ResponseWrapper 내부 제너릭에서도 Mono/Flux 있을 수 있으니 재귀적 언래핑
				unwrapReactorTypes( pInfo );
				var _pInfo = pInfo;
				// ResponseWrapper 필드 및 제너릭 파싱
				parseClassFields(
					firstArgTypeRef
						.getReferencedTypes()
						.stream()
						.filter( e -> ! isIgnoredResponseTypeRef( e, _pInfo.getType() ) )
						.findFirst()
						.orElse( firstArgTypeRef ),
					pInfo
				);

				pInfo.setPosition( LayerPosition.RESPONSE_BODY );

				handlerInfo
					.getResponseBodyInfo()
					.put(
						pInfo.getType().getSimpleName(),
						pInfo
					);

				// if (pInfo.getGenericTypes().isEmpty()) {
				// handlerInfo.getResponseBodyInfo().put( pInfo.getType().getSimpleName(), pInfo );
				//
				// } else {
				// handlerInfo.getResponseBodyInfo().put( pInfo.getGenericTypes().get( 0
				// ).getType().getSimpleName(), pInfo );
				//
				// }

			} else if (isParseFailedFlag) {

				// handlerInfo
				// .getResponseBodyInfo()
				// .put(
				// pInfo.getType().getSimpleName(),
				// pInfo
				// );
				if (pInfo.getGenericTypes().isEmpty()) {
					handlerInfo.getResponseBodyInfo().put( pInfo.getType().getSimpleName(), pInfo );

				} else {
					handlerInfo
						.getResponseBodyInfo()
						.put(
							pInfo
								.getGenericTypes()
								.get(
									0
								)
								.getType()
								.getSimpleName(),
							pInfo
						);

				}

			} else {
				// ResponseWrapper가 아니면 그냥 타입 그대로 사용
				// 두 번째 인자 (Class<?> elementClass) 있으면 사용
				Class<?> finalType = inv.getArguments().size() > 1 ? extractClassArgument( inv, 1 ) : pInfo.getType();
				CtTypeReference<?> finalTypeRef = (inv.getArguments().size() > 1) ? extractTypeRefArgument( inv, 1 ) : firstArgTypeRef;


				HandlerInfo.Info finalInfo = new HandlerInfo.Info();

				finalInfo.setType( finalType );
				finalInfo.setTypeRef( finalTypeRef );
				if (isReactorType( pInfo.getType() ) && ! isJdkContainerType( finalType ) && ! isReactorType( finalType ) && finalType != null && finalType.getTypeParameters().length > 0 // 제너릭 클래스인가?
				) {

					if (pInfo.getGenericTypes().size() == 0) {

						if (! firstArg.getReferencedTypes().isEmpty()) {
							var _finalType = finalType;
							var refs = firstArg
								.getReferencedTypes()
								.stream()
								.filter( e -> ! isIgnoredResponseTypeRef( e, _finalType ) )
								.toList();

							if (refs.size() == 1 && refs.get( 0 ).getSimpleName().equals( "Result" )) {
								parseClassFields( refs.get( 0 ), finalInfo );

							} else if (refs.size() >= 2) {
								parseClassFields( refs.get( 0 ), finalInfo );
								parseClassFields( refs.get( 1 ), finalInfo );

							}

							if (refs.size() > 1) {
								finalInfo
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

						}

					}

				} else {

					if (finalTypeRef != null) {
						parseClassFields( finalTypeRef, finalInfo );

					} else if (finalType != null && finalType != Object.class) {
						parseClassFields( inv.getFactory().Type().createReference( finalType ), finalInfo );

					}
				}

				// handlerInfo
				// .getResponseBodyInfo()
				// .put(
				// finalInfo.getType().getSimpleName(),
				// finalInfo
				// );
				if (finalInfo.getGenericTypes().isEmpty()) {
					handlerInfo.getResponseBodyInfo().put( finalInfo.getType().getSimpleName(), finalInfo );

				} else {
					handlerInfo
						.getResponseBodyInfo()
						.put(
							finalInfo
								.getGenericTypes()
								.get(
									0
								)
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

		// `response` 메서드의 인자 리스트에서 '데이터'에 해당하는 인자를 찾는다.
		// 데이터가 아닌 타입(Result, Long)을 제외시키는 방식으로 찾는다.
		CtExpression<?> dataArgument = null;

		for (CtExpression<?> arg : factoryMethodCall.getArguments()) {
			CtTypeReference<?> argType = arg.getType();

			if (argType != null) {
				String typeName = argType.getQualifiedName();
				// System.out.println( "typeName:::" + typeName + " ::: " + argType.isEnum() );

				if (typeName != null && ! typeName.endsWith( "Result" ) && ! typeName.equals( "java.lang.Long" )) {

					dataArgument = arg;
					break; // 데이터 인자를 찾았으므로 루프 종료

				}

			}

		}

		// 데이터 인자를 찾지 못한 경우 (예: response(Result._0) 호출)
		if (dataArgument == null) {

			// 인자가 하나뿐이고 그 타입이 Result라면, 데이터가 없는 호출이므로 파싱할 필요 없음
			if (factoryMethodCall.getArguments().size() == 1) {
				CtTypeReference<?> argType = factoryMethodCall.getArguments().get( 0 ).getType();

				if (argType != null && argType.getQualifiedName() != null && argType.getQualifiedName().endsWith( "Result" )) { return null; }

			}

			// 그 외의 경우, 데이터 인자를 식별할 수 없음
			return null;

		}

		// `result` 변수 등 데이터 인자의 타입을 가져온다
		CtTypeReference<?> dataTypeRef = dataArgument.getType();

		if (dataTypeRef == null) { return null; }

		// 2. ResponseWrapper에 대한 Info 객체를 생성
		// CtTypeReference<?> wrapperTypeRef = factoryMethodCall.getFactory().Type().createReference(
		// ResponseWrapper.class );


		return dataTypeRef;

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


	private void parseClassFields(
		CtTypeReference<?> wrapperRef, HandlerInfo.Info pInfo
	) {

		// 이미 처리된 타입이면 중단 (순환 참조 방지)
		if (processedTypes.contains( wrapperRef.getQualifiedName() )) { return; }

		// 현재 타입을 처리 목록에 추가
		processedTypes.add( wrapperRef.getQualifiedName() );

		wrapperRef.getDeclaredFields().forEach( field -> {
			CtTypeReference<?> fieldType = field.getType();


			// 제너릭 타입이 자기 자신을 참조하는 경우 방지
			if (fieldType.getActualTypeArguments().stream().anyMatch( e -> e.getSimpleName().equals( wrapperRef.getSimpleName() ) )) {
				HandlerInfo.Info selfRefInfo = buildPartialInfo( field, fieldType );
				pInfo.addField( field.getSimpleName(), selfRefInfo );
				return;

			}

			// 자기 자신을 참조하는 경우 (예: Token.refreshToken)
			if (fieldType.getQualifiedName().equals( wrapperRef.getQualifiedName() )) {
				HandlerInfo.Info selfRefInfo = buildPartialInfo( field, fieldType );
				pInfo.addField( field.getSimpleName(), selfRefInfo );
				return; // 무한 루프 방지

			}

			// System.out.println( wrapperRef );
			// System.out.println( "222" + fieldType.getSimpleName() );
			HandlerInfo.Info fieldInfo = buildParamInfoFromTypeRef( fieldType );
			fieldInfo.setPosition( LayerPosition.FIELDS );

			if (fieldInfo.getName() == null) {
				fieldInfo.setName( field.getSimpleName() );

			}

			if (fieldInfo.getType().isEnum()) {
				fieldInfo
					.setExample(
						RouteUtil.parserEnumValues( fieldInfo.getType() ).toString()
					);

				// ;
			} else if (fieldInfo.getType().isRecord() || fieldInfo.getType().getPackageName().startsWith( "com.byeolnaerim" )) {

				// System.out.println( fieldInfo.getType() );
				parseClassFields( wrapperRef.getFactory().Type().createReference( fieldInfo.getType() ), fieldInfo );

			}

			pInfo.addField( field.getSimpleName(), fieldInfo );

		} );
		// 현재 처리된 타입을 제거하여 이후 동일한 타입을 다시 파싱할 수 있도록 함
		processedTypes.remove( wrapperRef.getQualifiedName() );

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

		// 필드, 제너릭 내부에 Mono/Flux 있으면 언래핑
		pInfo.setGenericTypes( pInfo.getGenericTypes().stream().map( this::unwrapIfReactorType ).collect( Collectors.toList() ) );

		for (HandlerInfo.Info gi : pInfo.getGenericTypes()) {
			unwrapReactorTypes( gi );

		}

		for (HandlerInfo.Info fi : pInfo.getFields().values()) {
			HandlerInfo.Info newFi = unwrapIfReactorType( fi );

			if (newFi != fi) {

				// fi 교체 필요하면 처리
			}

			unwrapReactorTypes( newFi );

		}

	}

	// Mono나 Flux인지 확인해서 언래핑하는 메서드
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
	 */
	private void applyAnnotationsToParamInfo(
		CtVariable<?> var, HandlerInfo.Info pInfo
	) {

		if (var == null) {
			return;

		}

		CtAnnotation<?> requestQueryAnn = var.getAnnotation( var.getFactory().Type().createReference( RequestParam.class ) );

		if (requestQueryAnn != null) {
			overrideParamInfoWithAnnotation( pInfo, requestQueryAnn );

		}

		CtAnnotation<?> requestPathAnn = var.getAnnotation( var.getFactory().Type().createReference( RequestPath.class ) );

		if (requestPathAnn != null) {
			overrideParamInfoWithAnnotation( pInfo, requestPathAnn );

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

		if (ann.getActualAnnotation() instanceof RequestParam requestParam) {
			key = requestParam.key();
			defaultValue = requestParam.defaultValue();
			required = requestParam.required();
			nullable = requestParam.nullable();
			typeClass = requestParam.type();

		} else if (ann.getActualAnnotation() instanceof RequestPath requestPath) {
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
