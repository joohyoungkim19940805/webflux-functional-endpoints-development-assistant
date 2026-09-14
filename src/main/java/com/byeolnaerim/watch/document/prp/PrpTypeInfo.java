package com.byeolnaerim.watch.document.prp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.byeolnaerim.watch.document.common.TypeInfo;
import spoon.reflect.reference.CtTypeReference;

public class PrpTypeInfo implements TypeInfo<PrpTypeInfo> {
    private String name;
    private Class<?> type;
    private CtTypeReference<?> typeRef;
    private List<PrpTypeInfo> genericTypes = new ArrayList<>();
    private Map<String, PrpTypeInfo> fields = new LinkedHashMap<>();
    private String description;
    private Object example;

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }
    @Override public Class<?> getType() { return type; }
    @Override public void setType(Class<?> type) { this.type = type; }
    @Override public CtTypeReference<?> getTypeRef() { return typeRef; }
    @Override public void setTypeRef(CtTypeReference<?> typeRef) { this.typeRef = typeRef; }
    @Override public List<PrpTypeInfo> getGenericTypes() { return genericTypes; }
    @Override public void setGenericTypes(List<PrpTypeInfo> genericTypes) { this.genericTypes = genericTypes; }
    @Override public Map<String, PrpTypeInfo> getFields() { return fields; }
    public void setFields(Map<String, PrpTypeInfo> fields) { this.fields = fields; }
    @Override public void addField(String name, PrpTypeInfo info) { fields.put(name, info); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Object getExample() { return example; }
    @Override public void setExample(Object example) { this.example = example; }
}
