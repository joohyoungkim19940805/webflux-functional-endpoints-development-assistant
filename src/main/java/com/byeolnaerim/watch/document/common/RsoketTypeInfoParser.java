package com.byeolnaerim.watch.document.common;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.byeolnaerim.watch.document.asyncapi.rsocket.RsoketTypeInfo;
import spoon.reflect.declaration.CtType;


/** Type parser for RSocket metadata. */
public final class RsoketTypeInfoParser extends TypeInfoParser<RsoketTypeInfo> {

	public RsoketTypeInfoParser() {

		super();

	}

	public RsoketTypeInfoParser(
		Map<String, CtType<?>> externalTypes
	) {

		super( externalTypes );

	}

	@Override
	protected RsoketTypeInfo createInfo() {

		return new RsoketTypeInfo();

	}

	@Override
	protected RsoketTypeInfo copyInfo(
		RsoketTypeInfo source
	) {

		RsoketTypeInfo copy = new RsoketTypeInfo();
		copy.setName( source.getName() );
		copy.setNullable( source.getNullable() );
		copy.setType( source.getType() );
		copy.setTypeRef( source.getTypeRef() );
		copy.setDescription( source.getDescription() );
		copy.setExample( source.getExample() );

		ArrayList<RsoketTypeInfo> genericTypes = new ArrayList<>( source.getGenericTypes().size() );
		for (RsoketTypeInfo genericType : source.getGenericTypes()) {
			genericTypes.add( copyInfo( genericType ) );

		}
		copy.setGenericTypes( genericTypes );

		LinkedHashMap<String, RsoketTypeInfo> fields = new LinkedHashMap<>();
		source.getFields().forEach( (name, info) -> fields.put( name, copyInfo( info ) ) );
		copy.setFields( fields );
		return copy;

	}

}
