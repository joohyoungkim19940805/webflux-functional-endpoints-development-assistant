package com.byeolnaerim.watch.document.common;


import java.util.Arrays;
import java.util.stream.Collectors;
import spoon.reflect.declaration.CtElement;


/** Source-comment helpers used by document generators. */
public final class SourceDocumentationUtil {

	private SourceDocumentationUtil() {}

	/**
	 * Returns the first usable source comment attached to the given element.
	 * Javadoc tags are excluded from the generated description.
	 */
	public static String description(
		CtElement element
	) {

		if (element == null || element.getComments() == null) {
			return null;

		}

		return element
			.getComments()
			.stream()
			.map( comment -> normalize( comment.getContent() ) )
			.filter( value -> value != null && ! value.isBlank() )
			.findFirst()
			.orElse( null );

	}

	/** Returns a concise first sentence/line for operation summaries. */
	public static String summary(
		CtElement element
	) {

		String description = description( element );

		if (description == null || description.isBlank()) {
			return null;

		}

		int lineEnd = description.indexOf( '\n' );
		String firstLine = lineEnd >= 0 ? description.substring( 0, lineEnd ).trim() : description.trim();
		int sentenceEnd = firstLine.indexOf( ". " );

		return sentenceEnd >= 0 ? firstLine.substring( 0, sentenceEnd + 1 ).trim() : firstLine;

	}

	/** Returns operation details excluding the summary sentence/line. */
	public static String operationDescription(
		CtElement element
	) {

		String description = description( element );
		String summary = summary( element );

		if (description == null || description.isBlank() || summary == null || summary.isBlank()) {
			return null;

		}

		if (! description.startsWith( summary )) {
			return description;

		}

		String result = description.substring( summary.length() ).trim();

		return result.isBlank() ? null : result;

	}

	private static String normalize(
		String content
	) {

		if (content == null || content.isBlank()) {
			return null;

		}

		String result = Arrays
			.stream( content.replace( "<p>", "\n" ).replace( "</p>", "" ).split( "\\R" ) )
			.map( String::trim )
			.map( line -> line.startsWith( "*" ) ? line.substring( 1 ).trim() : line )
			.takeWhile( line -> ! line.startsWith( "@" ) )
			.collect( Collectors.joining( "\n" ) )
			.trim();

		return result.isBlank() ? null : result;

	}

}
