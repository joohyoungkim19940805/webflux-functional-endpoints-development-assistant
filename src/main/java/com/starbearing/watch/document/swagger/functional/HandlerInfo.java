package com.starbearing.watch.document.swagger.functional;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import spoon.reflect.reference.CtTypeReference;

public class HandlerInfo {

	// Request Body 관련 정보: request body로 매핑될 클래스 이름 -> 해당 클래스의 필드 정보를 맵핑한 Map
	private Map<String, Info> requestBodyInfo = new HashMap<>();

	// Query String 정보: 쿼리 파라미터 이름 -> 타입 및 디폴트 값, nullable 여부 등
	private Map<String, Info> queryStringInfo = new HashMap<>();

	// Path Variable 정보: path variable 이름 -> 타입 등 정보
	private Map<String, Info> pathVariableInfo = new HashMap<>();

	// Response Body 정보: response에 매핑될 타입이나 필드 정보
	private Map<String, Info> responseBodyInfo = new HashMap<>();

	// produces
	private List<String> contentMediaTypes = new ArrayList<>();

	private Map<String, Info> headerParams = new HashMap<>();

	private Map<String, Info> cookieParams = new HashMap<>();

	// ===== Getter / Setter =====

	public Map<String, Info> getRequestBodyInfo() { return requestBodyInfo; }

	public void setRequestBodyInfo(
		Map<String, Info> requestBodyInfo
	) { this.requestBodyInfo = requestBodyInfo; }

	public Map<String, Info> getQueryStringInfo() { return queryStringInfo; }

	public void setQueryStringInfo(
		Map<String, Info> queryStringInfo
	) { this.queryStringInfo = queryStringInfo; }

	public Map<String, Info> getPathVariableInfo() { return pathVariableInfo; }

	public void setPathVariableInfo(
		Map<String, Info> pathVariableInfo
	) { this.pathVariableInfo = pathVariableInfo; }

	public Map<String, Info> getResponseBodyInfo() { return responseBodyInfo; }

	public void setResponseBodyInfo(
		Map<String, Info> responseBodyInfo
	) { this.responseBodyInfo = responseBodyInfo; }

	public List<String> getContentMediaTypes() { return contentMediaTypes; }

	public void setContentMediaTypes(
		List<String> contentMediaTypes
	) { this.contentMediaTypes = contentMediaTypes; }

	public Map<String, Info> getHeaderParams() { return headerParams; }

	public void setHeaderParams(
		Map<String, Info> headerParams
	) { this.headerParams = headerParams; }

	public Map<String, Info> getCookieParams() { return cookieParams; }

	public void setCookieParams(
		Map<String, Info> cookieParams
	) { this.cookieParams = cookieParams; }

	@Override
	public String toString() {

		return "HandlerInfo{" + "requestBodyInfo=" + requestBodyInfo + ", queryStringInfo=" + queryStringInfo + ", pathVariableInfo=" + pathVariableInfo + ", responseBodyInfo=" + responseBodyInfo + ", contentMediaTypes=" + contentMediaTypes + ", headerParams=" + headerParams + ", cookieParams=" + cookieParams + '}';

	}

	public static class Info {

		private String name;

		private String defaultValue;

		private Boolean required;

		private Boolean nullable;

		private Class<?> type;

		private CtTypeReference<?> typeRef;
		
		private List<Info> genericTypes = new ArrayList<>();

		private Map<String, Info> fields = new HashMap<>();

		private LayerPosition position;

		private String description;

		private Object example;

		public void addField(
			String name, Info info
		) {

			fields.put( name, info );

		}

		// ===== Getter / Setter =====

		public String getName() { return name; }

		public void setName(
			String name
		) { this.name = name; }

		public String getDefaultValue() { return defaultValue; }

		public void setDefaultValue(
			String defaultValue
		) { this.defaultValue = defaultValue; }

		public Boolean getRequired() { return required; }

		public void setRequired(
			Boolean required
		) { this.required = required; }

		public Boolean getNullable() { return nullable; }

		public void setNullable(
			Boolean nullable
		) { this.nullable = nullable; }

		public Class<?> getType() { return type; }

		public void setType(
			Class<?> type
		) { this.type = type; }

		public CtTypeReference<?> getTypeRef() { return typeRef; }

		public void setTypeRef(
			CtTypeReference<?> typeRef
		) { this.typeRef = typeRef; }

		
		public List<Info> getGenericTypes() { return genericTypes; }

		public void setGenericTypes(
			List<Info> genericTypes
		) { this.genericTypes = genericTypes; }

		public Map<String, Info> getFields() { return fields; }

		public void setFields(
			Map<String, Info> fields
		) { this.fields = fields; }

		public LayerPosition getPosition() { return position; }

		public void setPosition(
			LayerPosition position
		) { this.position = position; }

		public String getDescription() { return description; }

		public void setDescription(
			String description
		) { this.description = description; }

		public Object getExample() { return example; }

		public void setExample(
			Object example
		) { this.example = example; }

		@Override
		public String toString() {

			return "Info{" + "name='" + name + '\'' + ", defaultValue='" + defaultValue + '\'' + ", required=" + required + ", nullable=" + nullable + ", type=" + type + ", genericTypes=" + genericTypes + ", fields=" + fields + ", position=" + position + ", description='" + description + '\'' + ", example=" + example + '}';

		}

	}

	public static enum LayerPosition {
		REQUEST_BODY, REQUEST_PATH, REQUEST_STRING, RESPONSE_BODY, HEADER, COOKIE, FIELDS, GENERIC
	}

}
