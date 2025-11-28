package com.byeolnaerim.watch.route;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.byeolnaerim.watch.AbstractWatcher;
import com.byeolnaerim.watch.ProjectDefaults;
import com.byeolnaerim.watch.ProjectDefaults.JavaLevel;
import com.byeolnaerim.watch.RouteUtil;
import reactor.core.publisher.Mono;


/**
 * HandlerGenerator는 라우터 패키지(여러 Router 클래스)를 스캔하여
 * 라우트 경로에 따라 핸들러 클래스를 자동 생성/업데이트하고
 * 라우터의 핸들러 참조를 보정합니다.
 */
public class HandlerGenerator extends AbstractWatcher {

	public static final class StaticImportSpec {

		private final Class<?> type;

		private final String member; // "*" 사용시 와일드카드

		private final boolean wildcard;

		private StaticImportSpec(
									Class<?> type,
									String member,
									boolean wildcard
		) {

			this.type = type;
			this.member = member;
			this.wildcard = wildcard;

		}

		public static StaticImportSpec of(
			Class<?> type, String member
		) {

			return new StaticImportSpec( type, member, false );

		}

		public static StaticImportSpec wildcard(
			Class<?> type
		) {

			return new StaticImportSpec( type, "*", true );

		}

		public Class<?> type() {

			return type;

		}

		public String member() {

			return member;

		}

		public boolean wildcard() {

			return wildcard;

		}

	}

	public static final class Config {

		private String rootPath; // slash ex) src/main/java

		private final String routerPackage; // dot ex) com.byeolnaerim.web.route

		private final boolean scanWholeProject; // true면 rootPath 전체 스캔

		private final String handlerPackage; // dot ex) com.byeolnaerim.web.handler

		private final String handlerOutputDir; // slash ex) src/main/java/com/npl/auction/platform/web/handler/

		private final List<Class<?>> autoinjectionHandlerRequiredImports;

		private final List<StaticImportSpec> autoinjectionHandlerRequiredImportsStatic;

		private Config(
						Builder b
		) {

			this.rootPath = b.rootPath.replace( '\\', '/' ).replace( '.', '/' );
			this.routerPackage = b.routerPackage.replace( '\\', '/' ).replace( '/', '.' );
			this.scanWholeProject = b.scanWholeProject;
			this.handlerPackage = b.handlerPackage.replace( '\\', '/' ).replace( '/', '.' );
			this.handlerOutputDir = b.handlerOutputDir.replace( '\\', '/' ).replace( '.', '/' );
			this.autoinjectionHandlerRequiredImports = List.copyOf( b.autoinjectionHandlerRequiredImports );
			this.autoinjectionHandlerRequiredImportsStatic = List.copyOf( b.autoinjectionHandlerRequiredImportsStatic );

		}

		public String rootPath() {

			return rootPath;

		}

		public String routerPackage() {

			return routerPackage;

		}

		public boolean scanWholeProject() {

			return scanWholeProject;

		}

		public String handlerPackage() {

			return handlerPackage;

		}

		public String handlerOutputDir() {

			return handlerOutputDir;

		}

		/** 라우터 디렉터리(패키지 기반) */
		public String routerDir() {

			return rootPath + "/" + routerPackage.replace( '.', '/' ) + "/";

		}

		public List<Class<?>> autoinjectionHandlerRequiredImports() {

			return autoinjectionHandlerRequiredImports;

		}

		public List<StaticImportSpec> autoinjectionHandlerRequiredImportsStatic() {

			return autoinjectionHandlerRequiredImportsStatic;

		}

		public static Builder builder() {

			return new Builder();

		}

		public static final class Builder {

			private String rootPath = ProjectDefaults.SRC_MAIN_JAVA;

			private String routerPackage = ProjectDefaults.ROUTER_PACKAGE;

			private boolean scanWholeProject = false;

			private String handlerPackage = ProjectDefaults.HANDLER_PACKAGE;

			private String handlerOutputDir = ProjectDefaults.HANDLER_OUTPUT_DIR;

			private final List<Class<?>> autoinjectionHandlerRequiredImports = new ArrayList<>(
				List
					.of(
						org.springframework.beans.factory.annotation.Autowired.class,
						org.springframework.http.MediaType.class,
						org.springframework.stereotype.Component.class,
						org.springframework.web.reactive.function.server.ServerRequest.class,
						org.springframework.web.reactive.function.server.ServerResponse.class,
						reactor.core.publisher.Mono.class
					)
			);

			private final List<StaticImportSpec> autoinjectionHandlerRequiredImportsStatic = new ArrayList<>(
				List
					.of(
						StaticImportSpec.of( org.springframework.web.reactive.function.server.ServerResponse.class, "ok" )
					)
			);

			public Builder rootPath(
				String v
			) {

				this.rootPath = v;
				return this;

			}

			public Builder routerPackage(
				String v
			) {

				this.routerPackage = v;
				return this;

			}

			public Builder scanWholeProject(
				boolean v
			) {

				this.scanWholeProject = v;
				return this;

			}

			public Builder handlerPackage(
				String v
			) {

				this.handlerPackage = v;
				return this;

			}

			public Builder handlerOutputDir(
				String v
			) {

				this.handlerOutputDir = v;
				return this;

			}

			public Builder addAutoImport(
				Class<?> clazz
			) {

				this.autoinjectionHandlerRequiredImports.add( clazz );
				return this;

			}

			public Builder autoImports(
				List<Class<?>> classes
			) {

				this.autoinjectionHandlerRequiredImports.clear();
				this.autoinjectionHandlerRequiredImports.addAll( classes );
				return this;

			}

			public Builder addStaticAutoImport(
				StaticImportSpec spec
			) {

				this.autoinjectionHandlerRequiredImportsStatic.add( spec );
				return this;

			}

			public Builder staticAutoImports(
				List<StaticImportSpec> specs
			) {

				this.autoinjectionHandlerRequiredImportsStatic.clear();
				this.autoinjectionHandlerRequiredImportsStatic.addAll( specs );
				return this;

			}

			public Config build() {

				return new Config( this );

			}

		}

	}

	private final Config config;

	/**
	 * ✅ 인스턴스 파서 (JAVA_21 + 심볼 리졸버)
	 */
	private final JavaParser javaParser;

	public HandlerGenerator(
							Config config
	) throws IOException {

		this.config = config;

		Files.createDirectories( Paths.get( config.handlerOutputDir() ) );

		ParserConfiguration pc = new ParserConfiguration()
			.setLanguageLevel( JavaLevel.resolve( config.rootPath() ) ) // ★ 동적 감지 적용
			.setAttributeComments( false );

		CombinedTypeSolver typeSolver = new CombinedTypeSolver(
			new ReflectionTypeSolver( false )
		);

		try {
			typeSolver.add( new JavaParserTypeSolver( Paths.get( config.rootPath() ) ) );

		} catch (Exception ignore) { /* 루트 없으면 스킵 */ }

		pc.setSymbolResolver( new JavaSymbolSolver( typeSolver ) );
		this.javaParser = new JavaParser( pc );

		StaticJavaParser.setConfiguration( pc ); // static 헬퍼와 일치

	}

	/** 오케스트레이터가 호출하는 1회 작업 */
	public Mono<Boolean> runGenerateTask() {

		return Mono.fromCallable( this::generateFiles );

	}

	/** 감시 루트: 라우터 패키지(또는 프로젝트 전체) */
	@Override
	protected Path root() {

		return Paths.get( config.scanWholeProject ? config.rootPath : config.routerDir() );

	}

	/** 기존 API 유지 */
	public void startWatching() {

		try {
			super.start();

		} catch (IOException e) {
			throw new RuntimeException( e );

		}

	}

	/** 라우터 패키지(또는 전체) 스캔 → 핸들러 생성/업데이트 & 라우터 보정 */
	public boolean generateFiles() {

		try {
			boolean anyChanged = false;
			List<Path> routerFiles = listRouterJavaFiles();

			for (Path file : routerFiles) {
				anyChanged |= processRouterFile( file );

			}

			return anyChanged;

		} catch (Exception e) {
			e.printStackTrace();
			return false;

		}

	}

	/** ROUTER_DIR(or ROOT_PATH) 아래 모든 .java 나열 */
	private List<Path> listRouterJavaFiles() throws IOException {

		Path base = Paths.get( config.scanWholeProject ? config.rootPath : config.routerDir() );
		if (! Files.exists( base ))
			return List.of();

		try (Stream<Path> s = Files.walk( base )) {
			return s.filter( p -> p.toString().endsWith( ".java" ) ).collect( Collectors.toList() );

		}

	}

	private boolean processRouterFile(
		Path routerPath
	) {

		boolean anyChanged = false;

		try (InputStream in = Files.newInputStream( routerPath )) {
			// 기존: StaticJavaParser.parse(in) —> 변경: javaParser.parse(in)
			CompilationUnit cu = javaParser
				.parse( in )
				.getResult()
				.orElseThrow( () -> new IllegalStateException( "Parse failed: " + routerPath ) );
			LexicalPreservingPrinter.setup( cu );

			for (ClassOrInterfaceDeclaration clazz : cu.findAll( ClassOrInterfaceDeclaration.class )) {

				for (MethodDeclaration method : clazz.getMethods()) {
					if (! isRouterFunctionReturnType( method ))
						continue;
					BlockStmt body = method.getBody().orElse( null );
					if (body == null)
						continue;

					// A) return null; 스캐폴딩은 유지
					body.findFirst( ReturnStmt.class ).ifPresent( returnStmt -> {
						Optional<Expression> returnedExprOpt = returnStmt.getExpression();

						if (returnedExprOpt.isEmpty() || isNullLiteral( returnedExprOpt.get() )) {
							String methodName = method.getNameAsString();
							String pathForNesting = RouteUtil.convertMethodNameToPath( methodName );
							MethodCallExpr routerReturnValue = createRouteNestInvocation( pathForNesting );
							returnStmt.setExpression( routerReturnValue );
							System.out.println( "[Scaffold] " + clazz.getNameAsString() + "#" + methodName );

						}

					} );

					// B) **구버전과 동일**: 메서드 내 "첫 번째(루트) nest"만 처리
					Optional<MethodCallExpr> rootNestOpt = body.findFirst( MethodCallExpr.class, m -> m.getNameAsString().equals( "nest" ) );

					if (rootNestOpt.isEmpty()) {
						anyChanged |= saveFileWithFormatting( routerPath, cu );
						continue;

					}

					MethodCallExpr rootNest = rootNestOpt.get();

					// 루트에서 첫 path("...")를 찾아 그 경로로 클래스명 결정 (구버전과 동일)
					Optional<MethodCallExpr> firstPathOptional = rootNest
						.findFirst(
							MethodCallExpr.class,
							m -> m.getNameAsString().equals( "path" ) && m.getArguments().size() > 0 && m.getArgument( 0 ).isStringLiteralExpr()
						);

					if (firstPathOptional.isEmpty()) {
						anyChanged |= saveFileWithFormatting( routerPath, cu );
						continue;

					}

					String routerFirstPathName = convertPathToMethodName(
						firstPathOptional.get().getArgument( 0 ).asStringLiteralExpr().getValue()
					);
					String classVariableName = routerFirstPathName + "Handler";
					String className = capitalizeFirst( classVariableName );

					// 루트 nest 기준으로 엔드포인트 파싱(구버전 동일)
					List<RouteMapping> routeMappings = parseRouteMappings( rootNest, method );

					// 구버전 동일: 무조건 클래스 스켈레톤 보장
					anyChanged |= generateOrUpdateHandlerClass( className, null );

					// 각 엔드포인트 보정(핸들러 null이거나 null 반환 람다일 때만)
					routeMappings.forEach( e -> {
						String methodName = convertPathToMethodName( e.path )
							.trim()
							.replaceAll( "\\{[^}]*\\}", "" );
						int handlerIdx = e.httpMethod.getArguments().size() - 1;

						if (methodName.isBlank()) {

							if (! e.isLambdaNullReturn()) {
								e.httpMethod
									.setArgument(
										handlerIdx,
										new NameExpr(
											"request -> {return org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue(\"\");}"
										)
									);

							}

						} else if (e.isHandlerNull() || e.isLambdaNullReturn()) {
							generateOrUpdateHandlerClass( className, methodName );
							e.httpMethod
								.setArgument(
									handlerIdx,
									new MethodReferenceExpr( new NameExpr( classVariableName ), NodeList.nodeList(), methodName )
								);

						}

					} );

					// 구버전 동일: 파라미터 주입(없으면 추가)
					boolean hasParam = method
						.getParameters()
						.stream()
						.anyMatch( p -> p.getTypeAsString().equals( className ) );

					if (! hasParam) {
						method.addParameter( className, classVariableName );
						cu.addImport( config.handlerPackage() + "." + className );

					}

					anyChanged |= saveFileWithFormatting( routerPath, cu );

				}

			}

		} catch (Exception e) {
			System.err.println( "Error processing router file: " + routerPath );
			e.printStackTrace();

		}

		return anyChanged;

	}

	/** 핸들러 클래스 생성/업데이트 (파일 존재 시 javaParser로 파싱) */
	private boolean generateOrUpdateHandlerClass(
		String handlerClassName, String handlerMethodName
	) {

		String filePath = config.handlerOutputDir() + (config.handlerOutputDir().endsWith( "/" ) ? "" : "/") + handlerClassName + ".java";
		File handlerFile = Paths.get( filePath ).toFile();
		CompilationUnit handlerCU;
		ClassOrInterfaceDeclaration handlerClass = null;
		boolean changed = false;

		try {

			if (handlerFile.exists()) {

				try (InputStream in = Files.newInputStream( handlerFile.toPath() )) {
					handlerCU = javaParser
						.parse( in )
						.getResult()
						.orElseThrow( () -> new IllegalStateException( "Parse failed: " + handlerFile ) );
					LexicalPreservingPrinter.setup( handlerCU );
					var handlerClassOpt = handlerCU.getClassByName( handlerClassName );

					if (handlerClassOpt.isEmpty()) {
						System.out.println( "기존 핸들러 클래스가 없음: " + handlerClassName );
						return false;

					}

					handlerClass = handlerClassOpt.get();

					if (! hasMethod( handlerClass, handlerMethodName )) {
						addHandlerMethod( handlerClass, handlerMethodName );

					}

				}

			} else {
				handlerCU = new CompilationUnit();
				handlerCU.setPackageDeclaration( config.handlerPackage() );
				handlerClass = handlerCU.addClass( handlerClassName ).setPublic( true );
				handlerClass.addAnnotation( "Component" );
				addAutowiredField( handlerClass, "mongoQueryBuilder", "MongoQueryBuilder" );
				addHandlerMethod( handlerClass, handlerMethodName );

			}

			addHandlerRequiredImports( handlerCU );
			changed |= saveFileWithFormatting( handlerFile.toPath(), handlerCU );

		} catch (Exception e) {
			e.printStackTrace();

		}

		return changed;

	}

	/** 메서드가 RouterFunction<ServerResponse> 반환인지 판단 */
	private boolean isRouterFunctionReturnType(
		MethodDeclaration method
	) {

		if (! method.getType().isClassOrInterfaceType())
			return false;
		ClassOrInterfaceType t = method.getType().asClassOrInterfaceType();
		if (! t.getNameAsString().equals( "RouterFunction" ))
			return false;
		if (t.getTypeArguments().isEmpty())
			return false;
		return t
			.getTypeArguments()
			.get()
			.stream()
			.filter( ta -> ta.isClassOrInterfaceType() )
			.map( ta -> ta.asClassOrInterfaceType().getNameAsString() )
			.anyMatch( name -> name.equals( "ServerResponse" ) );

	}

	private void addAutowiredField(
		ClassOrInterfaceDeclaration clazz, String fieldName, String fieldType
	) {

		FieldDeclaration field = clazz.addField( fieldType, fieldName, Modifier.Keyword.PRIVATE );
		field.addAnnotation( "Autowired" );

	}

	private void addHandlerMethod(
		ClassOrInterfaceDeclaration clazz, String methodName
	) {

		if (methodName == null)
			return;
		MethodDeclaration method = clazz.addMethod( methodName, Modifier.Keyword.PUBLIC );
		method.setType( "Mono<ServerResponse>" );
		method.addParameter( new Parameter( new ClassOrInterfaceType( null, "ServerRequest" ), "request" ) );

		BlockStmt body = new BlockStmt();
		MethodCallExpr okCall = new MethodCallExpr( null, "ok" );
		MethodCallExpr contentTypeCall = new MethodCallExpr( okCall, "contentType" );
		contentTypeCall.addArgument( new FieldAccessExpr( new NameExpr( "MediaType" ), "APPLICATION_JSON" ) );

		MethodCallExpr responseCall = new MethodCallExpr( null, "response" );
		responseCall.addArgument( new NameExpr( "Result._0" ) );

		MethodCallExpr bodyCall = new MethodCallExpr( contentTypeCall, "body" );
		bodyCall.addArgument( responseCall );
		bodyCall.addArgument( new ClassExpr( StaticJavaParser.parseClassOrInterfaceType( "ResponseWrapper" ) ) );

		body.addStatement( new ReturnStmt( bodyCall ) );
		method.setBody( body );

	}

	private boolean hasMethod(
		ClassOrInterfaceDeclaration clazz, String methodName
	) {

		return methodName != null && ! clazz.getMethodsByName( methodName ).isEmpty();

	}

	private String convertPathToMethodName(
		String path
	) {

		String[] parts = path.split( "/" );
		StringBuilder methodName = new StringBuilder();

		for (String part : parts) {
			if (part.isEmpty())
				continue;
			methodName.append( Stream.of( part.split( "-" ) ).map( this::capitalize ).collect( Collectors.joining() ) );

		}

		if (methodName.length() > 0) {
			methodName.replace( 0, 1, methodName.substring( 0, 1 ).toLowerCase() );

		}

		return methodName.toString();

	}

	private String capitalize(
		String s
	) {

		if (s == null || s.isEmpty())
			return s;
		return s.substring( 0, 1 ).toUpperCase() + s.substring( 1 );

	}

	private String capitalizeFirst(
		String s
	) {

		if (s == null || s.isEmpty())
			return s;
		return s.substring( 0, 1 ).toUpperCase() + s.substring( 1 );

	}

	/** return null 인 경우 기본 route 체인 생성 */
	private MethodCallExpr createRouteNestInvocation(
		String pathForNesting
	) {

		String firstBuilderName = "builder";
		MethodCallExpr routeCall = new MethodCallExpr( null, "route" );

		MethodCallExpr pathCall = new MethodCallExpr( null, "path" );
		pathCall.addArgument( new StringLiteralExpr( pathForNesting ) );

		LambdaExpr lambda = new LambdaExpr();
		Parameter builderParam = new Parameter( new UnknownType(), firstBuilderName );
		lambda.addParameter( builderParam );

		String[] paths = {
			"/search", "/create", "/update", "/delete"
		};
		MethodCallExpr lastNest = createNestedCalls( firstBuilderName, paths );
		lambda.setBody( new BlockStmt().addStatement( lastNest ) );

		MethodCallExpr initialNest = new MethodCallExpr( routeCall, "nest" );
		initialNest.addArgument( pathCall );
		initialNest.addArgument( lambda );

		return new MethodCallExpr( initialNest, "build" );

	}

	private MethodCallExpr createNestedCalls(
		String firstBuilderName, String[] paths
	) {

		MethodCallExpr currentNest = null;

		for (int i = 0, len = paths.length; i < len; i++) {
			String path = paths[i];
			String builderName = path.substring( 1 ) + "Builder";

			MethodCallExpr pathCall = new MethodCallExpr( null, "path" );
			pathCall.addArgument( new StringLiteralExpr( path ) );

			LambdaExpr lambda = new LambdaExpr();
			Parameter nestedBuilderParam = new Parameter( new UnknownType(), builderName );
			lambda.addParameter( nestedBuilderParam );

			String httpMethod = switch (path) {
				case "/search" -> "GET";
				case "/create" -> "POST";
				case "/update" -> "PUT";
				case "/delete" -> "DELETE";
				default -> throw new IllegalArgumentException( "Unexpected value: " + path );

			};

			MethodCallExpr getCall = new MethodCallExpr( new NameExpr( builderName ), httpMethod );
			getCall.addArgument( new StringLiteralExpr( "" ) ); // 경로
			getCall
				.addArgument(
					new MethodCallExpr( null, "accept" )
						.addArgument( new FieldAccessExpr( new NameExpr( "MediaType" ), "APPLICATION_JSON" ) )
				);
			getCall
				.addArgument(
					new LambdaExpr(
						new Parameter( new UnknownType(), "request" ),
						new BlockStmt()
							.addStatement(
								new ReturnStmt(
									new NameExpr(
										"\norg.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue(\"\")\n"
									)
								)
							)
					)
				);

			BlockStmt lambdaBody = new BlockStmt();
			lambdaBody.addStatement( getCall );
			lambda.setBody( lambdaBody );

			MethodCallExpr newNest = new MethodCallExpr(
				(currentNest == null ? new NameExpr( i == 0 ? firstBuilderName + "\n" : builderName ) : currentNest),
				"nest"
			);
			newNest.addArgument( pathCall );
			newNest.addArgument( lambda );

			currentNest = newNest;

		}

		return currentNest;

	}

	private boolean isNullLiteral(
		Expression expr
	) {

		return expr.isNullLiteralExpr();

	}

	private boolean saveFileWithFormatting(
		Path filePath, CompilationUnit cu
	) {

		try {
			String newContent = LexicalPreservingPrinter.print( cu );
			boolean changed = writeIfChanged( filePath, newContent.getBytes( StandardCharsets.UTF_8 ) );

			if (changed) {
				System.out.println( "File saved with changes: " + filePath );

			} else {
				System.out.println( "No changes: " + filePath );

			}

			return changed;

		} catch (Exception e) {
			System.err.println( "Error saving file: " + filePath );
			e.printStackTrace();
			return false;

		}

	}

	/** 라우트 매핑 정보 추출 */
	private List<RouteMapping> parseRouteMappings(
		MethodCallExpr rootNest, MethodDeclaration methodDeclaration
	) {

		List<RouteMapping> routeMappings = new ArrayList<>();
		List<MethodCallExpr> httpMethodCalls = rootNest
			.findAll( MethodCallExpr.class )
			.stream()
			.filter( this::isHttpMethodCall )
			.toList();

		for (MethodCallExpr httpMethodCall : httpMethodCalls) {
			Expression handlerExpression = extractHandler( httpMethodCall );
			String relativePath = extractPath( httpMethodCall );
			routeMappings.add( new RouteMapping( httpMethodCall, relativePath.trim(), handlerExpression ) );

		}

		return routeMappings;

	}


	private Expression extractHandler(
		MethodCallExpr httpMethodCall
	) {

		return httpMethodCall.getArgument( httpMethodCall.getArguments().size() - 1 );

	}

	private String extractPath(
		MethodCallExpr httpMethodCall
	) {

		return httpMethodCall.getArgument( 0 ).asStringLiteralExpr().asString();

	}

	private boolean isHttpMethodCall(
		MethodCallExpr methodCall
	) {

		return List.of( "GET", "POST", "PUT", "DELETE" ).contains( methodCall.getNameAsString() );

	}

	private void addHandlerRequiredImports(
		CompilationUnit cu
	) {

		for (Class<?> c : config.autoinjectionHandlerRequiredImports()) {
			addImportIfAbsent( cu, c.getCanonicalName() );

		}

		for (StaticImportSpec spec : config.autoinjectionHandlerRequiredImportsStatic()) {
			String base = spec.type().getCanonicalName();
			String name = spec.wildcard() ? (base + ".*") : (base + "." + spec.member());
			addStaticImportIfAbsent( cu, name, spec.wildcard() );

		}

	}

	// 중복 방지용 헬퍼
	private void addImportIfAbsent(
		CompilationUnit cu, String fqn
	) {

		boolean exists = cu.getImports().stream().anyMatch( im -> ! im.isStatic() && im.getNameAsString().equals( fqn ) );
		if (! exists)
			cu.addImport( fqn );

	}

	private void addStaticImportIfAbsent(
		CompilationUnit cu, String fqnWithMemberOrStar, boolean isStar
	) {

		boolean exists = cu.getImports().stream().anyMatch( im -> im.isStatic() && im.getNameAsString().equals( fqnWithMemberOrStar ) );

		if (! exists) {
			cu.addImport( new ImportDeclaration( new Name( fqnWithMemberOrStar ), true, isStar ) );

		}

	}

	/** 경로 매핑 정보 */
	private static class RouteMapping {

		private final MethodCallExpr httpMethod;

		private final String path;

		private final String handler;

		private final Expression handlerExpression;

		public RouteMapping(
							MethodCallExpr httpMethod,
							String path,
							Expression handlerExpression
		) {

			this.httpMethod = httpMethod;
			this.path = path;
			this.handler = handlerExpression.toString();
			this.handlerExpression = handlerExpression;

		}

		public boolean isHandlerNull() { return this.handler == null || "null".startsWith( this.handler.trim() ) || "//".equals( this.handler.trim() ); }

		public boolean isLambdaNullReturn() {

			if (handlerExpression.isLambdaExpr()) {
				var returnStmtOpt = handlerExpression.findFirst( ReturnStmt.class );

				if (returnStmtOpt.isPresent()) { return "return null;".equals( returnStmtOpt.get().toString().trim().replaceAll( "\\R", "" ) ); }

			}

			return false;

		}

		@Override
		public String toString() {

			return String.format( "HTTP Method: %s, Path: %s, Handler: %s", httpMethod.getNameAsString(), path, handler );

		}

	}

//	@SuppressWarnings("resource")
//	public static void main(
//		String[] args
//	)
//		throws IOException {
//
//		new HandlerGenerator(
//			HandlerGenerator.Config
//				.builder()
//				.rootPath( "src/main/java" )
//				.routerPackage( "com.byeolnaerim.web.route" )
//				.scanWholeProject( false )
//				.handlerPackage( "com.byeolnaerim.web.handler" )
//				.handlerOutputDir( "src/main/java/com/npl/auction/platform/web/handler/" )
//
//				// 일반 import 주입 (Class 기반)
//				.addAutoImport( com.byeolnaerim.util.MongoQueryBuilder.class )
//				.addAutoImport( com.byeolnaerim.util.ResponseWrapper.class )
//				.addAutoImport( com.byeolnaerim.util.exception.DefineGlobalErrorException.Result.class )
//
//				// static import 주입
//				.addStaticAutoImport( HandlerGenerator.StaticImportSpec.of( com.byeolnaerim.util.ResponseWrapper.class, "response" ) )
//				.addStaticAutoImport( HandlerGenerator.StaticImportSpec.of( com.byeolnaerim.util.FieldsPair.class, "pair" ) )
//				.addStaticAutoImport( HandlerGenerator.StaticImportSpec.of( org.springframework.web.reactive.function.server.ServerResponse.class, "ok" ) )
//				.build()
//		).generateFiles();
//
//	}

}
