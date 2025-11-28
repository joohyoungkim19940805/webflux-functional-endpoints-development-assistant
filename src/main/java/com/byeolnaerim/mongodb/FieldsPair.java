package com.byeolnaerim.mongodb;


import java.time.Duration;
import java.util.Map;
import lombok.Getter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


public class FieldsPair<K, V> implements Map.Entry<K, V> {

	public static enum Condition {

		eq, // Equal
		notEq, // Not equal
		between, // Between (expects a range)
		gt, // Greater than
		gte, // Greater than or equal
		lt, // Less than
		lte, // Less than or equal
		in, // In (expects a collection)
		notIn, // Not in (expects a collection)
		like, // Like (for pattern matching)
		regex, // Regular expression
		exists, // Field exists
		isNull, // Field is null
		isNotNull, // Field is not null
		all, // array all match
		near, // coordinate x,y search legacy 2d
		nearSphere, // GeoJSON + 2dsphere
		elemMatch;

		public static enum LikeOperator {
			i, m, s, x, l, u
		}

	}

	private K fieldName;

	private V fieldValue;

	@Getter
	private Condition queryType;

	public FieldsPair(
						K fieldName,
						V fieldValue
	) {

		this.fieldName = fieldName;
		this.fieldValue = fieldValue;
		this.queryType = Condition.eq; // 기본값은 eq

	}

	public FieldsPair(
						K fieldName,
						V fieldValue,
						Condition queryType
	) {

		this.fieldName = fieldName;
		this.fieldValue = fieldValue;
		this.queryType = queryType;

	}

	public FieldsPair(
						K fieldName,
						Condition queryType
	) {

		this.fieldName = fieldName;
		this.fieldValue = null;
		this.queryType = queryType;

	}

	public K getFieldName() { return fieldName; }

	public V getFieldValue() { return fieldValue; }

	@Override
	public K getKey() {

		// TODO Auto-generated method stub
		return this.fieldName;

	}

	@Override
	public V getValue() {

		// TODO Auto-generated method stub
		return this.fieldValue;

	}

	@Override
	public V setValue(
		V value
	) {

		// TODO Auto-generated method stub
		this.fieldValue = value;
		return this.fieldValue;

	}

	public static <K, V> FieldsPair<K, V> pair(
		K k, V v
	) {

		return new FieldsPair<>( k, v );

	}

	public static <K, V> FieldsPair<K, V> pair(
		K k, V v, Condition queryType
	) {

		return new FieldsPair<>( k, v, queryType );

	}

	public static <K, V> FieldsPair<K, V> pair(
		K k, Condition queryType
	) {

		return new FieldsPair<>( k, queryType );

	}

	public static void main(
		String a[]
	)
		throws InterruptedException {

		Mono<Integer> test = Mono.just( 4 );

		Flux<Integer> flux = Flux.just( 4, 5, 6, 7 );

		flux
			.map( e -> e + 1 )
			.mergeWith( test )
			.map( e -> {

				System.out.println( e );
				return e;

			} )
			.subscribe();

		Thread.sleep( Duration.ofHours( 9999 ) );



	}

}
