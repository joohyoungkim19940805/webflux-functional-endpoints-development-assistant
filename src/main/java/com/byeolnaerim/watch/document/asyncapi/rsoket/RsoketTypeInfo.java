package com.byeolnaerim.watch.document.asyncapi.rsoket;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spoon.reflect.reference.CtTypeReference;


/**
 * Type metadata used by RSocket parsers, documentation generators, and code generators.
 * <p>This structure stores both runtime and source-level type information,
 * including the resolved Java class when available, the Spoon type reference,
 * generic argument metadata, nested field metadata, description, and example values.</p>
 */
public class RsoketTypeInfo {

	private String name;

	private Boolean nullable;

	/** 가능한 경우 실제 Class. classpath에 없으면 Object.class 로 떨어질 수 있음 */
	private Class<?> type;

	/** Spoon이 보는 타입 레퍼런스(= 소스 기반) */
	private CtTypeReference<?> typeRef;

	private List<RsoketTypeInfo> genericTypes = new ArrayList<>();

	private Map<String, RsoketTypeInfo> fields = new LinkedHashMap<>();

	private String description;

	private Object example;

	/**
	 * Adds nested field metadata to this type.
	 *
	 * @param name
	 *            the field name
	 * @param info
	 *            the nested field metadata
	 */
	public void addField(
		String name, RsoketTypeInfo info
	) {

		fields.put( name, info );

	}

	public String getName() { return name; }

	public void setName(
		String name
	) { this.name = name; }

	public Boolean getNullable() { return nullable; }

	public void setNullable(
		Boolean nullable
	) { this.nullable = nullable; }

	/**
	 * Returns the resolved runtime class when available.
	 * <p>When the class cannot be resolved from the current classpath,
	 * this may fall back to {@link Object}.</p>
	 *
	 * @return the resolved runtime type
	 */
	public Class<?> getType() { return type; }

	public void setType(
		Class<?> type
	) { this.type = type; }

	/**
	 * Returns the Spoon source-level type reference.
	 *
	 * @return the source-level type reference
	 */
	public CtTypeReference<?> getTypeRef() { return typeRef; }

	public void setTypeRef(
		CtTypeReference<?> typeRef
	) { this.typeRef = typeRef; }

	/**
	 * Returns generic argument metadata for this type.
	 *
	 * @return generic type metadata
	 */
	public List<RsoketTypeInfo> getGenericTypes() { return genericTypes; }

	public void setGenericTypes(
		List<RsoketTypeInfo> genericTypes
	) { this.genericTypes = genericTypes; }

	/**
	 * Returns nested field metadata for object-like types.
	 *
	 * @return nested field metadata
	 */
	public Map<String, RsoketTypeInfo> getFields() { return fields; }

	public void setFields(
		Map<String, RsoketTypeInfo> fields
	) { this.fields = fields; }

	/**
	 * Returns the documentation description associated with this type.
	 *
	 * @return the description
	 */
	public String getDescription() { return description; }

	public void setDescription(
		String description
	) { this.description = description; }

	/**
	 * Returns the example value associated with this type.
	 *
	 * @return the example value
	 */
	public Object getExample() { return example; }

	public void setExample(
		Object example
	) { this.example = example; }

	@Override
	public String toString() {

		return "RsoketTypeInfo{" + "name='" + name + '\'' + ", nullable=" + nullable + ", type=" + type + ", genericTypes=" + genericTypes + ", fields=" + fields + ", description='" + description + '\'' + ", example=" + example + '}';

	}

}
