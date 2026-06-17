package com.byeolnaerim.watch.document.common;


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

}
