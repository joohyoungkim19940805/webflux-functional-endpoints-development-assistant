package com.byeolnaerim.watch.document.common;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.byeolnaerim.watch.RouteUtil;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtRecord;
import spoon.reflect.declaration.CtRecordComponent;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;


/**
 * Base source-level type parser for Swagger and AsyncAPI generators.
 * <p>This class centralizes DTO/record/generic field parsing logic that was
 * previously duplicated in functional, MVC, and RSocket parsers. Concrete
 * subclasses only decide which metadata object to create and how parser-specific
 * markers such as field/generic position are represented.</p>
 *
 * @param <T>
 *            concrete metadata type
 */
public abstract class TypeInfoParser<T extends TypeInfo<T>> {

	private final Map<String, CtType<?>> externalTypes;

	private final Set<String> visitedTypes = new HashSet<>();

	private final Map<String, T> infoCache = new HashMap<>();

	protected TypeInfoParser() {

		this( Map.of() );

	}

	protected TypeInfoParser(
		Map<String, CtType<?>> externalTypes
	) {

		this.externalTypes = (externalTypes != null) ? externalTypes : Map.of();

	}

	public T buildInfo(
		CtTypeReference<?> rawTypeRef
	) {

		CtTypeReference<?> typeRef = resolveSourceBackedTypeReference( rawTypeRef, externalTypes );
		String cacheKey = buildCacheKey( typeRef );
		T cached = infoCache.get( cacheKey );

		if (cached != null) {
			return copyInfo( cached );

		}

		T info = createInfo();
		info.setTypeRef( typeRef );
		info.setType( loadClassFromTypeReference( typeRef ) );
		info.setName( typeRef != null ? typeRef.getSimpleName() : "Object" );
		initializeInfo( info );

		List<CtTypeReference<?>> actualTypeArgs = typeRef != null ? typeRef.getActualTypeArguments() : List.of();

		if (actualTypeArgs != null && ! actualTypeArgs.isEmpty()) {
			List<T> genericInfos = new ArrayList<>();

			for (CtTypeReference<?> argRef : actualTypeArgs) {
				T genericInfo = buildInfo( argRef );
				markGeneric( genericInfo );
				parseNestedTypeIfNeeded( genericInfo );
				genericInfos.add( genericInfo );

			}

			info.setGenericTypes( genericInfos );

		}

		if (shouldParseFields( typeRef, info.getType(), externalTypes ) && info.getFields().isEmpty()) {
			parseFields( typeRef, info );

		}

		infoCache.put( cacheKey, copyInfo( info ) );
		return info;

	}

	public void parseFields(
		CtTypeReference<?> rawTypeRef, T target
	) {

		if (rawTypeRef == null || target == null) { return; }

		CtTypeReference<?> typeRef = resolveSourceBackedTypeReference( rawTypeRef, externalTypes );

		if (typeRef == null || typeRef.getQualifiedName() == null) { return; }

		String qName = typeRef.getQualifiedName();

		if (! shouldParseFields( typeRef, target.getType(), externalTypes )) { return; }
		if (visitedTypes.contains( qName )) { return; }

		visitedTypes.add( qName );

		try {
			CtType<?> typeDecl = resolveSourceBackedType( typeRef, externalTypes );

			if (typeDecl == null) { return; }

			CtTypeReference<?> superClassRef = resolveSourceBackedTypeReference( typeDecl.getSuperclass(), externalTypes );

			if (superClassRef != null && superClassRef.getQualifiedName() != null && ! "java.lang.Object".equals( superClassRef.getQualifiedName() )) {
				parseFields( superClassRef, target );

			}

			if (typeDecl instanceof CtRecord record) {
				parseRecordComponents( typeRef, record, target );
				return;

			}

			for (CtField<?> field : typeDecl.getFields() != null ? typeDecl.getFields() : Collections.<CtField<?>>emptyList()) {
				parseField( typeRef, typeDecl, target, field );

			}

		} finally {
			visitedTypes.remove( qName );

		}

	}

	protected abstract T createInfo();

	protected abstract T copyInfo(
		T source
	);

	protected void initializeInfo(
		T info
	) {}

	protected void markGeneric(
		T info
	) {}

	protected void markField(
		T info
	) {}

	private void parseRecordComponents(
		CtTypeReference<?> ownerTypeRef, CtRecord record, T target
	) {

		for (CtRecordComponent component : record.getRecordComponents()) {

			if (component == null || component.getType() == null) { continue; }

			String fieldName = component.getSimpleName();
			CtTypeReference<?> fieldType = resolveGenericFieldType( ownerTypeRef, record, component.getType(), target );
			T fieldInfo = buildInfo( fieldType );
			fieldInfo.setName( fieldName );
			markField( fieldInfo );
			parseNestedTypeIfNeeded( fieldInfo );
			target.addField( fieldName, fieldInfo );

		}

	}

	private void parseField(
		CtTypeReference<?> ownerTypeRef, CtType<?> ownerTypeDecl, T target, CtField<?> field
	) {

		if (field == null || field.getType() == null || field.isStatic()) { return; }

		String fieldName = field.getSimpleName();
		CtTypeReference<?> fieldType = resolveGenericFieldType( ownerTypeRef, ownerTypeDecl, field.getType(), target );

		if (fieldType == null || fieldType.getQualifiedName() == null) { return; }

		if (isSelfReference( ownerTypeRef, fieldType )) {
			target.addField( fieldName, buildPartialInfo( field.getReference(), fieldType ) );
			return;

		}

		T fieldInfo = buildInfo( fieldType );
		fieldInfo.setName( fieldName );
		markField( fieldInfo );

		Class<?> fieldClass = fieldInfo.getType();

		if (fieldClass != null && fieldClass.isEnum()) {
			fieldInfo.setExample( RouteUtil.parserEnumValues( fieldClass ).toString() );

		} else {
			parseNestedTypeIfNeeded( fieldInfo );

		}

		target.addField( fieldName, fieldInfo );

	}

	private boolean isSelfReference(
		CtTypeReference<?> ownerTypeRef, CtTypeReference<?> fieldType
	) {

		if (ownerTypeRef == null || fieldType == null || ownerTypeRef.getQualifiedName() == null) { return false; }

		if (ownerTypeRef.getQualifiedName().equals( fieldType.getQualifiedName() )) { return true; }

		return fieldType
			.getActualTypeArguments()
			.stream()
			.anyMatch( e -> ownerTypeRef.getQualifiedName().equals( e.getQualifiedName() ) );

	}

	private T buildPartialInfo(
		CtFieldReference<?> field, CtTypeReference<?> fieldType
	) {

		T info = createInfo();
		info.setName( field != null ? field.getSimpleName() : null );
		info.setType( loadClassFromTypeReference( fieldType ) );
		info.setTypeRef( fieldType );
		markField( info );
		initializeInfo( info );
		return info;

	}

	private void parseNestedTypeIfNeeded(
		T info
	) {

		if (info == null) { return; }

		CtTypeReference<?> typeRef = resolveSourceBackedTypeReference( info.getTypeRef(), externalTypes );

		if (typeRef != null && info.getFields().isEmpty() && shouldParseFields( typeRef, info.getType(), externalTypes )) {
			parseFields( typeRef, info );

		}

		List<T> genericTypes = info.getGenericTypes();

		if (genericTypes == null || genericTypes.isEmpty()) { return; }

		for (T genericInfo : genericTypes) {

			if (genericInfo == null) { continue; }

			markGeneric( genericInfo );
			CtTypeReference<?> genericTypeRef = resolveSourceBackedTypeReference( genericInfo.getTypeRef(), externalTypes );

			if (genericTypeRef != null && genericInfo.getFields().isEmpty() && shouldParseFields( genericTypeRef, genericInfo.getType(), externalTypes )) {
				parseFields( genericTypeRef, genericInfo );

			}

		}

	}

	private CtTypeReference<?> resolveGenericFieldType(
		CtTypeReference<?> ownerTypeRef, CtType<?> ownerTypeDecl, CtTypeReference<?> fieldType, T ownerInfo
	) {

		if (! (fieldType instanceof CtTypeParameterReference typeParamRef)) { return resolveSourceBackedTypeReference( fieldType, externalTypes ); }

		String typeParamName = typeParamRef.getSimpleName();

		if (typeParamName == null || ownerTypeRef == null || ownerTypeDecl == null) { return fieldType; }

		List<CtTypeReference<?>> actualTypeArgs = ownerTypeRef.getActualTypeArguments();
		var formalTypeParams = ownerTypeDecl.getFormalCtTypeParameters();

		if (actualTypeArgs == null || formalTypeParams == null) { return fieldType; }

		for (int i = 0; i < formalTypeParams.size(); i++) {

			if (! typeParamName.equals( formalTypeParams.get( i ).getSimpleName() )) { continue; }

			if (actualTypeArgs.size() > i && actualTypeArgs.get( i ) != null) {
				return resolveSourceBackedTypeReference( actualTypeArgs.get( i ), externalTypes );

			}

			List<T> genericTypes = ownerInfo.getGenericTypes();

			if (genericTypes != null && genericTypes.size() > i) {
				T genericInfo = genericTypes.get( i );

				if (genericInfo.getTypeRef() != null) {
					return resolveSourceBackedTypeReference( genericInfo.getTypeRef(), externalTypes );

				}

				if (genericInfo.getType() != null && genericInfo.getType() != Object.class && ownerTypeRef.getFactory() != null) {
					return ownerTypeRef.getFactory().Type().createReference( genericInfo.getType() );

				}

			}

		}

		return fieldType;

	}

	private String buildCacheKey(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) {
			return "<null>";

		}

		List<CtTypeReference<?>> actualTypeArguments = typeRef.getActualTypeArguments();

		if (actualTypeArguments == null || actualTypeArguments.isEmpty()) {
			return typeRef.getQualifiedName();

		}

		return typeRef.getQualifiedName() + "<" + actualTypeArguments.stream().map( this::buildCacheKey ).collect( Collectors.joining( "," ) ) + ">";

	}

	public static CtTypeReference<?> resolveSourceBackedTypeReference(
		CtTypeReference<?> typeRef
	) {

		return resolveSourceBackedTypeReference( typeRef, Map.of() );

	}

	public static CtTypeReference<?> resolveSourceBackedTypeReference(
		CtTypeReference<?> typeRef, Map<String, CtType<?>> externalTypes
	) {

		if (typeRef == null) { return null; }

		CtType<?> decl = safeTypeDeclaration( typeRef );

		if (decl != null && ! decl.isShadow()) { return typeRef; }

		CtType<?> externalType = findExternalDeclaringType( typeRef.getQualifiedName(), typeRef.getSimpleName(), externalTypes );

		if (externalType == null) { return typeRef; }

		CtTypeReference<?> resolvedRef = externalType.getReference().clone();

		if (typeRef.getActualTypeArguments() != null && ! typeRef.getActualTypeArguments().isEmpty()) {
			resolvedRef
				.setActualTypeArguments(
					typeRef
						.getActualTypeArguments()
						.stream()
						.map( e -> resolveSourceBackedTypeReference( e, externalTypes ) )
						.collect( Collectors.toList() )
				);

		}

		return resolvedRef;

	}

	public static CtType<?> resolveSourceBackedType(
		CtTypeReference<?> typeRef
	) {

		return resolveSourceBackedType( typeRef, Map.of() );

	}

	public static CtType<?> resolveSourceBackedType(
		CtTypeReference<?> typeRef, Map<String, CtType<?>> externalTypes
	) {

		CtTypeReference<?> resolvedRef = resolveSourceBackedTypeReference( typeRef, externalTypes );

		if (resolvedRef == null) { return null; }

		CtType<?> decl = safeTypeDeclaration( resolvedRef );

		if (decl != null && ! decl.isShadow()) { return decl; }

		return findExternalDeclaringType( resolvedRef.getQualifiedName(), resolvedRef.getSimpleName(), externalTypes );

	}

	public static CtType<?> findExternalDeclaringType(
		String qualifiedName, String simpleName, Map<String, CtType<?>> externalTypes
	) {

		if (externalTypes == null || externalTypes.isEmpty()) { return null; }

		if (qualifiedName != null && ! qualifiedName.isBlank()) {
			CtType<?> exact = externalTypes.get( qualifiedName );

			if (exact != null) { return exact; }

		}

		if (simpleName == null || simpleName.isBlank()) { return null; }

		CtType<?> found = null;

		for (CtType<?> type : externalTypes.values()) {

			if (! simpleName.equals( type.getSimpleName() )) { continue; }

			if (found != null) { return null; }

			found = type;

		}

		return found;

	}

	public static boolean isComplexPojo(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) { return false; }

		String qName = typeRef.getQualifiedName();

		if (qName == null) { return false; }

		Class<?> clazz = loadClassFromTypeReference( typeRef );

		if (clazz != Object.class) {

			if (clazz.isPrimitive()) { return false; }
			if (clazz == String.class) { return false; }
			if (Number.class.isAssignableFrom( clazz )) { return false; }
			if (clazz == Boolean.class || clazz == Character.class) { return false; }
			if (Enum.class.isAssignableFrom( clazz )) { return false; }

		}

		return ! isLibraryTypeName( qName );

	}

	public static Class<?> loadClassFromTypeReference(
		CtTypeReference<?> typeRef
	) {

		if (typeRef == null) { return Object.class; }

		String qName = typeRef.getQualifiedName();

		if (qName == null) { return Object.class; }

		return loadClass( qName );

	}

	public static Class<?> loadClass(
		String qName
	) {

		if (qName == null) { return Object.class; }

		switch (qName) {
			case "boolean":
				return boolean.class;
			case "byte":
				return byte.class;
			case "short":
				return short.class;
			case "int":
				return int.class;
			case "long":
				return long.class;
			case "float":
				return float.class;
			case "double":
				return double.class;
			case "char":
				return char.class;
			case "void":
				return void.class;
			default:
				break;

		}

		try {
			return Class.forName( qName );

		} catch (Throwable ignored) {
			return Object.class;

		}

	}

	private static boolean shouldParseFields(
		CtTypeReference<?> typeRef, Class<?> rawType, Map<String, CtType<?>> externalTypes
	) {

		if (typeRef == null) { return false; }

		String qName = typeRef.getQualifiedName();

		if (qName == null || isLibraryTypeName( qName ) || qName.startsWith( "reactor." )) { return false; }

		if (rawType != null && rawType != Object.class && RouteUtil.isPojo( rawType )) { return true; }

		return resolveSourceBackedType( typeRef, externalTypes ) != null;

	}

	private static boolean isLibraryTypeName(
		String qName
	) {

		return qName.startsWith( "java." ) || qName.startsWith( "javax." ) || qName.startsWith( "jakarta." );

	}

	private static CtType<?> safeTypeDeclaration(
		CtTypeReference<?> typeRef
	) {

		try {
			return typeRef.getTypeDeclaration();

		} catch (Throwable ignored) {
			return null;

		}

	}

}
