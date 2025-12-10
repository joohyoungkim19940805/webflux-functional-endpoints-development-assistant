package com.byeolnaerim.watch.swagger;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.byeolnaerim.watch.AbstractWatcher;
import com.byeolnaerim.watch.ProjectDefaults;
import com.byeolnaerim.watch.RouteUtil;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import spoon.Launcher;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;


public class SwaggerJsonFileWatcher extends AbstractWatcher {

	public static final class Config {

		private final String watchDirectory;

		private final String swaggerOutputFile; // = "src/main/resources/static/swagger.json";

		private Config(
						Builder b
		) {

			this.watchDirectory = b.watchDirectory.replace( '\\', '/' ).replace( '.', '/' );
			int lastDotIndex = b.swaggerOutputFile.lastIndexOf( '.' );

			if (lastDotIndex == -1) {
				this.swaggerOutputFile = b.swaggerOutputFile.substring( 0, lastDotIndex ).replace( '\\', '/' ) + ".json";

			} else {
				this.swaggerOutputFile = b.swaggerOutputFile.substring( 0, lastDotIndex ).replace( '\\', '/' ).replace( '.', '/' ) + b.swaggerOutputFile.substring( lastDotIndex );

			}

		}

		public String watchDirectory() {

			return watchDirectory;

		}

		public String swaggerOutputFile() {

			return swaggerOutputFile;

		}

		public static Builder builder() {

			return new Builder();

		}

		public static final class Builder {

			private String watchDirectory = ProjectDefaults.SRC_MAIN_JAVA;

			private String swaggerOutputFile = ProjectDefaults.SWAGGER_OUTPUT_FILE;

			public Builder watchDirectory(
				String p
			) {

				this.watchDirectory = p;
				return this;

			}

			public Builder swaggerOutputFile(
				String p
			) {

				this.swaggerOutputFile = p;
				return this;

			}

			public Config build() {

				return new Config( this );

			}

		}

	}

	private final Config config;

	public SwaggerJsonFileWatcher(
									Config config
	) {

		this.config = config;

	}

	/** 오케스트레이터에서 호출하는 1회 작업 */
	public Mono<Boolean> runGenerateTask() {

		return Mono.fromCallable( () -> {
			// 1) 기존 generate 로직으로 JSON 문자열 구성
			String json = generateSwaggerJson(); // <- 기존 함수 그대로
			// 2) 실제 파일에 "변경 시에만" 기록
			Path out = Paths.get( config.swaggerOutputFile );
			return writeIfChanged( out, json.getBytes( StandardCharsets.UTF_8 ) );

		} ).subscribeOn( Schedulers.boundedElastic() );

	}

	/** 감시 루트 제공 */
	@Override
	protected Path root() {

		return Paths.get( config.watchDirectory() );

	}


	/** 기존 API 유지: 내부적으로 AbstractWatcher.start() 호출 */
	public void startWatching() {

		try {
			super.start();

		} catch (IOException e) {
			throw new RuntimeException( e );

		}

	}

	/** Swagger JSON 생성 */
	private String generateSwaggerJson() {

		try {
			List<RouteInfo> routeInfos = extractRouteInfos(); // RouteInfo 리스트 추출
			routeInfos
				.sort(
					Comparator
						.comparing( RouteInfo::getUrl )
						.thenComparing( RouteInfo::getHttpMethod )
				);

			String swaggerJson = SwaggerGenerator.generateSwaggerJson( routeInfos ); // Swagger JSON 생성
			return swaggerJson;

			// try (FileWriter writer = new FileWriter( config.swaggerOutputFile )) {
			// writer.write( swaggerJson );
			// System.out.println( "Swagger JSON file updated: " + config.swaggerOutputFile );
			// return swaggerJson;
			//
			// }

		} catch (Exception e) {
			e.printStackTrace();

		}

		return "";

	}

	/** Spoon 기반으로 RouterFunction에서 라우트 정보 추출 */
	private List<RouteInfo> extractRouteInfos() {

		Launcher launcher = new Launcher();
		launcher.addInputResource( config.watchDirectory );
		launcher.getEnvironment().setAutoImports( true );
		launcher.getEnvironment().setNoClasspath( true );
		launcher.buildModel();

		List<CtMethod<?>> routerMethods = launcher
			.getModel()
			.getElements(
				(CtMethod<?> m) -> m.getAnnotations().stream().anyMatch( a -> a.getAnnotationType().getSimpleName().equals( "Bean" ) ) && m.getType().getSimpleName().contains( "RouterFunction" )
			);

		List<RouteInfo> routeInfos = new ArrayList<>();

		for (CtMethod<?> routerMethod : routerMethods) {
			@SuppressWarnings("rawtypes")
			List<CtInvocation> httpCalls = routerMethod
				.getElements( new TypeFilter<>( CtInvocation.class ) )
				.stream()
				.filter( inv -> RouteParser.HTTP_METHODS.contains( inv.getExecutable().getSimpleName() ) )
				.toList();

			for (CtInvocation<?> httpCall : httpCalls) {
				RouteInfo routeInfo = RouteParser.extractRouteInfoFromHttpCall( httpCall, routerMethod.getSimpleName() );
				HandlerParser handlerParser = new HandlerParser();
				routeInfo
					.setHandlerInfo(
						handlerParser
							.parseHandler(
								routeInfo.getHandlerInfoCtExpression(),
								RouteUtil.convertPathToMethodName( routeInfo.getUrl() )
							)
					);
				routeInfos.add( routeInfo );

			}

		}

		return routeInfos;

	}

	// public static void main(
	// String[] args
	// ) {
	//
	// SwaggerJsonFileWatcher watcher = new SwaggerJsonFileWatcher(
	// SwaggerJsonFileWatcher.Config
	// .builder()
	// .watchDirectory( "src/main/java" )
	// .swaggerOutputFile( "src/main/resources/static/swagger.json" )
	// .build()
	// );
	// watcher.generateSwaggerJson(); // 단발 실행
	//
	// }

}
