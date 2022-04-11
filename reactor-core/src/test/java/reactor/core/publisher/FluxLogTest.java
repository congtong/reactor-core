/*
 * Copyright (c) 2020-2021 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;


import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import reactor.core.CorePublisher;
import reactor.core.Scannable;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

public class FluxLogTest {

	private static final class MetricState {

		private static final Logger LOGGER = Loggers.getLogger(MetricState.class);

		public static MetricState of(Scannable parent, List<String> collectedMetrics, @Nullable String publisherType) {
			
			if (!parent.isScanAvailable()) {
				LOGGER.warn("Attempting to activate metrics but the upstream is not Scannable. You might want to use `name()` (and optionally `tags()`) right before this MetricState");
				return new MetricState("reactor", Collections.singletonList("type-"+publisherType), collectedMetrics);
			}
			String name;
			List<String> tags = new ArrayList<>();
			String nameOrDefault = parent.name();
			if (parent.stepName()
				.equals(nameOrDefault)) {
				name = "reactor";
			}
			else {
				name = nameOrDefault;
			}
			parent.tags().forEach(t -> tags.add(t.getT1() + "-" + t.getT2()));

			if (parent instanceof GroupedFlux) {
				tags.add("type-GroupedFlux");
			}
			else if (parent instanceof Flux) {
				tags.add("type-Flux");
			}
			else if (parent instanceof Mono) {
				tags.add("type-Mono");
			}
			else if (parent instanceof ParallelFlux) {
				tags.add("type-ParallelFlux");
			}
			else if (parent instanceof CorePublisher) {
				tags.add("type-otherCorePublisher");
			}
			else {
				tags.add("type-Publisher");
			}

			return new MetricState(name, tags, collectedMetrics);
		}

		public final String name;
		public final List<String> tags;
		public final List<String> collectedMetrics;
		public boolean enabled;

		public MetricState(String name, List<String> tags, List<String> targetCollected) {
			this.name = name;
			this.tags = tags;
			this.collectedMetrics = targetCollected;
			this.enabled = true;
		}
	}

	private static final class MetricSequenceObserver<T> extends Flux.DefaultSequenceObserver<T> {

		private final MetricState metricState;
		private final String      id;

		public MetricSequenceObserver(MetricState state, String id) {
			this.metricState = state;
			this.id = id;
		}

		@Override
		public void onSubscription() {
			metricState.collectedMetrics.add("this is the beginning of " + metricState.name + " with id " + id);
		}

		@Override
		public void onNext(T value) {
			metricState.collectedMetrics.add(id + " => onNext of " + value);
		}

		@Override
		public void onComplete() throws Throwable {
			Thread.sleep(100);
			metricState.collectedMetrics.add(id + " => onComplete");
		}

		@Override
		public void onError(Throwable throwable) {
			metricState.collectedMetrics.add(id + " => onError of " + throwable);
		}

		@Override
		public void onTerminateOrCancel() {
			metricState.collectedMetrics.add("this is the end of " + metricState.name + " with id " + id);
		}
	}

	static <T> BiFunction<Scannable, ContextView, Flux.SequenceObserver<T>> fluxMetrics(List<String> registry) {
		return ((scannable, contextView) -> new MetricSequenceObserver<>(MetricState.of(scannable, registry, "flux"),
			contextView.getOrDefault("requestId", "noRequestId")));
	}

	@Test
	void todoMoveMe() {
		Flux<Integer> source = Flux.range(1, 5);

		List<String> collectedMetrics = new ArrayList<>();
//		source.observeWith(MetricSequenceObserver::new);
//		source.observeWith(MetricState::fromAssembly, (state, contextView) ->
//			new MetricSequenceObserver<>(state, contextView.getOrDefault("requestId", "noRequestId")));

		source
			.name("NAME")
			.transform(f -> new Publisher<Integer>() {
				@Override
				public void subscribe(Subscriber<? super Integer> s) {
					f.subscribe(Operators.toCoreSubscriber(s));
				}
			})
			.observeWith(fluxMetrics(collectedMetrics))
			.contextWrite(Context.of("requestId", "123"))
			.blockLast();

		assertThat(collectedMetrics).containsExactly(
			"this is the beginning of NAME with id 123",
			"123 => onNext of 1",
			"123 => onNext of 2",
			"123 => onNext of 3",
			"123 => onNext of 4",
			"123 => onNext of 5",
			"123 => onComplete",
			"this is the end of NAME with id 123"
		);
	}

	@Test
	public void scanOperator(){
		Flux<Integer> parent = Flux.just(1);
		SignalLogger<Integer> log = new SignalLogger<>(parent, "category", Level.INFO, true);
		FluxLog<Integer> test = new FluxLog<>(parent, log);

		assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		assertThat(test.scan(Scannable.Attr.RUN_STYLE)).isSameAs(Scannable.Attr.RunStyle.SYNC);
	}

	@Test
	public void scanFuseableOperator(){
		Flux<Integer> parent = Flux.just(1);
		SignalLogger<Integer> log = new SignalLogger<>(parent, "category", Level.INFO, true);
		FluxLogFuseable<Integer> test = new FluxLogFuseable<>(parent, log);

		assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(parent);
		assertThat(test.scan(Scannable.Attr.RUN_STYLE)).isSameAs(Scannable.Attr.RunStyle.SYNC);
	}

}