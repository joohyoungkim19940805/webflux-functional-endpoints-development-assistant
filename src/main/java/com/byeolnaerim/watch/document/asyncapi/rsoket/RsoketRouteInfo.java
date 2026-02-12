package com.byeolnaerim.watch.document.asyncapi.rsoket;

public class RsoketRouteInfo {

	private String controller;

	private String controllerSimpleName;

	private String method;

	private String destination;

	/** return publisher type: Mono / Flux */
	private String publisher;

	private RsoketHandlerInfo handlerInfo;

	public String getController() { return controller; }
	public void setController(String controller) { this.controller = controller; }

	public String getControllerSimpleName() { return controllerSimpleName; }
	public void setControllerSimpleName(String controllerSimpleName) { this.controllerSimpleName = controllerSimpleName; }

	public String getMethod() { return method; }
	public void setMethod(String method) { this.method = method; }

	public String getDestination() { return destination; }
	public void setDestination(String destination) { this.destination = destination; }

	public String getPublisher() { return publisher; }
	public void setPublisher(String publisher) { this.publisher = publisher; }

	public RsoketHandlerInfo getHandlerInfo() { return handlerInfo; }
	public void setHandlerInfo(RsoketHandlerInfo handlerInfo) { this.handlerInfo = handlerInfo; }

	@Override
	public String toString() {
		return "RsoketRouteInfo{" +
			"controller='" + controller + '\'' +
			", controllerSimpleName='" + controllerSimpleName + '\'' +
			", method='" + method + '\'' +
			", destination='" + destination + '\'' +
			", publisher='" + publisher + '\'' +
			", handlerInfo=" + handlerInfo +
			'}';
	}
}
