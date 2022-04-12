/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
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

import java.util.function.Function;

import reactor.core.CoreSubscriber;
import reactor.core.publisher.FluxListen.SequenceObserverSubscriber;
import reactor.util.context.ContextView;

/**
 * A generic per-Subscription side effect {@link Mono} that notifies a {@link SequenceListener} of most events.
 *
 * @author Simon Baslé
 */
public class MonoListen<T> extends InternalMonoOperator<T, T> {

	final Function<ContextView, SequenceListener<T>> observerGenerator;

	MonoListen(Mono<? extends T> source, Function<ContextView, SequenceListener<T>> observerGenerator) {
		super(source);
		this.observerGenerator = observerGenerator;
	}

	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) throws Throwable {
		//if the SequenceObserver cannot be created, all we can do is error the subscriber.
		//after it is created, in case doFirst fails we can additionally try to invoke doFinally.
		//note that if the later handler also fails, then that exception is thrown.
		SequenceListener<T> sequenceListener;
		try {
			//TODO replace currentContext() with contextView() when available
			sequenceListener = observerGenerator.apply(actual.currentContext().readOnly());
		}
		catch (Throwable generatorError) {
			Operators.error(actual, generatorError);
			return null;
		}

		try {
			sequenceListener.doFirst();
		}
		catch (Throwable observerError) {
			Operators.error(actual, observerError);
			sequenceListener.doFinally(SignalType.ON_ERROR);
			return null;
		}
		return new SequenceObserverSubscriber<>(actual, sequenceListener);
	}
}
