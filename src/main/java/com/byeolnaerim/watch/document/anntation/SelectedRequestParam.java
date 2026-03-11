package com.byeolnaerim.watch.document.anntation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Overrides request-parameter metadata used during documentation parsing.
 * <p>This annotation can be applied to a local variable or method parameter
 * to explicitly control the documented query parameter name, default value,
 * required flag, nullable flag, and exposed type.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
	ElementType.LOCAL_VARIABLE, ElementType.PARAMETER
})
public @interface SelectedRequestParam {

	/**
	 * Returns the documented parameter name override.
	 *
	 * @return the overridden parameter name, or an empty string to keep the inferred name
	 */
	String key() default "";

	/**
	 * Returns the documented default value.
	 *
	 * @return the default value, or an empty string if no default value is specified
	 */
	String defaultValue() default "";

	/**
	 * Returns whether the parameter should be documented as required.
	 *
	 * @return {@code true} if the parameter is required
	 */
	boolean required() default true;

	/**
	 * Returns whether the parameter value may be {@code null}.
	 *
	 * @return {@code true} if the parameter is nullable
	 */
	boolean nullable() default false;

	/**
	 * Returns the documented parameter type override.
	 *
	 * @return the explicit parameter type, or {@link Void} when no override is specified
	 */
	Class<?> type() default Void.class;

}
