package com.byeolnaerim.watch.document.asyncapi.rsoket;


import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Aggregated request and response schema metadata for a parsed RSocket handler.
 * <p>This class stores payload parameters, destination variables, and response-body metadata
 * in separate maps so that generators can render them differently.</p>
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

	/**
	 * Returns payload parameter metadata excluding destination variables.
	 *
	 * @return payload parameter metadata
	 */
	public Map<String, RsoketTypeInfo> getPayloadInfo() { return payloadInfo; }

	/**
	 * Replaces payload parameter metadata.
	 *
	 * @param payloadInfo
	 *            the payload metadata
	 */
	public void setPayloadInfo(
		Map<String, RsoketTypeInfo> payloadInfo
	) { this.payloadInfo = payloadInfo; }

	/**
	 * Returns destination-variable metadata.
	 *
	 * @return destination-variable metadata
	 */
	public Map<String, RsoketTypeInfo> getDestinationVariableInfo() { return destinationVariableInfo; }

	/**
	 * Replaces destination-variable metadata.
	 *
	 * @param destinationVariableInfo
	 *            the destination-variable metadata
	 */
	public void setDestinationVariableInfo(
		Map<String, RsoketTypeInfo> destinationVariableInfo
	) { this.destinationVariableInfo = destinationVariableInfo; }

	/**
	 * Returns response-body metadata.
	 * <p>A map is kept for structural consistency even though a single response type is typical.</p>
	 *
	 * @return response-body metadata
	 */
	public Map<String, RsoketTypeInfo> getResponseBodyInfo() { return responseBodyInfo; }

	/**
	 * Replaces response-body metadata.
	 *
	 * @param responseBodyInfo
	 *            the response-body metadata
	 */
	public void setResponseBodyInfo(
		Map<String, RsoketTypeInfo> responseBodyInfo
	) { this.responseBodyInfo = responseBodyInfo; }

	@Override
	public String toString() {

		return "RsoketHandlerInfo{" + "payloadInfo=" + payloadInfo + ", destinationVariableInfo=" + destinationVariableInfo + ", responseBodyInfo=" + responseBodyInfo + '}';

	}

}
