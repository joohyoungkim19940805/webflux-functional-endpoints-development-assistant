package com.byeolnaerim.watch.document.common;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo;
import com.byeolnaerim.watch.document.swagger.functional.HandlerInfo.LayerPosition;
import spoon.reflect.declaration.CtType;


/** Type parser for Swagger handler metadata. */
public final class HandlerTypeInfoParser extends TypeInfoParser<HandlerInfo.Info> {

	public HandlerTypeInfoParser() {

		super();

	}

	public HandlerTypeInfoParser(
									Map<String, CtType<?>> externalTypes
	) {

		super( externalTypes );

	}

	@Override
	protected HandlerInfo.Info createInfo() {

		return new HandlerInfo.Info();

	}

	@Override
	protected HandlerInfo.Info copyInfo(
		HandlerInfo.Info source
	) {

		HandlerInfo.Info copy = new HandlerInfo.Info();
		copy.setName( source.getName() );
		copy.setDefaultValue( source.getDefaultValue() );
		copy.setRequired( source.getRequired() );
		copy.setNullable( source.getNullable() );
		copy.setType( source.getType() );
		copy.setTypeRef( source.getTypeRef() );
		copy.setPosition( source.getPosition() );
		copy.setDescription( source.getDescription() );
		copy.setExample( source.getExample() );

		ArrayList<HandlerInfo.Info> genericTypes = new ArrayList<>( source.getGenericTypes().size() );
		for (HandlerInfo.Info genericType : source.getGenericTypes()) {
			genericTypes.add( copyInfo( genericType ) );

		}
		copy.setGenericTypes( genericTypes );

		LinkedHashMap<String, HandlerInfo.Info> fields = new LinkedHashMap<>();
		source.getFields().forEach( (name, info) -> fields.put( name, copyInfo( info ) ) );
		copy.setFields( fields );
		return copy;

	}

	@Override
	protected void initializeInfo(
		HandlerInfo.Info info
	) {

		info.setRequired( Boolean.FALSE );
		info.setNullable( Boolean.TRUE );

	}

	@Override
	protected void markGeneric(
		HandlerInfo.Info info
	) {

		info.setPosition( LayerPosition.GENERIC );

	}

	@Override
	protected void markField(
		HandlerInfo.Info info
	) {

		info.setPosition( LayerPosition.FIELDS );

	}

}
