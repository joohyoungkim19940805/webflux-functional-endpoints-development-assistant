package com.byeolnaerim.watch.document.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Overrides request-body metadata used during documentation parsing.
 * <p>This annotation is intended for handler methods whose request body is parsed
 * through a custom helper and therefore cannot be inferred reliably from direct
 * {@code ServerRequest.bodyToMono(...)} or {@code bodyToFlux(...)} calls.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SelectedRequestBody {

	/**
	 * Returns the request-body type that should be exposed in the generated document.
	 *
	 * @return the explicit request-body type
	 */
	Class<?> value();

}
