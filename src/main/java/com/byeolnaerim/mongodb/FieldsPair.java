package com.byeolnaerim.mongodb;


import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

	private Condition queryType;

	public Condition getQueryType() { return this.queryType; }

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


	/**
	 * range 리스트([from, to])를 받아 between/gte/lte를 자동으로 선택.
	 * - 둘 다 있으면 between
	 * - from만 있으면 gte
	 * - to만 있으면 lte
	 * - 둘 다 없으면 null
	 * ※ List<Instant>, List<LocalDate> 등은 제네릭 타입만 달라 오버로드가 불가능하므로
	 * List<? extends T> 하나로 공통 처리한다.
	 */
	public static <K, T> FieldsPair<K, Object> autoRangePair(
		K field, List<? extends T> range
	) {

		if (range == null || range.isEmpty())
			return null;

		T from = range.size() >= 1 ? range.get( 0 ) : null;
		T to = range.size() >= 2 ? range.get( 1 ) : null;

		return buildAutoRangePair( field, from, to );

	}

	/** Instant 전용 오버로드 */
	public static <K> FieldsPair<K, Object> autoRangePair(
		K field, Instant from, Instant to
	) {

		return buildAutoRangePair( field, from, to );

	}

	/** LocalDateTime 전용 오버로드 */
	public static <K> FieldsPair<K, Object> autoRangePair(
		K field, LocalDateTime from, LocalDateTime to
	) {

		return buildAutoRangePair( field, from, to );

	}

	/** LocalDate 전용 오버로드 */
	public static <K> FieldsPair<K, Object> autoRangePair(
		K field, LocalDate from, LocalDate to
	) {

		return buildAutoRangePair( field, from, to );

	}

	/**
	 * 내부 공통 로직 (오버로드/리스트 모두 여기로 수렴)
	 */
	private static <K, T> FieldsPair<K, Object> buildAutoRangePair(
		K field, T from, T to
	) {

		if (from != null && to != null)
			return pair( field, List.of( from, to ), Condition.between );
		if (from != null)
			return pair( field, from, Condition.gte );
		if (to != null)
			return pair( field, to, Condition.lte );
		return null;

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
