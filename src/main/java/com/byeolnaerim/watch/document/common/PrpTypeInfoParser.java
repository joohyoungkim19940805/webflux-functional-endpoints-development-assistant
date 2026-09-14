package com.byeolnaerim.watch.document.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.byeolnaerim.watch.document.prp.PrpTypeInfo;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtType;

public final class PrpTypeInfoParser extends TypeInfoParser<PrpTypeInfo> {
    public PrpTypeInfoParser() { super(); }
    public PrpTypeInfoParser(Map<String, CtType<?>> externalTypes) { super(externalTypes); }

    @Override protected PrpTypeInfo createInfo() { return new PrpTypeInfo(); }

    @Override protected PrpTypeInfo copyInfo(PrpTypeInfo source) {
        PrpTypeInfo copy = new PrpTypeInfo();
        copy.setName(source.getName());
        copy.setType(source.getType());
        copy.setTypeRef(source.getTypeRef());
        copy.setDescription(source.getDescription());
        copy.setExample(source.getExample());
        ArrayList<PrpTypeInfo> genericTypes = new ArrayList<>(source.getGenericTypes().size());
        for (PrpTypeInfo genericType : source.getGenericTypes()) genericTypes.add(copyInfo(genericType));
        copy.setGenericTypes(genericTypes);
        LinkedHashMap<String, PrpTypeInfo> fields = new LinkedHashMap<>();
        source.getFields().forEach((name, info) -> fields.put(name, copyInfo(info)));
        copy.setFields(fields);
        return copy;
    }

    @Override protected void applyDocumentation(CtElement element, PrpTypeInfo info) {
        info.setDescription(SourceDocumentationUtil.description(element));
    }
}
