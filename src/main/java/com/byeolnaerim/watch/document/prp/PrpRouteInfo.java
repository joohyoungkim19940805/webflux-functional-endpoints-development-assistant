package com.byeolnaerim.watch.document.prp;

public final class PrpRouteInfo {
    private String route;
    private String interaction;
    private String controller;
    private String controllerSimpleName;
    private String method;
    private String description;
    private PrpTypeInfo request;
    private PrpTypeInfo response;

    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getInteraction() { return interaction; }
    public void setInteraction(String interaction) { this.interaction = interaction; }
    public String getController() { return controller; }
    public void setController(String controller) { this.controller = controller; }
    public String getControllerSimpleName() { return controllerSimpleName; }
    public void setControllerSimpleName(String controllerSimpleName) { this.controllerSimpleName = controllerSimpleName; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public PrpTypeInfo getRequest() { return request; }
    public void setRequest(PrpTypeInfo request) { this.request = request; }
    public PrpTypeInfo getResponse() { return response; }
    public void setResponse(PrpTypeInfo response) { this.response = response; }
}
