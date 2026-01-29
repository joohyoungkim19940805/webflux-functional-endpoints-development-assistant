package com.byeolnaerim.watch.document.swagger.functional;


import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import com.byeolnaerim.watch.RouteUtil;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;


public class RouteParser {


	public static final Set<String> HTTP_METHODS = new HashSet<>( Arrays.asList( "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "TRACE" ) );


	public static RouteInfo extractRouteInfoFromHttpCall(
		CtInvocation<?> httpCall, String routeMethodName
	) {

		String methodName = httpCall.getExecutable().getSimpleName();

		// GET("/path") 인자 추출
		Optional<String> pathOpt = httpCall
			.getArguments()
			.stream()
			.filter( a -> a instanceof CtLiteral )
			.map( a -> ((CtLiteral<?>) a).getValue() )
			.filter( v -> v instanceof String )
			.map( v -> (String) v )
			.findFirst();

		String lastSegment = pathOpt.orElse( "" );

		// acceptMediaTypes 추출
		List<String> acceptMedia = extractAcceptMediaTypesFromChain( httpCall );

		// 이제 상위로 올라가며 GET 호출이 속한 람다 -> 그 람다를 인자로 받는 nest(...) 또는 path(...)를 찾아 경로 스택 구성
		List<String> pathStack = buildPathStackFromLambdas( httpCall );

		String prefix = String.join( "", pathStack );
		String finalPath = prefix + lastSegment;
		//
		// String convertedPath = RouteUtil.convertMethodNameToPath( routeMethodName );
		//
		// if (! finalPath.startsWith( convertedPath )) {
		// return null; // 유효하지 않은 URL
		//
		// }

		RouteInfo info = new RouteInfo();
		info.setHttpMethod( methodName );
		info.setUrl( finalPath );
		info.setEndpoint( extractEndpoint( finalPath ) );
		info.setParentGroup( RouteUtil.convertMethodNameToPath( routeMethodName ) );
		info.setChildGroup( ((Supplier<String>) () -> {
			String[] paths = Stream
				.of(
					finalPath.replace( RouteUtil.convertMethodNameToPath( routeMethodName ), "" ).split( "/" )
				)
				.filter( e -> ! e.trim().isBlank() )
				.toArray( String[]::new );
			if (paths.length == 0)
				return "";
			String subPath = paths[0];
			if (List.of( "search", "create", "delete", "update" ).contains( subPath ))
				return subPath;
			return "";

		}).get() );
		info.getAcceptMediaTypes().addAll( acceptMedia );

		// 여기서 handler나 람다 정보 추출
		extractHandlerInfo( httpCall, info );

		return info;

	}

	/**
	 * GET에서 시작하여 상위로 올라가 람다(CtLambda)를 만나면, 그 람다가 속한 nest(...)나 path(...) 호출을 찾아
	 * pathStack에 경로를 누적한다.
	 * 이 과정을 반복하여 최상위까지 올라간 뒤, 최종 pathStack을 리턴.
	 */
	private static List<String> buildPathStackFromLambdas(
		CtInvocation<?> httpCall
	) {

		List<String> stack = new ArrayList<>();

		CtElement current = httpCall;

		while (current != null) {
			// 람다 내부인지 검사
			CtLambda<?> lambda = getEnclosingLambda( current );

			if (lambda == null) {
				// 더 이상 람다가 없으면 종료
				break;

			}

			// 이 람다를 인자로 받는 nest(...) 또는 path(...) 호출을 찾는다.
			CtInvocation<?> parentInvocation = getLambdaParentInvocation( lambda );

			if (parentInvocation != null) {
				// parentInvocation이 nest(...)나 path(...)이면 path(...) 인자를 추출
				String name = parentInvocation.getExecutable().getSimpleName();

				if (name.equals( "nest" )) {
					extractPathFromNest( parentInvocation, stack );

				} else if (name.equals( "path" )) {
					extractPathFromPathInvocation( parentInvocation, stack );

				}

			}

			// 다음 단계로 올라가기 위해 lambda의 상위로 이동
			current = parentInvocation;

		}

		// stack은 하위(lambda 깊은쪽)에서 상위로 모은 것이므로 reverse
		Collections.reverse( stack );
		return stack;

	}

	private static CtLambda<?> getEnclosingLambda(
		CtElement element
	) {

		CtElement current = element;

		while (current != null) {

			if (current instanceof CtLambda) { return (CtLambda<?>) current; }

			current = current.getParent();

		}

		return null;

	}

	/**
	 * 람다를 인자로 받는 nest(...)나 path(...) 호출 찾기
	 */
	private static CtInvocation<?> getLambdaParentInvocation(
		CtLambda<?> lambda
	) {

		CtElement parent = lambda.getParent();

		while (parent != null) {

			if (parent instanceof CtInvocation) {
				CtInvocation<?> inv = (CtInvocation<?>) parent;
				String name = inv.getExecutable().getSimpleName();

				// nest(...)나 path(...) 호출 중 lambda를 인자로 갖는지 확인
				if ((name.equals( "nest" ) || name.equals( "path" )) && inv.getArguments().contains( lambda )) { return inv; }

			}

			parent = parent.getParent();

		}

		return null;

	}

	private static void extractPathFromNest(
		CtInvocation<?> nestInvocation, List<String> stack
	) {

		if (nestInvocation.getArguments().isEmpty())
			return;
		CtExpression<?> firstArg = nestInvocation.getArguments().get( 0 );

		if (firstArg instanceof CtInvocation) {
			CtInvocation<?> pathCall = (CtInvocation<?>) firstArg;

			if (pathCall.getExecutable().getSimpleName().equals( "path" )) {
				extractPathFromPathInvocation( pathCall, stack );

			}

		}

	}

	private static void extractPathFromPathInvocation(
		CtInvocation<?> pathInvocation, List<String> stack
	) {

		pathInvocation
			.getArguments()
			.stream()
			.filter( a -> a instanceof CtLiteral )
			.map( a -> ((CtLiteral<?>) a).getValue() )
			.filter( v -> v instanceof String )
			.map( v -> (String) v )
			.forEach( stack::add );

	}

	private static List<String> extractAcceptMediaTypesFromChain(
		CtInvocation<?> httpCall
	) {

		List<String> mediaTypes = new ArrayList<>();

		// 1) GET("/xxx", accept(MediaType.XXX), ...) 형태 처리
		// 두 번째 인자가 accept(...) 호출이면 그 즉시 MediaType 반환
		if (httpCall.getArguments().size() > 1) {
			CtExpression<?> secondArg = httpCall.getArguments().get( 1 );

			if (secondArg instanceof CtInvocation<?> acceptInv) {

				if (acceptInv.getExecutable().getSimpleName().equals( "accept" )) {
					return acceptInv
						.getArguments()
						.stream()
						.filter( e -> e.getType() != null && e.getType().getActualClass().equals( MediaType.class ) && e instanceof CtFieldAccess )
						.map( e -> e.toString() )
						.collect( Collectors.toList() );

				}

			}

		}

		// 2) 위 형태가 아니라면 상위로 올라가며 and(accept(...))나 직접적인 accept(...) 호출을 찾는다.
		CtElement current = httpCall;

		while (current != null) {

			if (current instanceof CtInvocation<?> inv) {
				String name = inv.getExecutable().getSimpleName();

				// and(...)에서 accept(...) 탐색
				if (name.equals( "and" )) {

					for (CtExpression<?> arg : inv.getArguments()) {

						if (arg instanceof CtInvocation<?> argInv) {

							if (argInv.getExecutable().getSimpleName().equals( "accept" )) {
								extractMediaTypesFromAccept( argInv, mediaTypes );

							}

						}

					}

				}

				// 직접 accept(...) 호출인지 검사
				if (name.equals( "accept" )) {
					extractMediaTypesFromAccept( inv, mediaTypes );

				}

			}

			current = current.getParent();

		}

		return mediaTypes;

	}

	private static void extractMediaTypesFromAccept(
		CtInvocation<?> acceptInv, List<String> mediaTypes
	) {

		acceptInv
			.getArguments()
			.stream()
			.filter( e -> e.getType() != null && e.getType().getActualClass().equals( MediaType.class ) && e instanceof CtFieldAccess )
			.map( e -> e.toString() )
			.forEach( mediaTypes::add );

	}

	private static String extractEndpoint(
		String fullPath
	) {

		String[] segments = fullPath.split( "/" );

		for (int i = segments.length - 1; i >= 0; i--) {

			if (! segments[i].trim().isEmpty()) { return segments[i]; }

		}

		return "";

	}

	// handler나 람다 정보 추출
	private static void extractHandlerInfo(
		CtInvocation<?> httpCall, RouteInfo info
	) {

		// HTTP_METHOD 호출 시 인자가 2개 초과하거나, 마지막 인자가 메서드 참조나 람다인 경우 해당 정보 추출
		List<CtExpression<?>> args = httpCall.getArguments();

		if (args.size() > 2) {
			// 마지막 인자 확인
			CtExpression<?> lastArg = args.get( args.size() - 1 );
			info.setHandlerInfoCtExpression( lastArg );
			return;

		} else if (args.size() == 2) {
			// 인자가 정확히 2개일 때, 두 번째 인자가 handler나 람다일 경우도 처리
			CtExpression<?> secondArg = args.get( 1 );
			info.setHandlerInfoCtExpression( secondArg );
			return;

		}

		// 위 케이스가 아니고, route(..., request -> {...}) 같은 경우
		// 위 예제처럼 route(GET("/health").and(...), request -> ...) 패턴 처리
		// 즉, 상위 invocation을 찾아 람다 핸들러가 있는지 검사
		CtElement parent = httpCall.getParent();

		while (parent != null) {

			if (parent instanceof CtInvocation<?> parentInv) {
				// route(..., lambda)
				List<CtExpression<?>> pArgs = parentInv.getArguments();

				// 마지막 arg가 람다인지 확인
				if (! pArgs.isEmpty()) {
					CtExpression<?> lastPArg = pArgs.get( pArgs.size() - 1 );
					info.setHandlerInfoCtExpression( lastPArg );
					return;

				}

			}

			parent = parent.getParent();

		}

	}

	// private static String getHandlerOrLambdaInfo(
	// CtExpression<?> expr
	// ) {
	//
	// // 메서드 참조(handler::method)
	// if (expr instanceof CtExecutableReferenceExpression<?, ?> refExp) {
	// CtExecutableReference<?> ref = refExp.getExecutable();
	// // handler 메서드 정보 추출: 예: "handlerClassName::methodName"
	// String className = ref.getDeclaringType() != null ? ref.getDeclaringType().getQualifiedName() :
	// "unknown";
	// return className + "::" + ref.getSimpleName();
	//
	// }
	//
	// // 람다식(request -> ...)
	// if (expr instanceof CtLambda<?> lambda) {
	// // 람다를 문자열로 표현하거나 람다 본문 요약
	// return "Lambda: " + lambda.toString();
	//
	// }
	//
	// return null;
	//
	// }

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

		// @Bean + RouterFunction<ServerResponse> 메서드 찾기
		List<CtMethod<?>> routerMethods = model
			.getElements(
				(CtMethod<?> m) -> m.getAnnotations().stream().anyMatch( a -> a.getAnnotationType().getSimpleName().equals( "Bean" ) ) && m.getType().getSimpleName().contains( "RouterFunction" )
			);

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

			for (CtInvocation<?> httpCall : httpCalls) {
				RouteInfo info = extractRouteInfoFromHttpCall( httpCall, routeMethodName );

				if (info != null) {
					System.out.println( info );

				}

			}

		}

	}

}
