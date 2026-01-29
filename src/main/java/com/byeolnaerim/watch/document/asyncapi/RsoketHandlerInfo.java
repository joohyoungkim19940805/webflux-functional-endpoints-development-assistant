package com.byeolnaerim.watch.document.asyncapi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RSocket(@MessageMapping) 핸들러의 입/출력 스키마 정보.
 * - payloadInfo: @DestinationVariable 이 아닌 파라미터(일반적으로 @Payload)
 * - destinationVariableInfo: @DestinationVariable 파라미터
 * - responseBodyInfo: 응답 바디(가능하면 커스텀 @ResponseBody 우선)
 */
public class RsoketHandlerInfo {

	private Map<String, RsoketTypeInfo> payloadInfo = new LinkedHashMap<>();

	private Map<String, RsoketTypeInfo> destinationVariableInfo = new LinkedHashMap<>();

	/** 응답은 보통 1개지만, 기존 구조 호환을 위해 map 유지 */
	private Map<String, RsoketTypeInfo> responseBodyInfo = new LinkedHashMap<>();

	public Map<String, RsoketTypeInfo> getPayloadInfo() { return payloadInfo; }
	public void setPayloadInfo(Map<String, RsoketTypeInfo> payloadInfo) { this.payloadInfo = payloadInfo; }

	public Map<String, RsoketTypeInfo> getDestinationVariableInfo() { return destinationVariableInfo; }
	public void setDestinationVariableInfo(Map<String, RsoketTypeInfo> destinationVariableInfo) { this.destinationVariableInfo = destinationVariableInfo; }

	public Map<String, RsoketTypeInfo> getResponseBodyInfo() { return responseBodyInfo; }
	public void setResponseBodyInfo(Map<String, RsoketTypeInfo> responseBodyInfo) { this.responseBodyInfo = responseBodyInfo; }

	@Override
	public String toString() {
		return "RsoketHandlerInfo{" +
			"payloadInfo=" + payloadInfo +
			", destinationVariableInfo=" + destinationVariableInfo +
			", responseBodyInfo=" + responseBodyInfo +
			'}';
	}
}
