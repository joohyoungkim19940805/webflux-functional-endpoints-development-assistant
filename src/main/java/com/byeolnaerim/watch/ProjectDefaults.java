package com.byeolnaerim.watch;


import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import com.github.javaparser.ParserConfiguration;


/**
 * 모든 Watcher/Generator가 공유하는 기본값 제공자.
 * - 빌드 출력 위치( target/classes / build/classes/... )에서 프로젝트 루트 추정
 * - src/main/java & resources 경로 기본값
 * - basePackage는 `.watch` 앞을 잘라 추정(필요시 -Dapp.basePackage=... 로 재정의)
 * - router/handler/entity/field 패키지 및 핸들러 출력 디렉터리 기본값 제공
 */
public final class ProjectDefaults {

	public static final String SRC_MAIN_JAVA;

	public static final String SRC_MAIN_RESOURCES;

	public static final String BASE_PACKAGE;

	public static final String ROUTER_PACKAGE;

	public static final String HANDLER_PACKAGE;

	public static final String ENTITY_PACKAGE;

	public static final String FIELD_PACKAGE;

	public static final String COLLECTION_NAME_PACKAGE;

	public static final String HANDLER_OUTPUT_DIR; // slash

	public static final String WATCH_DIR; // slash (Swagger watchDirectory)

	public static final String SWAGGER_OUTPUT_FILE; // slash

	private static final String BASE_PACKAGE_OVERRIDE_PROP = "app.basePackage";

	static {
		Path projectRoot = detectProjectRoot();

		SRC_MAIN_JAVA = projectRoot.resolve( "src/main/java" ).toString().replace( '\\', '/' );
		SRC_MAIN_RESOURCES = projectRoot.resolve( "src/main/resources" ).toString().replace( '\\', '/' );

		String guessedBase = detectBasePackage( projectRoot )
			.orElseGet( () -> {
				var url = ProjectDefaults.class.getProtectionDomain().getCodeSource().getLocation();
				Path loc;

				try {
					loc = Paths.get( url.toURI() ).toAbsolutePath().normalize();

				} catch (URISyntaxException e) {
					return null;

				}

				return loc.toString();

			} );

		BASE_PACKAGE = guessedBase;

		// 공통 도메인 패키지 기본값
		ROUTER_PACKAGE = BASE_PACKAGE + ".web.route";
		HANDLER_PACKAGE = BASE_PACKAGE + ".web.handler";
		ENTITY_PACKAGE = BASE_PACKAGE + ".entity";
		FIELD_PACKAGE = BASE_PACKAGE + ".util.fields";
		COLLECTION_NAME_PACKAGE = BASE_PACKAGE + ".util";

		// 출력/파일 경로 기본값 (slash 기준)
		HANDLER_OUTPUT_DIR = (SRC_MAIN_JAVA + "/" + HANDLER_PACKAGE.replace( '.', '/' ) + "/").replaceAll( "/+", "/" );
		WATCH_DIR = SRC_MAIN_JAVA;
		SWAGGER_OUTPUT_FILE = SRC_MAIN_RESOURCES + "/static/swagger.json";

	}

	// private static Path detectProjectRoot() {
	//
	// Path p = Paths
	// .get( System.getProperty( "user.dir", "." ) )
	// .toAbsolutePath()
	// .normalize();
	//
	// for (int i = 0; i < 8 && p != null; i++) {
	// if (Files.isDirectory( p.resolve( "src" ) ))
	// return p;
	// p = p.getParent();
	//
	// }
	//
	// // 못 찾으면 그냥 실행 경로
	// return Paths.get( System.getProperty( "user.dir", "." ) ).toAbsolutePath().normalize();
	// }

	protected static Path detectClasspathRoot(
		Path projectRoot
	) {

		Class<?> clazz = null;

		// 2) JVM 실행 커맨드에서 main class 추출 (IDE 실행이면 보통 "com.xxx.Main args...")
		try {
			String cmd = System.getProperty( "sun.java.command" );

			if (cmd != null && ! cmd.isBlank() && ! cmd.startsWith( "-jar" )) {
				String main = cmd.split( "\\s+" )[0];
				clazz = Class.forName( main );

			}

		} catch (Throwable ignore) {}

		if (clazz != null) {

			try {
				var url = clazz.getProtectionDomain().getCodeSource().getLocation();
				Path loc = Paths.get( url.toURI() ).toAbsolutePath().normalize();

				if (Files.isDirectory( loc ))
					return loc;

			} catch (Exception ignore) {}

		}

		// fallback: gradle -> maven 순으로 존재하는 곳 선택
		Path gradleMain = projectRoot.resolve( "build/classes/java/main" );
		if (Files.isDirectory( gradleMain ))
			return gradleMain;

		Path binMain = projectRoot.resolve( "bin/main" );
		if (Files.isDirectory( binMain ))
			return binMain;


		Path maven = projectRoot.resolve( "target/classes" );
		if (Files.isDirectory( maven ))
			return maven;

		// default = gradle 경로로 반환 / 없으면 touch할 때 디렉터리 생성
		return gradleMain;

	}

	protected static Path detectProjectRoot() {

		try {
			var url = ProjectDefaults.class.getProtectionDomain().getCodeSource().getLocation();
			Path loc = Paths.get( url.toURI() );
			Path dir = Files.isRegularFile( loc ) ? loc.getParent() : loc; // jar → 상위, 폴더 그대로
			Path p = dir;

			for (int i = 0; i < 8 && p != null; i++) {
				if (Files.isDirectory( p.resolve( "src" ) ))
					return p;
				p = p.getParent();

			}

			return Paths.get( "" ).toAbsolutePath();

		} catch (Exception ignored) {
			return Paths.get( "" ).toAbsolutePath();

		}

	}

	private static Optional<String> detectBasePackage(
		Path projectRoot
	) {

		// 0) override
		String override = System.getProperty( BASE_PACKAGE_OVERRIDE_PROP );

		if (override != null && ! override.isBlank()) { return Optional.of( override.trim() ); }

		// 1) sun.java.command에서 main class
		try {
			String cmd = System.getProperty( "sun.java.command" );

			if (cmd != null && ! cmd.isBlank() && ! cmd.startsWith( "-jar" )) {
				String main = cmd.split( "\\s+" )[0];
				Class<?> clazz = Class.forName( main );
				String pkg = clazz.getPackageName();
				if (pkg != null && ! pkg.isBlank())
					return Optional.of( pkg );

			}

		} catch (Throwable ignore) {}

		// 2) -jar 실행이면 manifest의 Main-Class 시도 (가능한 범위에서)
		try {
			// sun.java.command가 jar 경로인 경우가 많음
			String cmd = System.getProperty( "sun.java.command" );

			if (cmd != null && cmd.endsWith( ".jar" )) {
				Path jar = Paths.get( cmd ).toAbsolutePath().normalize();

				try (java.util.jar.JarFile jf = new java.util.jar.JarFile( jar.toFile() )) {
					var mf = jf.getManifest();

					if (mf != null) {
						String mainClass = mf.getMainAttributes().getValue( "Main-Class" );

						if (mainClass != null && ! mainClass.isBlank()) {
							Class<?> clazz = Class.forName( mainClass.trim() );
							String pkg = clazz.getPackageName();
							if (pkg != null && ! pkg.isBlank())
								return Optional.of( pkg );

						}

					}

				}

			}

		} catch (Throwable ignore) {}

		// 3) src/main/java에서 앵커 탐색 (SpringBootApplication / main / *Application.java)
		Path srcMainJava = projectRoot.resolve( "src/main/java" );
		Optional<String> fromSrc = detectBasePackageFromSources( srcMainJava );
		if (fromSrc.isPresent())
			return fromSrc;

		return Optional.empty();

	}

	private static Optional<String> detectBasePackageFromSources(
		Path srcMainJava
	) {

		if (! Files.isDirectory( srcMainJava ))
			return Optional.empty();

		try (var stream = Files.walk( srcMainJava )) {
			// 1순위: *Application.java (스프링 부트 관례)
			Optional<Path> app = stream
				.filter( p -> p.toString().endsWith( ".java" ) )
				.filter( p -> p.getFileName().toString().endsWith( "Application.java" ) )
				.findFirst();

			if (app.isPresent()) { return readPackageDeclaration( app.get() ); }

		} catch (Throwable ignore) {}

		// 2순위: @SpringBootApplication 또는 main 메서드 포함 파일
		try (var stream = Files.walk( srcMainJava )) {
			Optional<Path> anchor = stream
				.filter( p -> p.toString().endsWith( ".java" ) )
				.filter( p -> {

					try {
						// 파일 전체 읽기 대신 적당량만 읽는 게 더 안전/빠름
						String s = Files.readString( p );
						return s.contains( "@SpringBootApplication" ) || s.contains( "public static void main(" );

					} catch (Throwable e) {
						return false;

					}

				} )
				.findFirst();

			if (anchor.isPresent()) { return readPackageDeclaration( anchor.get() ); }

		} catch (Throwable ignore) {}

		return Optional.empty();

	}

	private static Optional<String> readPackageDeclaration(
		Path javaFile
	) {

		try (var lines = Files.lines( javaFile )) {
			return lines
				.map( String::trim )
				.filter( l -> l.startsWith( "package " ) && l.endsWith( ";" ) )
				.map( l -> l.substring( "package ".length(), l.length() - 1 ).trim() )
				.filter( s -> ! s.isBlank() )
				.findFirst();

		} catch (Throwable ignore) {
			return Optional.empty();

		}

	}

	/** 슬래시 정규화(백슬래시→슬래시) */
	public static String slash(
		String v
	) {

		return v == null ? null : v.replace( '\\', '/' );

	}

	/** 패키지 표기(슬래시→닷) */
	public static String dot(
		String v
	) {

		return v == null ? null : v.replace( '\\', '/' ).replace( '/', '.' );

	}

	/** 점표기 → 경로 슬래시 ('.'만 '/'로) */
	public static String slashFromDots(
		String v
	) {

		return v == null ? null : v.replace( '\\', '/' ).replace( '.', '/' );

	}

	private ProjectDefaults() {}

	public static final class JavaLevel {

		/** 우선순위: 시스템 프로퍼티 > 빌드파일(pom/gradle) > 현재 JVM 런타임 */
		public static ParserConfiguration.LanguageLevel resolve(
			String rootPath
		) {

			// 1) 수동 오버라이드 (원하면 -Djava.parser.level=17 같은 식으로 넘길 수 있음)
			Integer feature = parseFeature( System.getProperty( "java.parser.level" ) );
			if (feature != null)
				return map( feature );

			// 2) 빌드 파일 스캔 (pom.xml / build.gradle[.kts])
			feature = detectFromBuild( rootPath );
			if (feature != null)
				return map( feature );

			// 3) 현재 JVM 런타임 버전
			try {
				int f = Runtime.version().feature(); // Java 9+
				return map( f );

			} catch (Throwable ignore) {
				// Java 8 계열이면 java.specification.version 사용
				String spec = System.getProperty( "java.specification.version", "8" );
				return map( parseFeature( spec ) != null ? parseFeature( spec ) : 8 );

			}

		}

		private static Integer detectFromBuild(
			String rootPath
		) {

			try {
				// rootPath가 보통 "src/main/java"니까 프로젝트 루트 추정
				Path guessed = Paths.get( rootPath ).toAbsolutePath();
				Path dir = Files.isDirectory( guessed ) ? guessed : guessed.getParent();

				if (dir != null && dir.endsWith( "src/main/java" )) {
					dir = dir.getParent() != null ? dir.getParent().getParent() : dir;

				}

				// 상위 디렉터리로 최대 몇 단계 올라가며 pom/gradle 탐색
				for (Path cur = dir; cur != null; cur = cur.getParent()) {
					// Maven
					Path pom = cur.resolve( "pom.xml" );

					if (Files.exists( pom )) {
						String xml = Files.readString( pom );
						Integer f = regex( xml, "(?s)<maven\\.compiler\\.release>\\s*(\\d+)\\s*</" ) // release가 있으면 최우선
							.or( () -> regex( xml, "(?s)<maven\\.compiler\\.source>\\s*([\\d.]+)\\s*</" ) )
							.or( () -> regex( xml, "(?s)<source>\\s*([\\d.]+)\\s*</" ) )
							.map( JavaLevel::parseFeature )
							.orElse( null );
						if (f != null)
							return normalize( f );

					}

					// Gradle
					for (String name : new String[] {
						"build.gradle.kts", "build.gradle"
					}) {
						Path gradle = cur.resolve( name );

						if (Files.exists( gradle )) {
							String g = Files.readString( gradle );
							Integer f =
								// toolchain: java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
								regex( g, "languageVersion\\s*=\\s*JavaLanguageVersion\\.of\\((\\d+)\\)" )
									// sourceCompatibility = JavaVersion.VERSION_21
									.or( () -> regex( g, "sourceCompatibility\\s*=\\s*JavaVersion\\.VERSION_(\\d+)" ) )
									// sourceCompatibility = '17' 혹은 "17"
									.or( () -> regex( g, "sourceCompatibility\\s*=\\s*['\\\"]([\\d.]+)['\\\"]" ) )
									.map( JavaLevel::parseFeature )
									.orElse( null );
							if (f != null)
								return normalize( f );

						}

					}

					// 루트까지 올라갔으면 종료
					if (cur.getParent() == null)
						break;

				}

			} catch (Exception ignore) {}

			return null;

		}

		private static Optional<String> regex(
			String text, String pattern
		) {

			var m = Pattern.compile( pattern ).matcher( text );
			return m.find() ? Optional.ofNullable( m.group( 1 ) ) : Optional.empty();

		}

		/** "1.8" -> 8, "17" -> 17 */
		private static Integer parseFeature(
			String v
		) {

			if (v == null)
				return null;
			v = v.trim();
			if (v.startsWith( "1." ))
				v = v.substring( 2 );

			try {
				return Integer.parseInt( v );

			} catch (Exception e) {
				return null;

			}

		}

		/** 이상치 보정 (예: 1.8 -> 8) */
		private static Integer normalize(
			Integer f
		) {

			if (f == null)
				return null;
			if (f < 8)
				return 8;
			return f;

		}

		/** Java feature 버전을 LanguageLevel로 매핑 */
		private static ParserConfiguration.LanguageLevel map(
			int feature
		) {

			return switch (feature) {
				case 8 -> ParserConfiguration.LanguageLevel.JAVA_8;
				case 9 -> ParserConfiguration.LanguageLevel.JAVA_9;
				case 10 -> ParserConfiguration.LanguageLevel.JAVA_10;
				case 11 -> ParserConfiguration.LanguageLevel.JAVA_11;
				case 12 -> ParserConfiguration.LanguageLevel.JAVA_12;
				case 13 -> ParserConfiguration.LanguageLevel.JAVA_13;
				case 14 -> ParserConfiguration.LanguageLevel.JAVA_14;
				case 15 -> ParserConfiguration.LanguageLevel.JAVA_15;
				case 16 -> ParserConfiguration.LanguageLevel.JAVA_16;
				case 17 -> ParserConfiguration.LanguageLevel.JAVA_17;
				case 18 -> ParserConfiguration.LanguageLevel.JAVA_18;
				case 19 -> ParserConfiguration.LanguageLevel.JAVA_19;
				case 20 -> ParserConfiguration.LanguageLevel.JAVA_20;
				case 21 -> ParserConfiguration.LanguageLevel.JAVA_21;
				default -> ParserConfiguration.LanguageLevel.JAVA_21; // 최신 상한선으로 고정

			};

		}

	}



}
