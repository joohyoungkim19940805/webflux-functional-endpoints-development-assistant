package com.starbearing.watch.document.anntation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({
	ElementType.LOCAL_VARIABLE, ElementType.PARAMETER, ElementType.METHOD
})
public @interface SelectedResponseBody {

	boolean nullable() default false;

	Class<?> type() default Void.class;
}
