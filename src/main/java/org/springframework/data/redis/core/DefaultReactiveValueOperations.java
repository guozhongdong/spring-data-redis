/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.core;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.springframework.data.redis.connection.ReactiveStringCommands;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.ReactiveSerializationContext;
import org.springframework.data.redis.serializer.ReactiveSerializationContext.SerializationTuple;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link ReactiveValueOperations}.
 *
 * @author Mark Paluch
 * @since 2.0
 */
public class DefaultReactiveValueOperations<K, V> implements ReactiveValueOperations<K, V> {

	private ReactiveRedisTemplate<K, V> template;

	public DefaultReactiveValueOperations(ReactiveRedisTemplate<K, V> template) {
		this.template = template;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#set(java.lang.Object, java.lang.Object)
	 */
	@Override
	public Mono<Boolean> set(K key, V value) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.set(rawKey(key), rawValue(value)));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#set(java.lang.Object, java.lang.Object, long, java.util.concurrent.TimeUnit)
	 */
	@Override
	public Mono<Boolean> set(K key, V value, long timeout, TimeUnit unit) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(unit, "TimeUnit must not be null!");

		return createMono(
				connection -> connection.set(rawKey(key), rawValue(value), Expiration.from(timeout, unit), SetOption.UPSERT));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#setIfAbsent(java.lang.Object, java.lang.Object)
	 */
	@Override
	public Mono<Boolean> setIfAbsent(K key, V value) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(
				connection -> connection.set(rawKey(key), rawValue(value), Expiration.persistent(), SetOption.SET_IF_ABSENT));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#setIfPresent(java.lang.Object, java.lang.Object)
	 */
	@Override
	public Mono<Boolean> setIfPresent(K key, V value) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(
				connection -> connection.set(rawKey(key), rawValue(value), Expiration.persistent(), SetOption.SET_IF_PRESENT));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#multiSet(java.util.Map)
	 */
	@Override
	public Mono<Boolean> multiSet(Map<? extends K, ? extends V> map) {

		Assert.notNull(map, "Map must not be null!");

		return createMono(connection -> {

			Mono<Map<ByteBuffer, ByteBuffer>> serializedMap = Flux.fromIterable(() -> map.entrySet().iterator())
					.collectMap(entry -> rawKey(entry.getKey()), entry -> rawValue(entry.getValue()));

			return serializedMap.flatMap(connection::mSet);
		});
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#multiSetIfAbsent(java.util.Map)
	 */
	@Override
	public Mono<Boolean> multiSetIfAbsent(Map<? extends K, ? extends V> map) {

		Assert.notNull(map, "Map must not be null!");

		return createMono(connection -> {

			Mono<Map<ByteBuffer, ByteBuffer>> serializedMap = Flux.fromIterable(() -> map.entrySet().iterator())
					.collectMap(entry -> rawKey(entry.getKey()), entry -> rawValue(entry.getValue()));

			return serializedMap.flatMap(connection::mSetNX);
		});
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#get(java.lang.Object)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Mono<V> get(Object key) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.get(rawKey((K) key)) //
				.map(this::readValue));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#getAndSet(java.lang.Object, java.lang.Object)
	 */
	@Override
	public Mono<V> getAndSet(K key, V value) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.getSet(rawKey(key), rawValue(value)).map(value()::read));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#multiGet(java.util.Collection)
	 */
	@Override
	public Mono<List<V>> multiGet(Collection<K> keys) {

		Assert.notNull(keys, "Keys must not be null!");

		return createMono(connection -> Flux.fromIterable(keys).map(key()::write).collectList().flatMap(connection::mGet)
				.map(byteBuffers -> {
					List<V> result = new ArrayList<>(byteBuffers.size());

					for (ByteBuffer buffer : byteBuffers) {

						if (buffer == null) {
							result.add(null);
						} else {
							result.add(readValue(buffer));
						}
					}

					return result;
				}));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#append(java.lang.Object, java.lang.String)
	 */
	@Override
	public Mono<Long> append(K key, String value) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(value, "Value must not be null!");

		return createMono(connection -> connection.append(rawKey(key), serialization().string().write(value)));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#get(java.lang.Object, long, long)
	 */
	@Override
	public Mono<String> get(K key, long start, long end) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.getRange(rawKey(key), start, end) //
				.map(string()::read));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#set(java.lang.Object, java.lang.Object, long)
	 */
	@Override
	public Mono<Long> set(K key, V value, long offset) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.setRange(rawKey(key), rawValue(value), offset));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#size(java.lang.Object)
	 */
	@Override
	public Mono<Long> size(K key) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.strLen(rawKey(key)));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#setBit(java.lang.Object, long, boolean)
	 */
	@Override
	public Mono<Boolean> setBit(K key, long offset, boolean value) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.setBit(rawKey(key), offset, value));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#getBit(java.lang.Object, long)
	 */
	@Override
	public Mono<Boolean> getBit(K key, long offset) {

		Assert.notNull(key, "Key must not be null!");

		return createMono(connection -> connection.getBit(rawKey(key), offset));
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.core.ReactiveValueOperations#getOperations()
	 */
	@Override
	public ReactiveRedisOperations<K, V> getOperations() {
		return template;
	}

	private <T> Mono<T> createMono(Function<ReactiveStringCommands, Publisher<T>> function) {

		Assert.notNull(function, "Function must not be null!");

		return template.createMono(connection -> function.apply(connection.stringCommands()));
	}

	private ByteBuffer rawKey(K key) {
		return serialization().key().write(key);
	}

	private ByteBuffer rawValue(V value) {
		return serialization().value().write(value);
	}

	private V readValue(ByteBuffer buffer) {
		return serialization().value().read(buffer);
	}

	private SerializationTuple<String> string() {
		return serialization().string();
	}

	private SerializationTuple<K> key() {
		return serialization().key();
	}

	private SerializationTuple<V> value() {
		return serialization().value();
	}

	private ReactiveSerializationContext<K, V> serialization() {
		return template.serialization();
	}
}
