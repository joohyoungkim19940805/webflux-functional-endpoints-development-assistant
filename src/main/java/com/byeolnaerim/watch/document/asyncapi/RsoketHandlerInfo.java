package com.byeolnaerim.watch.document.asyncapi;


import java.util.LinkedHashMap;
import java.util.Map;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo;


/**
 * RSocket(@MessageMapping) 핸들러의 입/출력 스키마 정보를 담는 DTO.
 * - payloadInfo: @DestinationVariable 이 아닌 파라미터(일반적으로 @Payload)
 * - destinationVariableInfo: @DestinationVariable 파라미터
 * - responseBodyInfo: 응답 바디(가능하면 @ResponseBody 우선)
 */
public class RsoketHandlerInfo {

	private Map<String, HandlerInfo.Info> payloadInfo = new LinkedHashMap<>();

	private Map<String, HandlerInfo.Info> destinationVariableInfo = new LinkedHashMap<>();

	private Map<String, HandlerInfo.Info> responseBodyInfo = new LinkedHashMap<>();

	public Map<String, HandlerInfo.Info> getPayloadInfo() { return payloadInfo; }

	public void setPayloadInfo(
		Map<String, HandlerInfo.Info> payloadInfo
	) { this.payloadInfo = payloadInfo; }

	public Map<String, HandlerInfo.Info> getDestinationVariableInfo() { return destinationVariableInfo; }

	public void setDestinationVariableInfo(
		Map<String, HandlerInfo.Info> destinationVariableInfo
	) { this.destinationVariableInfo = destinationVariableInfo; }

	public Map<String, HandlerInfo.Info> getResponseBodyInfo() { return responseBodyInfo; }

	public void setResponseBodyInfo(
		Map<String, HandlerInfo.Info> responseBodyInfo
	) { this.responseBodyInfo = responseBodyInfo; }

	@Override
	public String toString() {

		return "RsoketHandlerInfo{" + "payloadInfo=" + payloadInfo + ", destinationVariableInfo=" + destinationVariableInfo + ", responseBodyInfo=" + responseBodyInfo + '}';

	}

}
