package com.byeolnaerim.watch;


import java.util.List;
import java.util.stream.Stream;


/**
 * Utility methods for converting between route paths, handler method names,
 * enum values, and simple type classification helpers.
 */
public class RouteUtil {

	/**
	 * 메소드 이름을 기반으로 경로를 설정합니다.
	 * 예: "apiMenu" -> "/api/menu", "oauth2" -> "/oauth2"
	 *
	 * @param methodName
	 *            메소드 이름
	 * 
	 * @return 설정된 경로 문자열
	 */
	/**
	 * Converts a camel-case method name into a slash-separated route path.
	 * <p>Example: {@code apiMenu -> /api/menu}</p>
	 * 메소드 이름을 기반으로 경로를 설정합니다.
	 * <p>예: "apiMenu" -> "/api/menu", "oauth2" -> "/oauth2"</p>
	 * 
	 * @param methodName
	 *            the method name
	 *            메소드 이름
	 * 
	 * @return the generated route path
	 *         설정된 경로 문자열
	 */
	public static String convertMethodNameToPath(
		String methodName
	) {

		// camelCase를 스네이크 케이스로 변환 후, '/'로 대체
		String[] parts = methodName.split( "(?=[A-Z])" );
		StringBuilder path = new StringBuilder();

		for (String part : parts) {
			path.append( "/" ).append( part.toLowerCase() );

		}

		return path.toString();

	}

	/**
	 * Converts a separated path string into a camel-case method name.
	 * <p>Example: {@code /api/menu -> apiMenu}</p>
	 *
	 * @param url
	 *            the source path
	 * @param separator
	 *            the path separator to split on
	 * 
	 * @return the generated method name
	 */
	public static String convertPathToMethodName(
		String url, String separator
	) {

		String[] paths = Stream.of( url.split( separator ) ).filter( e -> ! e.trim().isBlank() ).toArray( String[]::new );
		StringBuilder path = new StringBuilder();

		for (int i = 0, len = paths.length; i < len; i += 1) {

			if (i == 0) {
				path.append( paths[i] );
				continue;

			}

			String newString = Character.toUpperCase( paths[i].charAt( 0 ) ) + paths[i].substring( 1 );

			if (newString.contains( "-" )) {
				newString = convertPathToMethodName( newString, "-" );

			}

			path.append( newString );

		}

		return path.toString();

	}

	/**
	 * Converts a slash-separated path into a camel-case method name.
	 *
	 * @param url
	 *            the source path
	 * 
	 * @return the generated method name
	 */
	public static String convertPathToMethodName(
		String url
	) {

		return convertPathToMethodName( url, "/" );

	}

	/**
	 * Returns all enum constant names of the given enum type.
	 *
	 * @param clazz
	 *            the enum class
	 * 
	 * @return the enum constant names
	 */
	public static List<String> parserEnumValues(
		Class<?> clazz
	) {

		@SuppressWarnings("rawtypes")
		Class<? extends Enum> enumCalzz = clazz.asSubclass( Enum.class );
		return Stream.of( enumCalzz.getEnumConstants() ).map( e -> e.name() ).toList();

	}

	/**
	 * Returns whether the given type should be treated as a user-defined POJO.
	 * <p>This is a lightweight heuristic used by parser/generator code and is not intended
	 * to be a strict domain-model classifier.</p>
	 *
	 * @param type
	 *            the target type
	 * 
	 * @return {@code true} if the type is treated as a POJO
	 */
	public static boolean isPojo(
		Class<?> type
	) {

		// java.lang 패키지에 포함되지 않고, 기본적으로 사용자 정의 클래스라고 간주
		return ! type.isPrimitive() && ! type.getPackageName().startsWith( "java." ) && ! type.isEnum();

	}

}
