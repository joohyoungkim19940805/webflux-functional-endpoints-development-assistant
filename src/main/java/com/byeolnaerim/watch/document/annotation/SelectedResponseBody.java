package com.byeolnaerim.watch.document.annotation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Overrides response-body metadata used during documentation parsing.
 * <p>This annotation can be applied to a local variable, method parameter, or method
 * in order to explicitly control the documented response type and nullability.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
	ElementType.LOCAL_VARIABLE, ElementType.PARAMETER, ElementType.METHOD
})
public @interface SelectedResponseBody {

	/**
	 * Returns whether the documented response body may be {@code null}.
	 *
	 * @return {@code true} if the response body is nullable
	 */
	boolean nullable() default false;

	/**
	 * Returns the documented response-body type override.
	 *
	 * @return the explicit response type, or {@link Void} when no override is specified
	 */
	Class<?> type() default Void.class;

	/**
	 * Returns the invocation argument index whose type should be used as the response payload.
	 * This is useful for reusable response helpers such as {@code json(result)}.
	 *
	 * @return zero-based argument index, or {@code -1} when no argument should be inferred
	 */
	int parameterIndex() default -1;

	/**
	 * Returns an optional response wrapper type applied around the inferred payload.
	 *
	 * @return wrapper type, or {@link Void} when the payload itself is the documented response
	 */
	Class<?> wrapperType() default Void.class;

}
