package com.byeolnaerim.watch.swagger.anntation;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Retention(RetentionPolicy.RUNTIME)
@Target({
	ElementType.LOCAL_VARIABLE, ElementType.PARAMETER
})
public @interface RequestParam {

	String key() default "";

	String defaultValue() default "";

	boolean required() default true;

	boolean nullable() default false;

	Class<?> type() default Void.class;

}
