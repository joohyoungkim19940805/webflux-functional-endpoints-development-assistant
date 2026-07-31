package com.byeolnaerim.watch.document;


import java.util.Map;
import java.util.Set;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;


/**
 * Shared, read-only Spoon analysis result used by document generators.
 */
public record SpoonAnalysisContext(
	CtModel projectModel,
	Map<String, CtType<?>> externalTypes,
	Set<String> effectiveSourceClasspath
) {

	public SpoonAnalysisContext {

		externalTypes = Map.copyOf( externalTypes );
		effectiveSourceClasspath = Set.copyOf( effectiveSourceClasspath );

	}

}
