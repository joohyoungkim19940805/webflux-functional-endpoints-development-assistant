package com.byeolnaerim.watch.document.common;


import java.util.List;
import java.util.Map;
import spoon.reflect.reference.CtTypeReference;


/**
 * Common contract for parsed type metadata used by Swagger and AsyncAPI parsers.
 *
 * @param <T>
 *            concrete metadata type
 */
public interface TypeInfo<T extends TypeInfo<T>> {

	String getName();

	void setName(
		String name
	);

	Class<?> getType();

	void setType(
		Class<?> type
	);

	CtTypeReference<?> getTypeRef();

	void setTypeRef(
		CtTypeReference<?> typeRef
	);

	List<T> getGenericTypes();

	void setGenericTypes(
		List<T> genericTypes
	);

	Map<String, T> getFields();

	void addField(
		String name, T info
	);

	void setExample(
		Object example
	);

}
