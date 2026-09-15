package com.byeolnaerim.watch.document.swagger.functional;


import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import com.byeolnaerim.watch.RouteUtil;
import com.byeolnaerim.watch.document.annotation.SelectedRequestBody;
import com.byeolnaerim.watch.document.annotation.SelectedRequestParam;
import com.byeolnaerim.watch.document.annotation.SelectedRequestPath;
import com.byeolnaerim.watch.document.annotation.SelectedResponseBody;
import com.byeolnaerim.watch.document.common.HandlerTypeInfoParser;
import com.byeolnaerim.watch.document.common.SourceDocumentationUtil;
import com.byeolnaerim.watch.document.common.TypeInfoParser;
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
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtImportKind;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeMemberWildcardImportReference;
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

	private final HandlerTypeInfoParser typeInfoParser;

	public HandlerParser() {

		this.externalTypes = Map.of();
		this.typeInfoParser = new HandlerTypeInfoParser( this.externalTypes );

	}

	public HandlerParser(
							Map<String, CtType<?>> externalTypes
	) {

		this.externalTypes = (externalTypes != null) ? externalTypes : Map.of();
		this.typeInfoParser = new HandlerTypeInfoParser( this.externalTypes );

	}

	// HandlerParser 내에 추가할 필드
	private Map<String, Boolean> queryParamsVars = new HashMap<>();

	private Map<String, Boolean> pathsParamsVars = new HashMap<>();

	private Set<String> processedTypes = new HashSet<>();

	private Set<String> processingMethods = new HashSet<>();

	private Set<String> parsedMethods = new HashSet<>();

	private Set<CtInvocation<?>> analyzedInvocations = Collections.newSetFromMap( new IdentityHashMap<>() );

	private boolean hasRequestBodyAnnotationOverride = false;

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
		processingMethods.clear();
		parsedMethods.clear();
		analyzedInvocations.clear();
		hasRequestBodyAnnotationOverride = false;
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

		return TypeInfoParser.findExternalDeclaringType( qualifiedName, simpleName, externalTypes );

	}

	private CtTypeReference<?> resolveSourceBackedTypeReference(
		CtTypeReference<?> typeRef
	) {

		return TypeInfoParser.resolveSourceBackedTypeReference( typeRef, externalTypes );

	}

	private CtType<?> resolveSourceBackedType(
		CtTypeReference<?> typeRef
	) {

		return TypeInfoParser.resolveSourceBackedType( typeRef, externalTypes );

	}

	private void parseMethodReferenceHandler(
		CtExecutableReferenceExpression<?, ?> methodRef, HandlerInfo handlerInfo, String routeName
	) {

		CtExecutableReference<?> executableRef = methodRef.getExecutable();

		if (executableRef == null) {
			return;

		}

		if (executableRef.getDeclaration() instanceof CtMethod<?> method) {
			applyOperationDocumentation( method, handlerInfo );
			parseMethodBody( method, handlerInfo, routeName );
			return;

		}

		// 메서드 참조에서 참조하는 메서드를 찾아야 한다.
		// internal 기준 기존 방식 우선, 없으면 external fallback
		CtType<?> declaringType = resolveDeclaringType( methodRef );

		if (declaringType != null) {
			// 메서드 이름과 파라미터 타입 등을 통해 CtMethod를 찾는다.
			List<CtMethod<?>> candidates = findCandidateMethods( executableRef, declaringType );

			// 여기서는 매칭되는 첫 번째 메서드를 사용
			if (! candidates.isEmpty()) {
				applyOperationDocumentation( candidates.get( 0 ), handlerInfo );
				parseMethodBody( candidates.get( 0 ), handlerInfo, routeName );

			}

		}

	}

	private void applyRequestBodyAnnotation(
		CtMethod<?> method, HandlerInfo handlerInfo
	) {

		if (method == null || handlerInfo == null || hasRequestBodyAnnotationOverride) {
			return;

		}

		CtAnnotation<?> ann = method.getAnnotation( method.getFactory().Type().createReference( SelectedRequestBody.class ) );

		if (ann == null) {
			return;

		}

		CtTypeReference<?> typeRef = resolveSelectedRequestBodyTypeReference( ann );

		if (typeRef == null) {
			return;

		}

		HandlerInfo.Info info = buildParamInfoFromTypeRef( typeRef );
		info.setPosition( LayerPosition.REQUEST_BODY );

		if (info.getFields().isEmpty()) {
			parseClassFields( typeRef, info );

		}

		hasRequestBodyAnnotationOverride = true;
		handlerInfo.getRequestBodyInfo().clear();

		String key = (info.getType() != null && info.getType() != Object.class)
			? info.getType().getSimpleName()
			: typeRef.getSimpleName();

		handlerInfo.getRequestBodyInfo().put( key, info );

	}

	private CtTypeReference<?> resolveSelectedRequestBodyTypeReference(
		CtAnnotation<?> ann
	) {

		CtExpression<?> valueExpr = ann.getValue( "value" );

		if (valueExpr instanceof CtFieldAccess<?> fieldAccess && "class".equals( fieldAccess.getVariable().getSimpleName() ) && fieldAccess.getTarget() instanceof CtTypeAccess<?> typeAccess) {
			return resolveSourceBackedTypeReference( typeAccess.getAccessedType() );

		}

		if (valueExpr != null) {
			return valueExpr
				.getReferencedTypes()
				.stream()
				.filter( typeRef -> ! "java.lang.Class".equals( typeRef.getQualifiedName() ) )
				.map( this::resolveSourceBackedTypeReference )
				.findFirst()
				.orElse( null );

		}

		return null;

	}

	private void applyOperationDocumentation(
		CtMethod<?> method, HandlerInfo handlerInfo
	) {

		if (method == null || handlerInfo == null) {
			return;

		}

		String summary = SourceDocumentationUtil.summary( method );
		String description = SourceDocumentationUtil.operationDescription( method );

		if ((handlerInfo.getOperationSummary() == null || handlerInfo.getOperationSummary().isBlank()) && summary != null && ! summary.isBlank()) {
			handlerInfo.setOperationSummary( summary );

		}

		if ((handlerInfo.getOperationDescription() == null || handlerInfo.getOperationDescription().isBlank()) && description != null && ! description.isBlank()) {
			handlerInfo.setOperationDescription( description );

		}

	}

	private List<CtMethod<?>> findCandidateMethods(
		CtExecutableReference<?> executableRef, CtType<?> declaringType
	) {

		if (executableRef == null || declaringType == null) {
			return List.of();

		}

		if (executableRef.getDeclaration() instanceof CtMethod<?> method) {
			return List.of( method );

		}

		return declaringType
			.getMethods()
			.stream()
			.filter( method -> method.getSimpleName().equals( executableRef.getSimpleName() ) )
			.filter( method -> method.getParameters().size() == executableRef.getParameters().size() )
			.filter( method -> {

				for (int i = 0; i < method.getParameters().size(); i++) {
					CtTypeReference<?> declaredType = method.getParameters().get( i ).getType();
					CtTypeReference<?> invokedType = executableRef.getParameters().get( i );

					if (declaredType != null && invokedType != null && ! declaredType
						.getTypeErasure()
						.getQualifiedName()
						.equals( invokedType.getTypeErasure().getQualifiedName() )) {
						return false;

					}

				}

				return true;

			} )
			.collect( Collectors.toList() );

	}

	private List<CtMethod<?>> findCandidateMethods(
		CtInvocation<?> invocation, CtType<?> declaringType
	) {

		if (invocation == null || invocation.getExecutable() == null || declaringType == null) {
			return List.of();

		}

		List<CtMethod<?>> candidates = findCandidateMethods( invocation.getExecutable(), declaringType );

		if (! candidates.isEmpty()) {
			return candidates;

		}

		return declaringType
			.getMethods()
			.stream()
			.filter( method -> method.getSimpleName().equals( invocation.getExecutable().getSimpleName() ) )
			.filter( method -> method.getParameters().size() == invocation.getArguments().size() )
			.filter( method -> {

				for (int i = 0; i < method.getParameters().size(); i++) {
					CtTypeReference<?> declaredType = resolveSourceBackedTypeReference( method.getParameters().get( i ).getType() );
					CtTypeReference<?> invokedType = resolveExpressionType( invocation.getArguments().get( i ) );

					if (declaredType == null || invokedType == null || declaredType instanceof CtTypeParameterReference) {
						continue;

					}

					String declaredQualifiedName = declaredType.getTypeErasure().getQualifiedName();
					String invokedQualifiedName = invokedType.getTypeErasure().getQualifiedName();

					if (declaredQualifiedName == null || invokedQualifiedName == null || declaredQualifiedName.equals( invokedQualifiedName )) {
						continue;

					}

					Class<?> declaredClass = loadClassFromTypeReference( declaredType );
					Class<?> invokedClass = loadClassFromTypeReference( invokedType );

					if (declaredClass != null && declaredClass != Object.class && invokedClass != null && invokedClass != Object.class && declaredClass.isAssignableFrom( invokedClass )) {
						continue;

					}

					return false;

				}

				return true;

			} )
			.collect( Collectors.toList() );

	}

	private List<CtMethod<?>> resolveInvocationMethods(
		CtInvocation<?> invocation
	) {

		if (invocation == null || invocation.getExecutable() == null) {
			return List.of();

		}

		Map<String, CtMethod<?>> resolved = new LinkedHashMap<>();
		CtExecutableReference<?> executableRef = invocation.getExecutable();

		if (executableRef.getDeclaration() instanceof CtMethod<?> method) {
			putResolvedMethod( resolved, method );

		}

		addInvocationMethods( resolved, invocation, resolveDeclaringType( executableRef ), false );

		CtExpression<?> target = invocation.getTarget();

		if (target != null) {
			addInvocationMethods( resolved, invocation, resolveSourceBackedType( resolveExpressionType( target ) ), false );

		} else {
			addInvocationMethods( resolved, invocation, findEnclosingType( invocation ), false );

		}

		if (resolved.isEmpty()) {
			addStaticImportedInvocationMethods( resolved, invocation );

		}

		return new ArrayList<>( resolved.values() );

	}

	private void addInvocationMethods(
		Map<String, CtMethod<?>> resolved, CtInvocation<?> invocation, CtType<?> declaringType, boolean staticOnly
	) {

		if (resolved == null || invocation == null || declaringType == null) {
			return;

		}

		for (CtMethod<?> method : findCandidateMethods( invocation, declaringType )) {

			if (staticOnly && ! method.hasModifier( ModifierKind.STATIC )) {
				continue;

			}

			putResolvedMethod( resolved, method );

		}

	}

	private void addStaticImportedInvocationMethods(
		Map<String, CtMethod<?>> resolved, CtInvocation<?> invocation
	) {

		if (invocation.getPosition() == null || ! invocation.getPosition().isValidPosition()) {
			return;

		}

		var compilationUnit = invocation.getPosition().getCompilationUnit();

		if (compilationUnit == null || compilationUnit.getImports() == null) {
			return;

		}

		String methodName = invocation.getExecutable().getSimpleName();

		for (CtImport ctImport : compilationUnit.getImports()) {

			if (ctImport == null || ctImport.getReference() == null) {
				continue;

			}

			if (ctImport.getImportKind() == CtImportKind.METHOD && ctImport.getReference() instanceof CtExecutableReference<?> importedExecutable && methodName
				.equals( importedExecutable.getSimpleName() )) {
				addInvocationMethods( resolved, invocation, resolveDeclaringType( importedExecutable ), true );

			} else if (ctImport.getImportKind() == CtImportKind.ALL_STATIC_MEMBERS && ctImport.getReference() instanceof CtTypeMemberWildcardImportReference wildcardImport) {
				addInvocationMethods( resolved, invocation, resolveSourceBackedType( wildcardImport.getTypeReference() ), true );

			}

		}

	}

	private void putResolvedMethod(
		Map<String, CtMethod<?>> resolved, CtMethod<?> method
	) {

		if (resolved == null || method == null || method.getDeclaringType() == null) {
			return;

		}

		resolved.putIfAbsent( method.getDeclaringType().getQualifiedName() + "#" + method.getSignature(), method );

	}

	private CtTypeReference<?> resolveExpressionType(
		CtExpression<?> expression
	) {

		if (expression == null) {
			return null;

		}

		if (expression instanceof CtVariableRead<?> variableRead && variableRead.getVariable() != null && variableRead.getVariable().getDeclaration() instanceof CtVariable<?> variable) {
			CtTypeReference<?> variableType = resolveSourceBackedTypeReference( variable.getType() );

			if (variableType != null) {
				return variableType;

			}

		}

		if (expression instanceof CtFieldAccess<?> fieldAccess && fieldAccess.getVariable() != null && fieldAccess.getVariable().getDeclaration() instanceof CtVariable<?> variable) {
			CtTypeReference<?> fieldType = resolveSourceBackedTypeReference( variable.getType() );

			if (fieldType != null) {
				return fieldType;

			}

		}

		return resolveSourceBackedTypeReference( expression.getType() );

	}

	private boolean isServerRequestType(
		CtTypeReference<?> typeRef
	) {

		typeRef = resolveSourceBackedTypeReference( typeRef );

		return typeRef != null && ("org.springframework.web.reactive.function.server.ServerRequest".equals( typeRef.getQualifiedName() ) || "ServerRequest".equals( typeRef.getSimpleName() ));

	}

	private boolean isServerRequestExpression(
		CtExpression<?> expression
	) {

		return isServerRequestType( resolveExpressionType( expression ) );

	}

	private boolean passesServerRequestTo(
		CtInvocation<?> invocation, CtMethod<?> method
	) {

		if (invocation == null || method == null) {
			return false;

		}

		int loopSize = Math.min( invocation.getArguments().size(), method.getParameters().size() );

		for (int i = 0; i < loopSize; i++) {

			if (isServerRequestExpression( invocation.getArguments().get( i ) ) && isServerRequestType( method.getParameters().get( i ).getType() )) {
				return true;

			}

		}

		return false;

	}

	private void parseMethodBody(
		CtMethod<?> method, HandlerInfo handlerInfo, String routeName
	) {

		if (method == null) {
			return;

		}

		applyRequestBodyAnnotation( method, handlerInfo );

		if (method.getBody() == null) {
			return;

		}

		String methodKey = method.getDeclaringType().getQualifiedName() + "#" + method.getSignature();

		if (! parsedMethods.add( methodKey ) || ! processingMethods.add( methodKey )) {
			return;

		}

		try {
			parseHandlerBody( method.getBody(), handlerInfo, routeName );

		} finally {
			processingMethods.remove( methodKey );

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
			// TypeFilter#getElements()가 중첩 lambda 내부까지 이미 재귀 수집하므로
			// lambda body를 별도로 다시 순회하지 않는다.
			analyzeInvocationForRequestResponse( inv, handlerInfo, routeName );

			if (inv.getArguments().stream().anyMatch( this::isServerRequestExpression )) {

				for (CtMethod<?> candidate : resolveInvocationMethods( inv )) {

					if (passesServerRequestTo( inv, candidate )) {
						applyOperationDocumentation( candidate, handlerInfo );
						parseMethodBody( candidate, handlerInfo, routeName );

					}

				}

			}

			// 메서드 참조는 lambda body와 달리 대상 메서드 본문이 현재 subtree에 없으므로
			// 기존처럼 선언부를 따라간다. 같은 메서드는 parsedMethods에서 한 번만 분석된다.
			for (CtExpression<?> arg : inv.getArguments()) {

				if (arg instanceof CtExecutableReferenceExpression<?, ?> methodRef) {
					parseMethodReferenceHandler( methodRef, handlerInfo, routeName );

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

		if (! analyzedInvocations.add( inv ) || inv.getExecutable() == null) {
			return;

		}

		String name = inv.getExecutable().getSimpleName();

		CtAnnotation<?> responseHelperAnnotation = findResponseBodyOnInvokedMethod( inv );

		if (responseHelperAnnotation != null && isServerResponseHelperInvocation( inv )) {
			parseResponseBodyFromAnnotatedHelper( inv, responseHelperAnnotation, handlerInfo );

		}

		// get/getFirst/getOrDefault만 queryParams/pathVariables 계열 후보다.
		// 모든 invocation에서 target.toString()/type resolution을 반복하지 않는다.
		switch (name) {
			case "getFirst" -> {

				if (isRequestQueryParamsGetFirstDirectCall( inv )) {
					addParamInfo( handlerInfo, extractStringArgument( inv, 0 ), inv, LayerPosition.REQUEST_STRING );

				}

				if (isQueryParamsGetFirstCall( inv )) {
					addParamInfo( handlerInfo, extractStringArgument( inv, 0 ), inv, LayerPosition.REQUEST_STRING );

				}

				if (isRequestPathVariablesGetFirstCall( inv )) {
					String key = extractStringArgument( inv, 0 );
					addParamInfo( handlerInfo, key, findOrElseDefaultValue( inv ), inv, LayerPosition.REQUEST_PATH );

				}

			}
			case "get" -> {

				if (isRequestQueryParamsGetDirectCall( inv )) {
					String key = extractStringArgument( inv, 0 );
					addParamInfo( handlerInfo, key, findOrElseDefaultValue( inv ), inv, LayerPosition.REQUEST_STRING );

				}

				if (isQueryParamsGetCall( inv )) {
					String key = extractStringArgument( inv, 0 );
					addParamInfo( handlerInfo, key, findOrElseDefaultValue( inv ), inv, LayerPosition.REQUEST_STRING );

				}

				if (isRequestPathVariablesGetCall( inv )) {
					String key = extractStringArgument( inv, 0 );
					addParamInfo( handlerInfo, key, findOrElseDefaultValue( inv ), inv, LayerPosition.REQUEST_PATH );

				}

			}
			case "getOrDefault" -> {

				if (isRequestQueryParamsGetOrDefaultDirectCall( inv ) || isQueryParamsGetOrDefaultCall( inv )) {
					addParamInfo( handlerInfo, extractStringArgument( inv, 0 ), null, inv, LayerPosition.REQUEST_STRING );

				}

				if (isRequestPathVariablesGetOrDefaultCall( inv )) {
					addParamInfo( handlerInfo, extractStringArgument( inv, 0 ), null, inv, LayerPosition.REQUEST_PATH );

				}

			}
			case "queryParam" -> {

				if (isTargetRequest( inv )) {
					String key = extractStringArgument( inv, 0 );
					addParamInfo( handlerInfo, key, findOrElseDefaultValue( inv ), inv, LayerPosition.REQUEST_STRING );

				}

			}
			case "pathVariable" -> {

				if (isTargetRequest( inv )) {
					String key = extractStringArgument( inv, 0 );
					addParamInfo( handlerInfo, key, null, inv, LayerPosition.REQUEST_PATH );

				}

			}
			default -> {}

		}

		boolean isBodyToXCall = (name.equals( "bodyToMono" ) || name.equals( "bodyToFlux" )) && isTargetRequest( inv );

		if (isBodyToXCall && ! hasRequestBodyAnnotationOverride) {
			int targetIndex = 0;
			CtExpression<?> arg = inv.getArguments().get( targetIndex );
			Class<?> bodyClass = extractClassArgument( inv, targetIndex );
			CtTypeReference<?> bodyClassRef = extractTypeRefArgument( inv, targetIndex );

			HandlerInfo.Info requestBodyInfo = new HandlerInfo.Info();
			requestBodyInfo.setType( bodyClass );
			requestBodyInfo.setTypeRef( bodyClassRef );
			requestBodyInfo
				.setFields(
					buildParamInfoFromTypeRef(
						bodyClassRef != null ? bodyClassRef : arg.getFactory().Type().createReference( bodyClass )
					).getFields()
				);

			if (! arg.getReferencedTypes().isEmpty()) {
				var refs = inv
					.getArguments()
					.get( targetIndex )
					.getReferencedTypes()
					.stream()
					.filter( e -> ! "Object".equals( e.getSimpleName() ) && ! bodyClass.getSimpleName().equals( e.getSimpleName() ) )
					.toList();
				refs.forEach( e -> parseClassFields( e, requestBodyInfo ) );
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

		if ((name.equals( "body" ) || name.equals( "bodyValue" ) || name.equals( "contentType" ) || name.equals( "build" ) || name.equals( "noContent" )) && resolveResponseStatusCode( inv ) != null) {
			parseResponseBodyFromResponseChain( inv, handlerInfo );

		}

	}

	private boolean isServerResponseHelperInvocation(
		CtInvocation<?> inv
	) {

		return containsServerResponseType( inv != null ? inv.getType() : null );

	}

	private boolean containsServerResponseType(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) {
			return false;

		}

		if ("ServerResponse".equals( typeRef.getSimpleName() )) {
			return true;

		}

		return typeRef.getActualTypeArguments() != null && typeRef.getActualTypeArguments().stream().anyMatch( this::containsServerResponseType );

	}

	private void addParamInfo(
		HandlerInfo handlerInfo, String key, CtInvocation<?> inv, LayerPosition position
	) {

		addParamInfo( handlerInfo, key, null, inv, position );

	}

	private void addParamInfo(
		HandlerInfo handlerInfo, String key, String defaultValue, CtInvocation<?> inv, LayerPosition position
	) {

		CtLocalVariable<?> variable = determineRequestParameterVariable( inv );
		CtTypeReference<?> finalTypeRef = determineRequestParameterType( inv );
		HandlerInfo.Info pInfo = buildParamInfoFromTypeRef( finalTypeRef );
		pInfo.setName( key );
		pInfo.setDefaultValue( defaultValue );
		pInfo.setRequired( position == LayerPosition.REQUEST_PATH || isRequiredRequestParameter( inv ) );
		pInfo.setNullable( position != LayerPosition.REQUEST_PATH && isNullableRequestParameter( inv ) );
		pInfo.setPosition( position );

		if (variable != null) {
			pInfo.setDescription( SourceDocumentationUtil.description( variable ) );

		}

		applyAnnotationsToParamInfo( variable, pInfo );

		if (pInfo.getPosition() == LayerPosition.REQUEST_PATH) {
			pInfo.setRequired( Boolean.TRUE );
			pInfo.setNullable( Boolean.FALSE );

		}

		if (pInfo.getPosition().equals( LayerPosition.REQUEST_STRING )) {
			handlerInfo.getQueryStringInfo().put( pInfo.getName(), pInfo );

		} else if (pInfo.getPosition().equals( LayerPosition.REQUEST_PATH )) {
			handlerInfo.getPathVariableInfo().put( pInfo.getName(), pInfo );

		}

	}

	/**
	 * request query/path expression이 최종적으로 대입되는 로컬 변수를 표현식 범위 안에서 추적한다.
	 * 최종 변수 타입이 request parameter로 지원되는 Java 타입인 경우에만 해당 타입을 사용하고,
	 * DTO 등 지원하지 않는 타입이면 원래 request expression 타입을 유지한다.
	 */
	private CtTypeReference<?> determineRequestParameterType(
		CtInvocation<?> inv
	) {

		CtLocalVariable<?> variable = determineRequestParameterVariable( inv );
		CtTypeReference<?> assignedType = variable == null ? null : resolveSourceBackedTypeReference( variable.getType() );

		if (isSupportedRequestParameterType( assignedType )) {
			return unwrapOptionalRequestParameterType( assignedType );

		}

		CtTypeReference<?> result = resolveSourceBackedTypeReference( inv.getType() );
		CtElement current = inv;

		while (current != null) {
			CtElement parent = current.getParent();

			if (! (parent instanceof CtInvocation<?> parentInv)) {
				break;

			}

			CtTypeReference<?> candidate = resolveSourceBackedTypeReference( parentInv.getType() );

			if (! isRequestParameterTransformation( parentInv, current, result, candidate )) {
				break;

			}

			result = candidate;
			current = parentInv;

		}

		return unwrapOptionalRequestParameterType( result != null ? result : inv.getType() );

	}

	private CtTypeReference<?> unwrapOptionalRequestParameterType(
		CtTypeReference<?> typeRef
	) {

		typeRef = resolveSourceBackedTypeReference( typeRef );

		while (isOptionalType( typeRef ) && typeRef.getActualTypeArguments() != null && ! typeRef.getActualTypeArguments().isEmpty()) {
			typeRef = resolveSourceBackedTypeReference( typeRef.getActualTypeArguments().get( 0 ) );

		}

		return typeRef;

	}

	private CtLocalVariable<?> determineRequestParameterVariable(
		CtInvocation<?> inv
	) {

		CtElement current = inv;

		while (current != null) {
			CtElement parent = current.getParent();

			if (parent instanceof CtLocalVariable<?> localVariable) {
				return localVariable;

			}

			if (parent instanceof CtAssignment<?, ?> assign && assign
				.getAssigned() instanceof CtVariableWrite<?> varWrite && varWrite.getVariable().getDeclaration() instanceof CtLocalVariable<?> localVariable) {
				return localVariable;

			}

			if (parent instanceof CtLambda<?> || ! (parent instanceof CtExpression<?>)) {
				break;

			}

			current = parent;

		}

		return null;

	}

	private boolean isRequestParameterTransformation(
		CtInvocation<?> invocation, CtElement source, CtTypeReference<?> sourceType, CtTypeReference<?> resultType
	) {

		if (invocation == null || invocation.getExecutable() == null || ! isSupportedRequestParameterType( resultType )) {
			return false;

		}

		String methodName = invocation.getExecutable().getSimpleName();

		if (invocation.getTarget() == source) {

			if (isOptionalType( sourceType )) {
				return Set.of( "get", "or", "orElse", "orElseGet", "orElseThrow", "map", "flatMap", "filter" ).contains( methodName );

			}

			if (isCollectionType( sourceType )) {
				return "get".equals( methodName );

			}

			Class<?> sourceClass = loadClassFromTypeReference( sourceType );

			if (invocation.getArguments().isEmpty() && sourceClass != null && sourceClass != Object.class && Number.class.isAssignableFrom( sourceClass ) && methodName.endsWith( "Value" )) {
				return true;

			}

			return isSameRequestParameterType( sourceType, resultType ) && Set.of( "trim", "strip", "stripLeading", "stripTrailing", "toLowerCase", "toUpperCase" ).contains( methodName );

		}

		if (! invocation.getArguments().contains( source )) {
			return false;

		}

		if ("requireNonNull".equals( methodName )) {
			return true;

		}

		boolean converterName = methodName.startsWith( "parse" ) || methodName.startsWith( "convert" ) || methodName.startsWith( "to" ) || methodName.startsWith( "from" ) || "valueOf"
			.equals( methodName );

		if (invocation.getTarget() instanceof CtTypeAccess<?>) {
			return converterName || isRequestParameterEnumType( resultType );

		}

		CtMethod<?> declaration = invocation.getExecutable().getDeclaration() instanceof CtMethod<?> method ? method : null;
		CtType<?> enclosingType = findEnclosingType( source );

		if (declaration != null && declaration.getDeclaringType() != null && enclosingType != null && declaration.getDeclaringType().getQualifiedName().equals( enclosingType.getQualifiedName() )) {
			return converterName || isRequestParameterEnumType( resultType );

		}

		return converterName;

	}

	private boolean isSupportedRequestParameterType(
		CtTypeReference<?> typeRef
	) {

		typeRef = resolveSourceBackedTypeReference( typeRef );

		if (typeRef == null) {
			return false;

		}

		if (typeRef instanceof CtArrayTypeReference<?> arrayTypeReference) {
			return isSupportedRequestParameterType( arrayTypeReference.getComponentType() );

		}

		if (isRequestParameterEnumType( typeRef )) {
			return true;

		}

		Class<?> type = loadClassFromTypeReference( typeRef );

		if (type != null && type != Object.class) {

			if (type.isArray()) {
				return isSupportedRequestParameterClass( type.getComponentType() );

			}

			if (Collection.class.isAssignableFrom( type ) || Optional.class.isAssignableFrom( type )) {
				return ! typeRef.getActualTypeArguments().isEmpty() && isSupportedRequestParameterType( typeRef.getActualTypeArguments().get( 0 ) );

			}

			return isSupportedRequestParameterClass( type );

		}

		return typeRef.getSimpleName() != null && typeRef.getSimpleName().contains( "ObjectId" );

	}

	private boolean isSupportedRequestParameterClass(
		Class<?> type
	) {

		return type == String.class || type == boolean.class || type == Boolean.class || type == byte.class || type == Byte.class || type == short.class || type == Short.class || type == int.class || type == Integer.class || type == long.class || type == Long.class || type == float.class || type == Float.class || type == double.class || type == Double.class || type == java.math.BigDecimal.class || type == java.math.BigInteger.class || type == java.time.LocalDate.class || type == java.time.LocalDateTime.class || type == java.time.LocalTime.class || type == java.time.Instant.class || type == java.time.OffsetDateTime.class || type == java.time.ZonedDateTime.class || type == java.util.Date.class || type == java.util.UUID.class || type
			.isEnum() || type.getSimpleName().contains( "ObjectId" );

	}

	private boolean isRequestParameterEnumType(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) {
			return false;

		}

		Class<?> type = loadClassFromTypeReference( typeRef );

		if (type != null && type != Object.class && type.isEnum()) {
			return true;

		}

		return resolveSourceBackedType( typeRef ) instanceof spoon.reflect.declaration.CtEnum<?>;

	}

	private boolean isOptionalType(
		CtTypeReference<?> typeRef
	) {

		return typeRef != null && ("java.util.Optional".equals( typeRef.getQualifiedName() ) || "Optional".equals( typeRef.getSimpleName() ));

	}

	private boolean isCollectionType(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) {
			return false;

		}

		Class<?> type = loadClassFromTypeReference( typeRef );
		return type != null && type != Object.class && Collection.class.isAssignableFrom( type );

	}

	private boolean isSameRequestParameterType(
		CtTypeReference<?> left, CtTypeReference<?> right
	) {

		left = resolveSourceBackedTypeReference( left );
		right = resolveSourceBackedTypeReference( right );

		return left != null && right != null && left.getQualifiedName() != null && left.getQualifiedName().equals( right.getQualifiedName() );

	}

	private CtType<?> findEnclosingType(
		CtElement element
	) {

		CtElement current = element;

		while (current != null) {

			if (current instanceof CtType<?> type) {
				return type;

			}

			current = current.getParent();

		}

		return null;

	}

	private boolean isRequiredRequestParameter(
		CtInvocation<?> inv
	) {

		CtElement current = inv;
		CtTypeReference<?> currentType = resolveSourceBackedTypeReference( inv.getType() );

		while (current != null) {
			CtElement parent = current.getParent();

			if (! (parent instanceof CtInvocation<?> parentInv)) {
				return false;

			}

			String methodName = parentInv.getExecutable() != null ? parentInv.getExecutable().getSimpleName() : "";

			if (parentInv.getTarget() == current && isOptionalType( currentType )) {

				if ("get".equals( methodName ) || "orElseThrow".equals( methodName )) {
					return true;

				}

				if ("orElse".equals( methodName ) || "orElseGet".equals( methodName )) {
					return false;

				}

			}

			if (parentInv.getTarget() == current && isCollectionType( currentType ) && "get".equals( methodName )) {
				return true;

			}

			if (parentInv.getArguments().contains( current ) && "requireNonNull".equals( methodName )) {
				return true;

			}

			CtTypeReference<?> candidate = resolveSourceBackedTypeReference( parentInv.getType() );

			if (! isRequestParameterTransformation( parentInv, current, currentType, candidate )) {
				return false;

			}

			currentType = candidate;
			current = parentInv;

		}

		return false;

	}

	private boolean isNullableRequestParameter(
		CtInvocation<?> inv
	) {

		if (isRequiredRequestParameter( inv )) {
			return false;

		}

		CtElement current = inv;
		CtTypeReference<?> currentType = resolveSourceBackedTypeReference( inv.getType() );

		while (current != null) {
			CtElement parent = current.getParent();

			if (! (parent instanceof CtInvocation<?> parentInv)) {
				break;

			}

			String methodName = parentInv.getExecutable() != null ? parentInv.getExecutable().getSimpleName() : "";

			if (parentInv.getTarget() == current && isOptionalType( currentType ) && "orElse".equals( methodName ) && ! parentInv.getArguments().isEmpty()) {
				CtExpression<?> defaultExpression = parentInv.getArguments().get( 0 );
				return defaultExpression instanceof CtLiteral<?> literal && literal.getValue() == null;

			}

			CtTypeReference<?> candidate = resolveSourceBackedTypeReference( parentInv.getType() );

			if (! isRequestParameterTransformation( parentInv, current, currentType, candidate )) {
				break;

			}

			currentType = candidate;
			current = parentInv;

		}

		return true;

	}


	/**
	 * CtTypeReference로부터 Class<?>를 로딩
	 */
	private Class<?> loadClassFromTypeReference(
		CtTypeReference<?> typeRef
	) {

		return TypeInfoParser.loadClassFromTypeReference( typeRef );

	}

	/**
	 * CtTypeReference를 ParamInfo로 변환하는 메서드.
	 * 제너릭 타입이 있을 경우 재귀적으로 처리하여 genericTypes 리스트에 추가.
	 */
	private HandlerInfo.Info buildParamInfoFromTypeRef(
		CtTypeReference<?> typeRef
	) {

		return typeInfoParser.buildInfo( typeRef );

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



	private boolean isResponseCallChain(
		CtInvocation<?> inv
	) {

		if (inv == null || inv.getExecutable() == null) {
			return false;

		}

		String name = inv.getExecutable().getSimpleName();

		if (! name.equals( "body" ) && ! name.equals( "bodyValue" ) && ! name.equals( "contentType" ) && ! name.equals( "build" ) && ! name.equals( "noContent" )) {
			return false;

		}

		return resolveResponseStatusCode( inv ) != null;

	}

	private boolean matchesCall(
		CtInvocation<?> inv, String methodName
	) {

		return inv.getExecutable().getSimpleName().equals( methodName );

	}

	private boolean isTargetRequest(
		CtInvocation<?> inv
	) {

		return inv != null && isServerRequestExpression( inv.getTarget() );

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

		CtElement current = inv;

		while (current != null) {
			CtElement parent = current.getParent();

			if (! (parent instanceof CtInvocation<?> parentInv) || parentInv.getTarget() != current) {
				return null;

			}

			if (isOrElseCall( parentInv )) {
				CtExpression<?> argument = parentInv.getArguments().get( 0 );

				if (argument instanceof CtLiteral<?> literal && literal.getValue() != null) {
					return String.valueOf( literal.getValue() );

				}

				return null;

			}

			current = parentInv;

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

	private String resolveResponseStatusCode(
		CtInvocation<?> inv
	) {

		CtInvocation<?> current = inv;

		while (current != null) {
			String name = current.getExecutable().getSimpleName();

			String statusCode = switch (name) {
				case "ok" -> "200";
				case "created" -> "201";
				case "accepted" -> "202";
				case "noContent" -> "204";
				case "badRequest" -> "400";
				case "notFound" -> "404";
				case "unprocessableEntity" -> "422";
				default -> null;

			};

			if (statusCode != null) {
				return statusCode;

			}

			if (name.equals( "status" ) && ! current.getArguments().isEmpty()) {
				return extractStatusCodeArgument( current.getArguments().get( 0 ) );

			}

			if (current.getTarget() instanceof CtInvocation<?> targetInvocation) {
				current = targetInvocation;

			} else {
				current = null;

			}

		}

		return null;

	}

	private String extractStatusCodeArgument(
		CtExpression<?> arg
	) {

		if (arg == null) {
			return null;

		}

		if (arg instanceof CtLiteral<?> literal) {
			Object value = literal.getValue();

			if (value instanceof Number number) {
				return String.valueOf( number.intValue() );

			}

			if (value instanceof String stringValue && stringValue.matches( "\\d{3}" )) {
				return stringValue;

			}

		}

		if (arg instanceof CtVariableRead<?> variableRead && variableRead.getVariable() != null && variableRead.getVariable().getDeclaration() instanceof CtLocalVariable<?> localVariable) {
			return extractStatusCodeArgument( localVariable.getDefaultExpression() );

		}

		if (arg instanceof CtInvocation<?> invocation) {
			String name = invocation.getExecutable().getSimpleName();

			if ((name.equals( "valueOf" ) || name.equals( "status" )) && ! invocation.getArguments().isEmpty()) {
				return extractStatusCodeArgument( invocation.getArguments().get( 0 ) );

			}

			if (name.equals( "value" ) && invocation.getTarget() != null) {
				return extractHttpStatusCodeFromText( invocation.getTarget().toString() );

			}

		}

		return extractHttpStatusCodeFromText( arg.toString() );

	}

	private String extractHttpStatusCodeFromText(
		String text
	) {

		if (text == null || text.isBlank()) {
			return null;

		}

		String normalized = text
			.replace( "org.springframework.http.HttpStatus.", "" )
			.replace( "HttpStatus.", "" )
			.replace( "org.springframework.http.HttpStatusCode.", "" )
			.replace( "HttpStatusCode.", "" )
			.trim();

		if (normalized.matches( "\\d{3}" )) {
			return normalized;

		}

		if (normalized.startsWith( "valueOf(" ) && normalized.endsWith( ")" )) {
			return extractHttpStatusCodeFromText(
				normalized.substring( "valueOf(".length(), normalized.length() - 1 )
			);

		}

		return switch (normalized) {
			case "OK" -> "200";
			case "CREATED" -> "201";
			case "ACCEPTED" -> "202";
			case "NO_CONTENT" -> "204";
			case "BAD_REQUEST" -> "400";
			case "UNAUTHORIZED" -> "401";
			case "FORBIDDEN" -> "403";
			case "NOT_FOUND" -> "404";
			case "CONFLICT" -> "409";
			case "UNPROCESSABLE_ENTITY" -> "422";
			case "INTERNAL_SERVER_ERROR" -> "500";
			case "BAD_GATEWAY" -> "502";
			case "SERVICE_UNAVAILABLE" -> "503";
			default -> null;

		};

	}

	private void putResponseInfo(
		HandlerInfo handlerInfo, CtInvocation<?> inv, String key, HandlerInfo.Info info
	) {

		handlerInfo.getResponseBodyInfo().put( key, info );

		String statusCode = resolveResponseStatusCode( inv );

		if (statusCode == null) {
			statusCode = "200";

		}

		handlerInfo.getResponseInfoByStatusCode().put( statusCode, info );

	}

	private void putEmptyResponseInfo(
		HandlerInfo handlerInfo, CtInvocation<?> inv
	) {

		String statusCode = resolveResponseStatusCode( inv );

		if (statusCode != null) {
			handlerInfo.getResponseInfoByStatusCode().putIfAbsent( statusCode, null );

		}

	}

	private void parseResponseBodyFromResponseChain(
		CtInvocation<?> inv, HandlerInfo handlerInfo
	) {

		// ok()/badRequest()/notFound()/noContent()/status(...).body(...) or bodyValue(...)
		String name = inv.getExecutable().getSimpleName();

		if (name.equals( "build" ) || name.equals( "noContent" )) {
			putEmptyResponseInfo( handlerInfo, inv );

		}

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
					putResponseInfo( handlerInfo, inv, key, annotated );
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
				CtTypeReference<?> inferredResponseTypeRef = manuallyInferResponseType( responseFactoryInvocation );

				if (inferredResponseTypeRef != null) {
					firstArgTypeRef = inferredResponseTypeRef;
					isParseFailedFlag = true;

				}

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

				if (pInfo.getFields().isEmpty()) {
					parseClassFields( envelopeTypeRef, pInfo );

				}

				pInfo.setPosition( LayerPosition.RESPONSE_BODY );

				putResponseInfo(
					handlerInfo,
					inv,
					pInfo.getType().getSimpleName(),
					pInfo
				);

			} else if (isParseFailedFlag) {

				if (pInfo.getGenericTypes().isEmpty()) {
					putResponseInfo( handlerInfo, inv, pInfo.getType().getSimpleName(), pInfo );

				} else {
					putResponseInfo(
						handlerInfo,
						inv,
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
					putResponseInfo( handlerInfo, inv, finalInfo.getType().getSimpleName(), finalInfo );

				} else {
					putResponseInfo(
						handlerInfo,
						inv,
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

				if (valTypeRef != null && pInfo.getFields().isEmpty()) {
					parseClassFields( valTypeRef, pInfo );

				}

				putResponseInfo(
					handlerInfo,
					inv,
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

		CtExpression<?> init = dataArgument;
		CtVariable<?> varDecl = extractVariableDeclaration( dataArgument );

		if (varDecl instanceof CtLocalVariable<?> localVar && localVar.getDefaultExpression() != null) {
			init = localVar.getDefaultExpression();

		}

		List<CtInvocation<?>> nestedInvocations = new ArrayList<>();

		if (init instanceof CtInvocation<?> initInvocation) {
			nestedInvocations.add( initInvocation );

		}

		nestedInvocations.addAll( init.getElements( new TypeFilter<>( CtInvocation.class ) ) );
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

			if (! hasUsableTypeArgument( nestedTypeRef )) {
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
			.equals( qName )) && ! hasUsableTypeArgument( actualTypeRef )) {

			CtTypeReference<?> repairedTypeRef = tryInferRawReactorTypeFromVariableInitializer( argumentExpression, actualTypeRef );

			if (repairedTypeRef != null) {
				return resolveSourceBackedTypeReference( repairedTypeRef );

			}

		}

		return actualTypeRef;

	}

	private boolean hasUsableTypeArgument(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null || typeRef.getActualTypeArguments() == null || typeRef.getActualTypeArguments().isEmpty()) {
			return false;

		}

		CtTypeReference<?> argument = resolveSourceBackedTypeReference( typeRef.getActualTypeArguments().get( 0 ) );

		if (argument == null || argument instanceof CtTypeParameterReference) {
			return false;

		}

		String simpleName = argument.getSimpleName();
		String qualifiedName = argument.getQualifiedName();

		return simpleName != null && ! simpleName.startsWith( "?" ) && ! "Object".equals( simpleName ) && ! "java.lang.Object".equals( qualifiedName );

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

	private CtTypeReference<?> applyTypeBindings(
		CtTypeReference<?> typeRef, Map<String, CtTypeReference<?>> bindings
	) {

		typeRef = resolveSourceBackedTypeReference( typeRef );

		if (typeRef == null) {
			return null;

		}

		if (typeRef instanceof CtTypeParameterReference typeParameterReference) {
			String typeParameterName = typeParameterReference.getSimpleName();

			if (typeParameterReference.getDeclaration() != null) {
				typeParameterName = typeParameterReference.getDeclaration().getSimpleName();

			}

			CtTypeReference<?> boundTypeRef = bindings.get( typeParameterName );

			return boundTypeRef != null ? resolveSourceBackedTypeReference( boundTypeRef ) : typeRef;

		}

		CtTypeReference<?> resolvedTypeRef = typeRef.clone();
		List<CtTypeReference<?>> actualTypeArguments = typeRef.getActualTypeArguments();

		if (actualTypeArguments != null && ! actualTypeArguments.isEmpty()) {
			List<CtTypeReference<?>> resolvedTypeArguments = new ArrayList<>( actualTypeArguments.size() );

			for (CtTypeReference<?> actualTypeArgument : actualTypeArguments) {
				CtTypeReference<?> resolvedTypeArgument = applyTypeBindings( actualTypeArgument, bindings );
				resolvedTypeArguments.add( resolvedTypeArgument != null ? resolvedTypeArgument : actualTypeArgument );

			}

			resolvedTypeRef.setActualTypeArguments( resolvedTypeArguments );

		}

		return resolvedTypeRef;

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

		if (factoryMethodCall == null || factoryMethodCall.getExecutable() == null) {
			return null;

		}

		for (CtMethod<?> candidate : resolveInvocationMethods( factoryMethodCall )) {
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
				CtTypeReference<?> formalParameterTypeRef = resolveSourceBackedTypeReference( candidate.getParameters().get( i ).getType() );
				CtTypeReference<?> actualArgumentTypeRef = resolveActualArgumentTypeForGenericInference( factoryMethodCall.getArguments().get( i ) );

				bindTypeParameters( formalParameterTypeRef, actualArgumentTypeRef, bindings );

			}

			if (! returnTypeParameterNames.stream().allMatch( bindings::containsKey )) {
				continue;

			}

			CtTypeReference<?> inferredReturnTypeRef = applyTypeBindings( returnTypeRef, bindings );

			if (inferredReturnTypeRef != null) {
				return resolveSourceBackedTypeReference( inferredReturnTypeRef );

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
		CtTypeReference<?> wrapperRef, HandlerInfo.Info pInfo
	) {

		typeInfoParser.parseFields( wrapperRef, pInfo );

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

		if (inv == null || inv.getExecutable() == null) {
			return null;

		}

		var annType = inv.getFactory().Type().createReference( SelectedResponseBody.class );

		for (CtMethod<?> method : resolveInvocationMethods( inv )) {
			CtAnnotation<?> ann = method.getAnnotation( annType );

			if (ann != null) {
				return ann;

			}

		}

		return null;

	}

	private void parseResponseBodyFromAnnotatedHelper(
		CtInvocation<?> inv, CtAnnotation<?> ann, HandlerInfo handlerInfo
	) {

		if (! (ann.getActualAnnotation() instanceof SelectedResponseBody rb)) {
			return;

		}

		HandlerInfo.Info info = buildResponseBodyInfoFromAnnotation( ann, inv.getFactory() );

		if (info == null && rb.parameterIndex() >= 0 && rb.parameterIndex() < inv.getArguments().size()) {
			CtExpression<?> argument = inv.getArguments().get( rb.parameterIndex() );
			CtTypeReference<?> argumentTypeRef = resolveActualArgumentTypeForGenericInference( argument );

			if (argumentTypeRef == null) {
				return;

			}

			HandlerInfo.Info payloadInfo = buildParamInfoFromTypeRef( argumentTypeRef );

			if (payloadInfo.getType() != null && Mono.class.isAssignableFrom( payloadInfo.getType() ) && ! payloadInfo.getGenericTypes().isEmpty()) {
				payloadInfo = payloadInfo.getGenericTypes().get( 0 );

			}

			Class<?> wrapperType = rb.wrapperType();

			if (wrapperType != null && wrapperType != Void.class && wrapperType != void.class) {
				CtTypeReference<?> wrapperTypeRef = inv.getFactory().Type().createReference( wrapperType );
				CtTypeReference<?> payloadTypeRef = payloadInfo.getTypeRef();

				if (payloadTypeRef == null && payloadInfo.getType() != null && payloadInfo.getType() != Object.class) {
					payloadTypeRef = inv.getFactory().Type().createReference( payloadInfo.getType() );

				}

				if (payloadTypeRef != null) {
					wrapperTypeRef.setActualTypeArguments( List.of( resolveSourceBackedTypeReference( payloadTypeRef ) ) );

				}

				info = buildParamInfoFromTypeRef( wrapperTypeRef );

				if (info.getFields().isEmpty()) {
					parseClassFields( wrapperTypeRef, info );

				}

			} else {
				info = payloadInfo;

			}

			info.setNullable( rb.nullable() );
			info.setPosition( LayerPosition.RESPONSE_BODY );

		}

		if (info == null) {
			return;

		}

		hasResponseBodyAnnotationOverride = true;
		handlerInfo.getResponseBodyInfo().clear();

		String key = (info.getType() != null && info.getType() != Object.class)
			? info.getType().getSimpleName()
			: (info.getTypeRef() != null ? info.getTypeRef().getSimpleName() : "Object");

		handlerInfo.getResponseBodyInfo().put( key, info );
		handlerInfo.getResponseInfoByStatusCode().put( "200", info );

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
		if (typeRef != null && info.getFields().isEmpty()) {
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
