package com.starbearing.watch;


import java.util.List;
import java.util.stream.Stream;


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
	 * 메소드 이름을 기반으로 경로를 설정합니다.
	 * 예: "/api/menu" -> "apiMenu", "/oauth2" -> "oauth2"
	 *
	 * @param methodName
	 *            메소드 이름
	 * 
	 * @return 설정된 경로 문자열
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
	 * 메소드 이름을 기반으로 경로를 설정합니다.
	 * 예: "/api/menu" -> "apiMenu", "/oauth2" -> "oauth2"
	 *
	 * @param methodName
	 *            메소드 이름
	 * 
	 * @return 설정된 경로 문자열
	 */
	public static String convertPathToMethodName(
		String url
	) {

		return convertPathToMethodName( url, "/" );

	}

	public static List<String> parserEnumValues(
		Class<?> clazz
	) {

		@SuppressWarnings("rawtypes")
		Class<? extends Enum> enumCalzz = clazz.asSubclass( Enum.class );
		return Stream.of( enumCalzz.getEnumConstants() ).map( e -> e.name() ).toList();

	}

	/**
	 * 주어진 타입이 POJO인지 확인
	 */
	public static boolean isPojo(
		Class<?> type
	) {

		// java.lang 패키지에 포함되지 않고, 기본적으로 사용자 정의 클래스라고 간주
		return ! type.isPrimitive() && ! type.getPackageName().startsWith( "java." ) && ! type.isEnum();

	}

}
