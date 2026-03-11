package com.byeolnaerim.watch.document.anntation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Returns the documented parameter type override.
 *
 * @return the explicit parameter type, or {@link Void} when no override is specified
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
	ElementType.LOCAL_VARIABLE, ElementType.PARAMETER
})
public @interface SelectedRequestPath {

	/**
	 * Returns the documented path-variable name override.
	 *
	 * @return the overridden variable name, or an empty string to keep the inferred name
	 */
	String key() default "";

	/**
	 * Returns the documented default value.
	 *
	 * @return the default value, or an empty string if no default value is specified
	 */
	String defaultValue() default "";

	/**
	 * Returns whether the path variable should be documented as required.
	 *
	 * @return {@code true} if the path variable is required
	 */
	boolean required() default true;

	/**
	 * Returns whether the path-variable value may be {@code null}.
	 *
	 * @return {@code true} if the path-variable value is nullable
	 */
	boolean nullable() default false;

	/**
	 * Returns the documented path-variable type override.
	 *
	 * @return the explicit type, or {@link Void} when no override is specified
	 */
	Class<?> type() default Void.class;

}
