package com.byeolnaerim.watch.document.common;


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
