package com.byeolnaerim.watch.swagger;


import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import spoon.reflect.code.CtExpression;


@Getter
@Setter
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

	@Override
	public String toString() {

		return "RouteInfo{" + "httpMethod='" + httpMethod + '\'' + ", url='" + url + '\'' + ", endpoint='" + endpoint + '\'' + ", acceptMediaTypes=" + acceptMediaTypes + ", parentGroup='" + parentGroup + '\'' + ", childGroup='" + childGroup + '\'' + ", handlerInfo=" + handlerInfo + '}';

	}

}
