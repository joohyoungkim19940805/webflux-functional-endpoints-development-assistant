package com.byeolnaerim.watch.document.asyncapi.rsoket;


/**
 * Metadata for a parsed RSocket route definition.
 * <p>This class stores controller identity, handler method name, resolved destination,
 * return publisher type, and parsed handler metadata.</p>
 */
public class RsoketRouteInfo {

	private String controller;

	private String controllerSimpleName;

	private String method;

	private String destination;

	/** return publisher type: Mono / Flux */
	private String publisher;

	private RsoketHandlerInfo handlerInfo;

	public String getController() { return controller; }

	public void setController(
		String controller
	) { this.controller = controller; }

	public String getControllerSimpleName() { return controllerSimpleName; }

	public void setControllerSimpleName(
		String controllerSimpleName
	) { this.controllerSimpleName = controllerSimpleName; }

	public String getMethod() { return method; }

	public void setMethod(
		String method
	) { this.method = method; }

	public String getDestination() { return destination; }

	public void setDestination(
		String destination
	) { this.destination = destination; }

	/**
	 * Returns the reactive publisher type of the handler method.
	 * <p>Typical values are {@code Mono} and {@code Flux}.</p>
	 *
	 * @return the publisher type
	 */
	public String getPublisher() { return publisher; }

	/**
	 * Sets the reactive publisher type of the handler method.
	 *
	 * @param publisher
	 *            the publisher type
	 */
	public void setPublisher(
		String publisher
	) { this.publisher = publisher; }

	public RsoketHandlerInfo getHandlerInfo() { return handlerInfo; }

	public void setHandlerInfo(
		RsoketHandlerInfo handlerInfo
	) { this.handlerInfo = handlerInfo; }

	@Override
	public String toString() {

		return "RsoketRouteInfo{" + "controller='" + controller + '\'' + ", controllerSimpleName='" + controllerSimpleName + '\'' + ", method='" + method + '\'' + ", destination='" + destination + '\'' + ", publisher='" + publisher + '\'' + ", handlerInfo=" + handlerInfo + '}';

	}

}
