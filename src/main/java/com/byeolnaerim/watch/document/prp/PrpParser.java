package com.byeolnaerim.watch.document.prp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.byeolnaerim.watch.document.common.PrpTypeInfoParser;
import com.byeolnaerim.watch.document.common.SourceDocumentationUtil;
import spoon.Launcher;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

public final class PrpParser {
    private final PrpTypeInfoParser typeInfoParser;

    public PrpParser() { this(Map.of()); }
    public PrpParser(Map<String, CtType<?>> externalTypes) {
        this.typeInfoParser = new PrpTypeInfoParser(externalTypes == null ? Map.of() : externalTypes);
    }

    public List<PrpRouteInfo> extractPrpRoutes(String watchDirectory) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(watchDirectory);
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.buildModel();
        return extractPrpRoutes(launcher.getModel().getAllTypes());
    }

    public List<PrpRouteInfo> extractPrpRoutes(Iterable<CtType<?>> allTypes) {
        List<PrpRouteInfo> output = new ArrayList<>();
        for (CtType<?> type : allTypes) {
            for (CtMethod<?> method : type.getMethods()) {
                for (CtAnnotation<?> annotation : method.getAnnotations()) {
                    if (annotation.getAnnotationType() == null) continue;
                    String annotationName = annotation.getAnnotationType().getSimpleName();
                    if ("PrpRoute".equals(annotationName)) {
                        addRoute(output, type, method, annotation);
                    } else if ("PrpRoutes".equals(annotationName)) {
                        addContainerRoutes(output, type, method, annotation);
                    }
                }
            }
        }
        return output;
    }

    private void addContainerRoutes(List<PrpRouteInfo> output, CtType<?> type, CtMethod<?> method, CtAnnotation<?> container) {
        CtExpression<?> value = container.getValue("value");
        if (value instanceof CtAnnotation<?> nested) {
            addRoute(output, type, method, nested);
            return;
        }
        if (value instanceof CtNewArray<?> array) {
            for (CtExpression<?> expression : array.getElements()) {
                if (expression instanceof CtAnnotation<?> nested) addRoute(output, type, method, nested);
            }
        }
    }

    private void addRoute(List<PrpRouteInfo> output, CtType<?> type, CtMethod<?> method, CtAnnotation<?> annotation) {
        String route = stringValue(annotation, "value");
        if (route == null || route.isBlank()) return;
        String interaction = interactionValue(annotation);
        validateInteraction(interaction, method);
        PrpRouteInfo info = new PrpRouteInfo();
        info.setRoute(route.trim());
        info.setInteraction(interaction);
        info.setController(type.getQualifiedName());
        info.setControllerSimpleName(type.getSimpleName());
        info.setMethod(method.getSimpleName());
        info.setDescription(SourceDocumentationUtil.description(method));
        info.setRequest(parseRequest(method, interaction));
        info.setResponse(parseResponse(method, interaction));
        output.add(info);
    }

    private static void validateInteraction(String interaction, CtMethod<?> method) {
        if (!List.of("REQUEST_RESPONSE", "FIRE_AND_FORGET", "REQUEST_STREAM", "REQUEST_CHANNEL", "DATAGRAM").contains(interaction)) {
            throw invalid(method, "unsupported PRP interaction: " + interaction);
        }
    }

    private PrpTypeInfo parseRequest(CtMethod<?> method, String interaction) {
        List<CtParameter<?>> payloads = new ArrayList<>();
        for (CtParameter<?> parameter : method.getParameters()) {
            CtTypeReference<?> type = parameter.getType();
            String simple = type == null ? "" : type.getSimpleName();
            String qualified = type == null ? "" : type.getQualifiedName();
            if ("PrpContext".equals(simple) || "com.byeolnaerim.prp.spring.PrpContext".equals(qualified)) continue;
            payloads.add(parameter);
        }
        if (payloads.size() != 1) {
            throw invalid(method, "must declare exactly one application payload parameter plus optional PrpContext");
        }
        CtParameter<?> payload = payloads.get(0);
        CtTypeReference<?> type = payload.getType();
        if ("REQUEST_CHANNEL".equals(interaction)) {
            if (!isPublisherType(type)) throw invalid(method, "REQUEST_CHANNEL payload must be Flux or Publisher");
            type = unwrapFirstGeneric(type);
        } else if ("DATAGRAM".equals(interaction)) {
            if (!isDatagramType(type)) throw invalid(method, "DATAGRAM payload must be byte[], ByteBuffer, or RoutedDatagram");
        } else if (isPublisherType(type)) {
            throw invalid(method, interaction + " payload must be a single value, not a Publisher");
        }
        PrpTypeInfo info = typeInfoParser.buildInfo(type);
        info.setName(payload.getSimpleName());
        return info;
    }

    private PrpTypeInfo parseResponse(CtMethod<?> method, String interaction) {
        if ("FIRE_AND_FORGET".equals(interaction) || "DATAGRAM".equals(interaction)) {
            return voidInfo(method.getFactory().Type().VOID_PRIMITIVE);
        }
        CtTypeReference<?> type = method.getType();
        if (type == null) return voidInfo(method.getFactory().Type().VOID_PRIMITIVE);
        if ("REQUEST_RESPONSE".equals(interaction) && isVoidType(type)) {
            throw invalid(method, "REQUEST_RESPONSE must return a response value or asynchronous response value");
        }
        if ("REQUEST_STREAM".equals(interaction) || "REQUEST_CHANNEL".equals(interaction)) {
            if (!isStreamOutputType(type)) {
                throw invalid(method, interaction + " must return Flux, Publisher, Iterable, Collection, or an array");
            }
            type = unwrapStreamOutput(type);
        } else {
            type = unwrapAsyncSingle(type);
        }
        return typeInfoParser.buildInfo(type);
    }

    private PrpTypeInfo voidInfo(CtTypeReference<?> type) {
        return typeInfoParser.buildInfo(type);
    }

    private static CtTypeReference<?> unwrapAsyncSingle(CtTypeReference<?> type) {
        if (type == null) return null;
        String simple = type.getSimpleName();
        if (("Mono".equals(simple) || "CompletionStage".equals(simple) || "CompletableFuture".equals(simple) || "Publisher".equals(simple))
            && type.getActualTypeArguments() != null && !type.getActualTypeArguments().isEmpty()) {
            return type.getActualTypeArguments().get(0);
        }
        return type;
    }

    private static CtTypeReference<?> unwrapStreamOutput(CtTypeReference<?> type) {
        if (type == null) return null;
        if (type instanceof CtArrayTypeReference<?> array) return array.getComponentType();
        String simple = type.getSimpleName();
        if (("Flux".equals(simple) || "Mono".equals(simple) || "Publisher".equals(simple) || "Iterable".equals(simple)
            || "Collection".equals(simple) || "List".equals(simple) || "Set".equals(simple))
            && type.getActualTypeArguments() != null && !type.getActualTypeArguments().isEmpty()) {
            return type.getActualTypeArguments().get(0);
        }
        return type;
    }

    private static CtTypeReference<?> unwrapFirstGeneric(CtTypeReference<?> type) {
        if (type != null && type.getActualTypeArguments() != null && !type.getActualTypeArguments().isEmpty()) {
            return type.getActualTypeArguments().get(0);
        }
        return type;
    }

    private static boolean isPublisherType(CtTypeReference<?> type) {
        if (type == null) return false;
        String simple = type.getSimpleName();
        return "Flux".equals(simple) || "Mono".equals(simple) || "Publisher".equals(simple);
    }

    private static boolean isStreamOutputType(CtTypeReference<?> type) {
        if (type == null) return false;
        if (type instanceof CtArrayTypeReference<?>) return true;
        String simple = type.getSimpleName();
        return isPublisherType(type) || "Iterable".equals(simple) || "Collection".equals(simple)
            || "List".equals(simple) || "Set".equals(simple);
    }

    private static boolean isDatagramType(CtTypeReference<?> type) {
        if (type == null) return false;
        if (type instanceof CtArrayTypeReference<?> array) {
            CtTypeReference<?> component = array.getComponentType();
            return component != null && "byte".equals(component.getQualifiedName());
        }
        String simple = type.getSimpleName();
        return "ByteBuffer".equals(simple) || "RoutedDatagram".equals(simple);
    }

    private static boolean isVoidType(CtTypeReference<?> type) {
        if (type == null) return true;
        String q = type.getQualifiedName();
        String simple = type.getSimpleName();
        return "void".equals(q) || "java.lang.Void".equals(q) || "void".equals(simple) || "Void".equals(simple);
    }

    private static IllegalStateException invalid(CtMethod<?> method, String message) {
        return new IllegalStateException("Invalid @PrpRoute method " + method.getDeclaringType().getQualifiedName()
            + "#" + method.getSimpleName() + ": " + message);
    }

    private static String stringValue(CtAnnotation<?> annotation, String name) {
        var value = annotation.getValue(name);
        if (value instanceof CtLiteral<?> literal && literal.getValue() != null) return String.valueOf(literal.getValue());
        if (value == null) return null;
        String text = value.toString().trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) return text.substring(1, text.length() - 1);
        return text;
    }

    private static String interactionValue(CtAnnotation<?> annotation) {
        var value = annotation.getValue("interaction");
        if (value == null) return "REQUEST_RESPONSE";
        String text;
        if (value instanceof CtFieldRead<?> fieldRead && fieldRead.getVariable() != null) text = fieldRead.getVariable().getSimpleName();
        else text = value.toString();
        int dot = text.lastIndexOf('.');
        if (dot >= 0) text = text.substring(dot + 1);
        text = text.replaceAll("[^A-Za-z_]", "");
        return text.isBlank() ? "REQUEST_RESPONSE" : text;
    }
}
