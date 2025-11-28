package com.byeolnaerim.watch;


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

	public static final String TRIGGER_FILE; // slash

	private static final String BASE_PACKAGE_OVERRIDE_PROP = "app.basePackage";

	static {
		Path projectRoot = detectProjectRoot();
		SRC_MAIN_JAVA = projectRoot.resolve( "src/main/java" ).toString().replace( '\\', '/' );
		SRC_MAIN_RESOURCES = projectRoot.resolve( "src/main/resources" ).toString().replace( '\\', '/' );

		// basePackage 추정: 이 클래스 패키지에서 ".watch" 앞까지
		String pkg = ProjectDefaults.class.getPackageName(); // ex) com.byeolnaerim.watch
		String guessedBase = pkg;
		int idx = pkg.indexOf( ".watch" );
		if (idx > 0)
			guessedBase = pkg.substring( 0, idx );

		// 시스템 프로퍼티로 재정의 가능: -Dapp.basePackage=com.foo.bar
		String override = System.getProperty( BASE_PACKAGE_OVERRIDE_PROP );

		if (override != null && ! override.isBlank()) {
			guessedBase = override.trim();

		}

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
		TRIGGER_FILE = SRC_MAIN_RESOURCES + "/.reloadtrigger";

	}

	private static Path detectProjectRoot() {

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
