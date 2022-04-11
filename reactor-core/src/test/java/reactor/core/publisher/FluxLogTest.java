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


import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import reactor.core.CorePublisher;
import reactor.core.Scannable;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class FluxLogTest {

	private static final class MetricState {

		public static MetricState fromAssembly(Publisher<?> assembled, List<String> collectedMetrics) {
			String name;
			List<String> tags = new ArrayList<>();
			Scannable scannable = Scannable.from(assembled);
			if (scannable.isScanAvailable()) {
				String nameOrDefault = scannable.name();
				if (scannable.stepName()
					.equals(nameOrDefault)) {
					name = "reactor";
				}
				else {
					name = nameOrDefault;
				}
				scannable.tags().forEach(t -> tags.add(t.getT1() + "-" + t.getT2()));
			}
			else {
				name = "reactor";
			}

			if (assembled instanceof GroupedFlux) {
				tags.add("type-GroupedFlux");
			}
			else if (assembled instanceof Flux) {
				tags.add("type-Flux");
			}
			else if (assembled instanceof Mono) {
				tags.add("type-Mono");
			}
			else if (assembled instanceof ParallelFlux) {
				tags.add("type-ParallelFlux");
			}
			else if (assembled instanceof CorePublisher) {
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

		private final MetricState globalMetricState;
		private final String id;

		public MetricSequenceObserver(MetricState globalMetricState, String id) {
			this.globalMetricState = globalMetricState;
			this.id = id;
		}

		@Override
		public void onSubscription() {
			globalMetricState.collectedMetrics.add("this is the beginning of " + globalMetricState.name + " with id " + id);
		}

		@Override
		public void onNext(T value) {
			globalMetricState.collectedMetrics.add(id + " => onNext of " + value);
		}

		@Override
		public void onComplete() {
			globalMetricState.collectedMetrics.add(id + " => onComplete");
		}

		@Override
		public void onError(Throwable throwable) {
			globalMetricState.collectedMetrics.add(id + " => onError of " + throwable);
		}

		@Override
		public void onTerminateOrCancel() {
			globalMetricState.collectedMetrics.add("this is the end of " + globalMetricState.name + " with id " + id);
		}
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
			.observeWith()
			.observeWith(p -> MetricState.fromAssembly(p, collectedMetrics), (state, contextView) ->
			new MetricSequenceObserver<>(state, contextView.getOrDefault("requestId", "noRequestId")))
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