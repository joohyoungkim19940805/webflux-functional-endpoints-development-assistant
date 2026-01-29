package com.byeolnaerim.watch.document.swagger.functional;


import java.util.ArrayList;
import java.util.List;
import spoon.reflect.code.CtExpression;


public class RouteInfo {

	private String httpMethod;

	private String url;

	private String endpoint;

	// consumes
	private List<String> acceptMediaTypes = new ArrayList<>();

	// 새로운 필드 추가: handler나 람다 정보
	private CtExpression<?> handlerInfoCtExpression;

	private HandlerInfo handlerInfo;

	private List<String> securitySchemes = new ArrayList<>();

	// 새로운 필드 추가
	private String parentGroup; // 상위 그룹 이름 (예: "oauth2")

	private String childGroup; // 하위 그룹 이름 (예: "oauth2-search")

	public String getHttpMethod() { return httpMethod; }

	public void setHttpMethod(
		String httpMethod
	) { this.httpMethod = httpMethod; }

	public String getUrl() { return url; }

	public void setUrl(
		String url
	) { this.url = url; }

	public String getEndpoint() { return endpoint; }

	public void setEndpoint(
		String endpoint
	) { this.endpoint = endpoint; }

	public List<String> getAcceptMediaTypes() { return acceptMediaTypes; }

	public void setAcceptMediaTypes(
		List<String> acceptMediaTypes
	) { this.acceptMediaTypes = acceptMediaTypes; }

	public CtExpression<?> getHandlerInfoCtExpression() { return handlerInfoCtExpression; }

	public void setHandlerInfoCtExpression(
		CtExpression<?> handlerInfoCtExpression
	) { this.handlerInfoCtExpression = handlerInfoCtExpression; }

	public HandlerInfo getHandlerInfo() { return handlerInfo; }

	public void setHandlerInfo(
		HandlerInfo handlerInfo
	) { this.handlerInfo = handlerInfo; }

	public List<String> getSecuritySchemes() { return securitySchemes; }

	public void setSecuritySchemes(
		List<String> securitySchemes
	) { this.securitySchemes = securitySchemes; }

	public String getParentGroup() { return parentGroup; }

	public void setParentGroup(
		String parentGroup
	) { this.parentGroup = parentGroup; }

	public String getChildGroup() { return childGroup; }

	public void setChildGroup(
		String childGroup
	) { this.childGroup = childGroup; }
	@Override
	public String toString() {

		return "RouteInfo{" + "httpMethod='" + httpMethod + '\'' + ", url='" + url + '\'' + ", endpoint='" + endpoint + '\'' + ", acceptMediaTypes=" + acceptMediaTypes + ", parentGroup='" + parentGroup + '\'' + ", childGroup='" + childGroup + '\'' + ", handlerInfo=" + handlerInfo + '}';

	}

}
