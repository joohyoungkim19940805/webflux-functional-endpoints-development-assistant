package com.byeolnaerim.watch.document.asyncapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import spoon.reflect.reference.CtTypeReference;

/**
 * RSocket 문서/코드제너레이터용 타입 정보.
 * - REST(HandlerInfo)와 분리해서 rsoket 모듈이 swagger 모듈에 의존하지 않도록 한다.
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

	public void addField(String name, RsoketTypeInfo info) {
		fields.put(name, info);
	}

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public Boolean getNullable() { return nullable; }
	public void setNullable(Boolean nullable) { this.nullable = nullable; }

	public Class<?> getType() { return type; }
	public void setType(Class<?> type) { this.type = type; }

	public CtTypeReference<?> getTypeRef() { return typeRef; }
	public void setTypeRef(CtTypeReference<?> typeRef) { this.typeRef = typeRef; }

	public List<RsoketTypeInfo> getGenericTypes() { return genericTypes; }
	public void setGenericTypes(List<RsoketTypeInfo> genericTypes) { this.genericTypes = genericTypes; }

	public Map<String, RsoketTypeInfo> getFields() { return fields; }
	public void setFields(Map<String, RsoketTypeInfo> fields) { this.fields = fields; }

	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	public Object getExample() { return example; }
	public void setExample(Object example) { this.example = example; }

	@Override
	public String toString() {
		return "RsoketTypeInfo{" +
			"name='" + name + '\'' +
			", nullable=" + nullable +
			", type=" + type +
			", genericTypes=" + genericTypes +
			", fields=" + fields +
			", description='" + description + '\'' +
			", example=" + example +
			'}';
	}
}
